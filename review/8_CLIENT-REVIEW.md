# `mqtt-sn-client` — Code Review

Scope: `mqtt-sn-client/src/main/java`. Lens: SOLID, null/thread safety, state-machine correctness in the connect / sleep / wake / disconnect cycle. Smaller blast radius than the gateway, but every downstream device implementor will copy this. File:line references are 1-based.

## TL;DR

One clear bug:

- **`MqttsnClient.publish()` reads the `session` *field* (not the local) when QoS=-1** — line 272 references `session` before the local declaration on line 278, so the predefined-topic lookup runs against whatever's in the instance field. For a fresh client that hasn't yet connected, the field is `null` → NPE. For a reconnected client, it's the previous session.

Plus:

- **`session` field is non-volatile** and used in a DCL pattern inside `discoverGatewaySession` — broken under the Java memory model.
- **`supervisedSleepWithWake` does not protect against spurious wakeup** — author left a `// TODO` at the wait site.
- **`setWillData` does two sequential `*UPD` round-trips with no atomicity** — first succeeds, second fails → broker has new topic with stale data.
- **`close()` wraps typed `MqttsnException` into plain `RuntimeException`**, losing type information.
- **`MqttsnClientMessageHandler.handleRegister` casts directly to v1.2 `MqttsnRegister`** — same protocol-version coupling the gateway handler exhibits, here without even a branch.

---

## 1. Critical / High

### 1.1 `publish()` reads stale `session` field before local declaration
`MqttsnClient.java:264–288` (`publish`):

```java
public MqttsnWaitToken publish(String topicName, int QoS, ...) {
    ...
    if (QoS == -1) {
        if (topicName.length() > 2) {
            Integer alias = registry.getTopicRegistry().lookupPredefined(session, topicName);   // <-- 'session' = field
            if (alias == null) throw new MqttsnExpectationFailedException(...);
        }
    }

    ISession session = checkSession(QoS >= 0);    // <-- local declared HERE
    ...
}
```

On line 272 (inside the `QoS == -1` branch) the only `session` in scope is the **instance field** declared at `MqttsnClient.java:66`. Three outcomes depending on prior state:

- **Fresh client, no prior connect**: field is `null` → `lookupPredefined(null, topic)` NPE.
- **Connected client**: field points at the current session — happens to be the same one `checkSession` will return → behaves correctly *by accident*.
- **Reconnected client, new gateway**: field still points at the previous session (the old `INetworkContext`) → predefined alias lookup happens against the wrong registry partition.

**Fix**: move the QoS=-1 validation block *after* the `ISession session = checkSession(...)` line.

### 1.2 `session` field used in DCL but is not `volatile`
`MqttsnClient.java:66`: `protected ISession session;` (no `volatile`).
`MqttsnClient.discoverGatewaySession:592–614`:
```java
if (session == null) {
    synchronized (functionMutex) {
        if (session == null) {
            ...
            session = registry.getSessionRegistry().createNewSession(...);
        }
    }
}
return session;
```

Classic broken DCL. Without `volatile`, the JVM is allowed to publish the field reference before the constructor of `SessionBeanImpl` completes its writes — a second thread can see `session != null` (return from method) but observe an incompletely-initialised session bean. Either mark `session` volatile, or replace the whole thing with `computeIfAbsent` on the session registry.

### 1.3 `supervisedSleepWithWake` does not handle spurious wakeup
`MqttsnClient.java:351–395`:
```java
synchronized (sleepMonitor){
    //TODO protect against spurious wake up here
    sleepMonitor.wait(wake * 1000);
}
wake(maxWaitTimeMillis);
```
The author confirms in comment. A spurious wakeup ends the sleep window early; `wake(...)` runs prematurely, gateway gets unexpected PINGREQ. Standard fix: loop while a deadline hasn't been reached, recomputing remaining time each iteration.

### 1.4 `setWillData` is two non-atomic round-trips
`MqttsnClient.java:201–236`. Sends WILLTOPICUPD, waits, then sends WILLMSGUPD, waits. If WILLTOPICUPD succeeds and WILLMSGUPD fails, the gateway now associates a new topic with stale will data. The spec doesn't provide an atomic primitive, so the client should at minimum:
- Detect the partial-success window and resend until both succeed, or
- On failure of the second, attempt to roll back the first (resend WILLTOPICUPD with the prior values).

Also: this method does **not** wrap its work in `synchronized(functionMutex)` like its siblings (`connect`, `sleep`, `wake`, `disconnect`). Concurrent connect/disconnect during a will update races.

### 1.5 `MqttsnClientMessageHandler.handleRegister` is v1.2-only
`MqttsnClientMessageHandler.java:38–45`:
```java
protected IMqttsnMessage handleRegister(IMqttsnMessageContext context, IMqttsnMessage message) ... {
    MqttsnRegister register = (MqttsnRegister) message;   // v1.2 only
    ...
}
```
A v2.0 REGISTER (codec returns a different class) throws `ClassCastException` with no useful context. The gateway handler at least branches on `context.getProtocolVersion()`; the client doesn't. Either branch or use the marker interfaces (codec review §5.1).

---

## 2. High — concurrency / lifecycle

### 2.1 `publish` auto-starts processing when client is asleep/disconnected
Line 279–281: if state is `ASLEEP` or `DISCONNECTED`, `publish` silently calls `startProcessing(true)`. A user calling `publish` after `sleep` (which intentionally stopped processing) gets the side effect without asking. The spec lets a client publish QoS-1 without a session; everything else should require explicit `connect` / `wake`.

### 2.2 `activateManagedConnection` swallows exceptions and bumps `errorRetryCounter`
`MqttsnClient.java:620–678`. The retry loop:
- on success: `resetErrorState()` → `errorRetryCounter = 0`
- on failure: `errorRetryCounter++; clearInflight(...)` — clears the inflight map but the message itself is never DLQ'd or surfaced. Same silent-drop pattern as core/gateway/paho.
- after `MAX_ERROR_RETRIES`: `disconnect(false, true, true)` — full disconnect with deep clean, dropping any remaining state.

The outer `catch (Exception e)` is the only handler in the loop; an `InterruptedException` is wrapped into the same path → the interrupt is lost.

### 2.3 `discoverGatewaySession` loses interrupt status
Line 607: `catch (NetworkRegistryException | InterruptedException e) { throw new MqttsnException(...); }`. On `InterruptedException`, the thread's interrupt flag is cleared by the JDK and the catch does not re-interrupt. Add `Thread.currentThread().interrupt();` before throwing.

### 2.4 No re-discovery if the bound gateway changes
Once `session` is set in `discoverGatewaySession`, it is never reset by the discovery path — only by `clearState`. If an ADVERTISE from a different gateway shows up (the protocol's discovery feature), the client stays bound to the original. The client's design here is "first match wins forever". Document or rotate.

### 2.5 `close()` wraps checked exceptions in `RuntimeException`
`MqttsnClient.java:545–569`. Two `throw new RuntimeException(e)` calls in `close()` for `MqttsnException`. The class exposes typed exceptions everywhere else; close should either throw `MqttsnException` (declared) or follow `AutoCloseable.close() throws Exception`. Also: the class doesn't `implement AutoCloseable` despite having a `close()` method — add it so try-with-resources is available.

### 2.6 `notifyServicesStarted` only checks the *first* network context
Line 119–130: pulls `registry.getNetworkRegistry().first()` and rejects boot only if discovery is disabled *and* no contexts are configured. With multiple gateways in the registry, only the first one is checked. Acceptable for the "at least one is configured" invariant, but it's not what the code reads like.

### 2.7 `disconnect(...)` flips client state *before* sending the DISCONNECT
`MqttsnClient.java:511–543`: modifies `ClientState.DISCONNECTED` (line 520), *then* enqueues the DISCONNECT message (line 522). If `sendRemoteDisconnect` is true but the send fails, we have locally said "disconnected" while the gateway still thinks we're ACTIVE. Either set state after a successful send, or send first and only update on ack.

---

## 3. Medium — SOLID / API hygiene

### 3.1 `MqttsnClient` is an 819-line god class
46 public/protected methods. Mixes:
- Public API surface (`connect`, `publish`, `subscribe`, `unsubscribe`, `sleep`, `wake`, `ping`, `helo`, `disconnect`, `setWillData`, `clearWillData`, `close`, `isConnected`, `isAsleep`, …)
- Managed-connection thread + reconnect (`activateManagedConnection`, `resetConnection`, `errorRetryCounter`)
- Session discovery (`discoverGatewaySession`, `checkSession`)
- State transitions (`stopProcessing`, `startProcessing`, `clearState`, `resetErrorState`)
- Internals (`stateChangeResponseCheck`, `getCurrentTransport`)

Split into `MqttsnClient` (public API), `MqttsnClientReconnectSupervisor` (managed thread), `MqttsnSessionDiscoverer`.

### 3.2 `IMqttsnClient` API leaks transport concepts
The public interface exposes `sleep`, `wake`, `helo`, `ping` — all four are protocol primitives. Most applications want `publish` and `subscribe`. Consider splitting into a "minimal" interface and an "advanced" sub-interface for power users.

### 3.3 `publish` overloads vary in QoS interpretation
The QoS=-1 branch enforces predefined/short topics; the QoS≥0 path validates via `checkSession`. A single `publish` method with side-conditional validation is brittle. Two methods (`publishConnectionless(...)` for QoS-1, `publish(...)` for the rest) would tell users the truth.

### 3.4 `disconnect` has four overloads
Lines 488, 498, 507, 511. Each forwards to the next with different defaults: `disconnect()`, `disconnect(boolean stopTransport)`, `disconnect(int waitTime, TimeUnit unit)`, the full private overload. Use a builder or pass a single `DisconnectOptions` struct.

### 3.5 `MqttsnClientMessageHandler` doesn't handle v2.0 REGISTER
See §1.5. The handler couples to v1.2 wire types directly. If the marker-interface refactor from the codec review lands, this becomes `IMqttsnRegisterPacket` and the cast disappears.

---

## 4. Medium — null / state hygiene

### 4.1 `MqttsnClient.isConnected()` synchronizes the read path
Line 132–144: `synchronized(functionMutex)` around a state read. Hot calls (e.g. UI polling) serialise against connect/disconnect/publish. Either accept a stale read (no sync) or expose a `Status` snapshot object.

### 4.2 `MqttsnClient.wake()` checks state inside the lock but operates outside session-state guarantees
Line 426–456: checks `ASLEEP`, then issues PINGREQ. While inside `synchronized(functionMutex)`, `clearState` could be triggered by another path that holds the same mutex? No — same mutex serialises. But: if a remote DISCONNECT arrives in between, the gateway has already terminated the session and the PINGREQ goes to dead air. Not a bug today (the catch path handles failure) but worth documenting.

### 4.3 `MqttsnClient.connect()` discards `MqttsnException` into `MqttsnClientConnectException`
Line 195: `throw new MqttsnClientConnectException(e);` — wraps `MqttsnExpectationFailedException`. The wrapper exception type is fine, but it's only thrown for that specific subtype, not for any other failure mode. Either catch all `MqttsnException` here, or document why only one subclass is wrapped.

### 4.4 `Example.java` shipped under `src/main`
`mqtt-sn-client/src/main/java/org/slj/mqtt/sn/client/impl/examples/Example.java` is example code in the production source tree. Either move to `src/test/java` or `src/main/examples`, or accept it ships in the jar.

---

## 5. Low — style / micro

- `MqttsnClient.java:66`: declare `session` as `protected volatile ISession session;` (cross-references §1.2).
- `MqttsnClient.java:230–234`: stale comment ("CONNECT" inside the will-update catch).
- `MqttsnClient.java:362`: `(int) Math.min(duration, timeLeft / 1000)` — cast loses precision on long; minor.
- `MqttsnClient.java:545`: `close()` is the only method *not* synchronized on `functionMutex`. If a thread calls `publish` while another calls `close`, the publish may run after disconnect.
- `MqttsnClientMessageHandler.java:40`: no null-guard on the cast.
- `MqttsnClientMessageHandler.java:48`: comment "Dont acknowledge the DISCONNECT in client mode" — confirm/document the behaviour matches the v2.0 spec.
- `cli/` and `examples/` folders share the runtime; `ClientInteractiveMain` and friends should be in a `cli` jar separate from the library.

---

## Recommended sequencing of fixes

1. **§1.1 — move the `QoS==-1` topic validation after `checkSession`**. One-line diff; closes a fresh-client NPE.
2. **§1.2 — `volatile` on `session` (or `computeIfAbsent`)**. Memory-model fix.
3. **§1.3 — wrap `sleepMonitor.wait` in a deadline loop**. Removes the author's outstanding TODO and an actual bug at the same time.
4. **§1.5 — make `MqttsnClientMessageHandler.handleRegister` version-aware** (or use the marker interface refactor when it lands).
5. **§2.3 — restore `Thread.currentThread().interrupt()` in `discoverGatewaySession`**.
6. **§2.7 — set DISCONNECTED state only after a successful remote DISCONNECT** (or set it last in the `finally`).
7. **§1.4 — `setWillData` synchronization + atomicity decision** (resend-until-consistent vs roll-back). Document the chosen contract.
8. **Then** the SOLID work (§3.1, §3.4) and the `Example.java`/`cli` packaging cleanup (§4.4).
