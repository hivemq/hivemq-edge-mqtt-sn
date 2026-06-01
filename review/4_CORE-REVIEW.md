# `mqtt-sn-core` — Code Review

Scope: `mqtt-sn-core/src/main/java` — SPI, session/state/registry impls, UDP transport, persistence, utils. Lens: SOLID, null/thread-safety, lifecycle hygiene, light security review of the utility primitives that the protection module depends on. File:line references are 1-based.

## TL;DR

Two findings here are **stop-shipping**:

- `MqttsnUdpTransport.stop()` has an inverted condition (`if(running && !stopping)` after setting `stopping = true`) → the body **never runs**. Sockets aren't closed, threads aren't interrupted. This is the root cause of the leaked-resources / hung-shutdown family of issues that the recent gateway commits have been chasing.
- `AbstractMqttsnTransport.receiveFromTransportInternal` (line ~91) has an **operator-precedence bug** in the clientId-mismatch check (`clientId == null || "".equals(clientId.trim()) && message.getMessageType() == PINGREQ`). The intended group is `(null || empty) && isPingreq`. Today, any non-PINGREQ packet from a context with a null `clientId` silently takes the "trust the previous clientId" branch — this is one route by which a misrouted packet can be accepted against the wrong session.

Beyond those:
- `Security.verifyHMac` uses `Arrays.equals` — **timing-attack vulnerable** for any MAC verification consumer.
- `MqttsnUtils.randomBytes` uses `ThreadLocalRandom` (non-cryptographic). Currently only used by a test/bridge helper, but it's an obvious foot-gun for any future protection-extension code that pulls it in.
- `AbstractMqttsnMessageStateService` is 894 lines with a mix of synchronized & unsynchronized maps for the same data — same area as the recently fixed Issue #69 deadlock. It is still fragile.
- `IMqttsnRuntimeRegistry` is a 35-method service locator that every other class depends on. The codebase pays for this every time it touches a new SPI.

---

## 1. Critical — concurrency / shutdown bugs

### 1.1 `MqttsnUdpTransport.stop()` body is unreachable
`MqttsnUdpTransport.java:135–168`:
```java
volatile boolean stopping = false;
@Override
public void stop() throws MqttsnException {
    stopping = true;                       // <-- set true
    if (running && !stopping) {            // <-- then require !stopping  (always false)
        ...
        socket.close();
        receiverThread.interrupt();
        ...
        stopping = false;
    }
}
```
The body never executes. The socket is never closed and the receiver/broadcast threads are never interrupted. `super.stop()` is also skipped, so `running` is never flipped back. Calling `stop()` a second time *also* skips the body because `stopping` is permanently `true`.

**Fix**: drop `!stopping` (and probably the `stopping` flag entirely; use `running` for the state check and CAS-style guard with `AtomicBoolean` if needed).

This almost certainly explains lingering threads after gateway shutdown, port-in-use errors on restart, and accumulated `DatagramSocket` instances under integration tests.

### 1.2 `receiveFromTransportInternal` clientId-mismatch precedence bug
`AbstractMqttsnTransport.java` (the line numbered ~91 in the body printed earlier):
```java
if (clientId == null || "".equals(clientId.trim()) && message.getMessageType() == MqttsnConstants.PINGREQ) {
    logger.info("... received with no clientId, continue with previous clientId on network address ...");
}
```
`&&` binds tighter than `||`, so this is:
```java
if (clientId == null
    || ("".equals(clientId.trim()) && message.getMessageType() == PINGREQ))
```
A non-PINGREQ packet (CONNECT, PUBLISH, SUBSCRIBE, …) arriving with a literal null clientId hits the **first branch** and is accepted as belonging to the previously authenticated context on that network address. Anyone who can spoof source address + send a malformed identification packet bypasses the mismatch check.

**Fix**:
```java
if ((clientId == null || "".equals(clientId.trim())) && message.getMessageType() == PINGREQ) { ... }
```
…and add a unit test for the case `null clientId + non-PINGREQ` to lock the behavior in.

### 1.3 `AbstractMqttsnMessageStateService.flushOperations` lock discipline is inconsistent
`AbstractMqttsnMessageStateService.java:62`: `flushOperations = new HashMap<>();` — plain `HashMap`, no `Collections.synchronizedMap` wrapper unlike the four neighboring fields (lines 65–68). It's then read at lines 74–75 and 81–82 *without* synchronization, and modified at lines 90–91, 101–102, 113 *with* `synchronized (flushOperations)`. This combination is the canonical setup for `ConcurrentModificationException` and lost updates. Replace with `ConcurrentHashMap` and remove the explicit sync blocks, or sync all reads.

This area was touched by the recent commit `5f2e934` ("Issue 69 - Potential deadlock on reaping messages"); the deadlock is plausibly the same bug seen from another angle.

### 1.4 `MqttsnInMemoryMessageStateService.doWork` iterates while mutating
`MqttsnInMemoryMessageStateService.java:53–67`:
```java
synchronized (inflightMessages) {
    Iterator<IClientIdentifierContext> itr = inflightMessages.keySet().iterator();
    while (itr.hasNext()) {
        IClientIdentifierContext context = itr.next();
        clearInflightInternal(context, System.currentTimeMillis());
    }
}
```
`clearInflightInternal` calls back into the registry hierarchy and (via the parent) into `removeInflight`, `addInflightMessage`, etc. If any of those modifies `inflightMessages` (e.g. removing an empty entry), the iterator throws `ConcurrentModificationException`. Snapshot the key set into a `List` before iterating.

### 1.5 `MqttsnSessionRegistry.getSession` uses DCL but `sessionLookup` is a ConcurrentHashMap
`MqttsnSessionRegistry.java:64–76`:
```java
ISession session = sessionLookup.get(context);
if (session == null && createIfNotExists) {
    synchronized (sessionLookup) {
        session = sessionLookup.get(context);
        if (session == null) session = createNewSession(context);
    }
}
```
The double-checked locking pattern is misapplied: `sessionLookup` is a `ConcurrentHashMap`, but `createNewSession` does `sessionLookup.put(context, session)` *outside* the `synchronized` block. So while DCL prevents two competing `createNewSession` calls under that monitor, concurrent calls from any other call site (e.g. `cleanSession`, ad-hoc puts) aren't excluded. **Replace with `sessionLookup.computeIfAbsent(context, c -> new SessionBeanImpl(c, ClientState.DISCONNECTED))`** — atomic and one line.

### 1.6 Listener lists in `AbstractMqttsnRuntime` are `ArrayList`
`AbstractMqttsnRuntime.java:57–66`: `publishReceivedListeners`, `publishSentListeners`, `sendFailureListeners`, `connectionListeners`, `trafficListeners` are plain `ArrayList`s, mutated by `addListener` and traversed by hot-path callbacks. `activeServices` (line 67) IS `synchronizedList` — so the pattern is known; it's been forgotten for the others. Use `CopyOnWriteArrayList` (read-heavy).

### 1.7 `AbstractMqttsnService.start` ordering
`AbstractMqttsnService.java:38–41`:
```java
this.registry = runtime;   // non-volatile
running = true;            // volatile
```
A reader that observes `running()==true` is not guaranteed to see a non-null `registry` (the volatile write is on `running`, the publication of `registry` piggybacks on it — actually OK because the write to `registry` happens-before the volatile store… **wait, yes that's the correct ordering**). My initial flag was wrong; the volatile write on `running` provides the publication for the preceding `registry` write. *No bug here*. Worth a sentence of comment though.

### 1.8 `MqttsnUdpTransport.bind` and `MqttsnUdpTransport.stop` race
`bind()` is `synchronized`, `stop()` is not. They read/write the same `socket`, `receiverThread`, `broadcastSocket`, `broadcastThread` fields. Two-phase start/stop without the same monitor is a race. Make `stop()` `synchronized` (and fix 1.1 while you're there).

### 1.9 `NetworkAddressRegistry.bindContexts` is not atomic
`NetworkAddressRegistry.java:114–118` puts into three maps individually. A reader that catches the registry mid-update sees an inconsistent view (`networkContextRegistry.containsKey(c)` true, `mqttsnContextRegistry.get(s)` null). Wrap the three puts in a lock, or use a single composite-key map.

---

## 2. High — input/lifecycle safety

### 2.1 `MqttsnUdpTransport` catches `Throwable`
Line 118 in the receiver loop: `catch(Throwable e) { logger.error("uncaught exception listening for datagrams", e); }`. Catches `OutOfMemoryError` and `ThreadDeath`. Narrow to `Exception`. Same pattern in `AbstractMqttsnTransport.receiveFromTransportInternal` (the `catch(Throwable t)` at the bottom that auto-sends a SERVER_UNAVAILABLE disconnect — convenient but also catches `Error`).

### 2.2 `MqttsnUdpTransport` reuses receive buffer across packets
Line 87: `byte[] buff = new byte[bufSize]` then line 91: `new DatagramPacket(buff, buff.length)`. The packet's backing array is shared until line 121 (`buff = new byte[bufSize]` in `finally`). If `receiveDatagramInternal` ever hands the buffer off async, the next iteration overwrites it. Verify `drain(bb)` copies (likely OK) and document; or allocate per packet.

### 2.3 `MqttsnUdpTransport.broadcast` opens a new socket per call
Line 212: `try (DatagramSocket socket = new DatagramSocket()) { ... }`. Under high broadcast volume this exhausts ephemeral ports and adds syscall cost. Hold a `broadcastSocket` instance and reuse.

### 2.4 `MqttsnUdpTransport.sendDatagramInternal` may do DNS on the I/O thread
Line 193: `InetAddress.getByName(address.getHostAddress())`. If the registry ever stores a hostname instead of a numeric address, this blocks on DNS. Resolve once at registration; don't re-resolve per send.

### 2.5 `AbstractMqttsnTransport.receiveFromTransportInternal` doesn't promote auto-assigned clientId
The local `assignedClientId` (line ~73 in the body printed earlier) is initialised to `false` and never reassigned to `true`, even though the same method just generated an auto-clientId for v2.0 (line ~78). `authorizeContext` therefore receives `assignedClientId = false` for a clientId the gateway just made up. If downstream code branches on that flag (likely), the audit log lies.

### 2.6 `MqttsnFilesystemStorageService.saveFile` path-traversal check is partial
Line 236: `fileName.contains(File.separator) || fileName.contains("..")` — misses Windows backslash, Unicode-encoded separators, and symlinks already in `path`. Acceptable for a single-user dev workstation; not for a service that may consume user-supplied names. If filenames come from network traffic, harden this.

### 2.7 `MqttsnFilesystemStorageService.writePreferenceInternal` rewrites the entire XML on every change
Line 144 onwards. Single-key updates load and re-serialise the full `properties`. Acceptable for small configs; quadratic-ish for any larger set. Also no `fsync` — power loss truncates the file mid-write.

### 2.8 `AbstractMqttsnMessageStateService.reapInflight` silently drops messages
Line ~745:
```java
} catch (MqttsnQueueAcceptException e) {
    //queue is full cant put it there
}
```
No log, no DLQ, no listener notification. A publish that timed out and that the queue rejected on requeue simply vanishes. At minimum log at WARN and push to DLQ.

### 2.9 `AbstractMqttsnMessageStateService.getNextMsgId` has redundant clamping and modulo
Lines 632–642:
```java
startAt = startAt % MqttsnConstants.UNSIGNED_MAX_16;
startAt = Math.max(Math.max(1, registry.getOptions().getMsgIdStartAt()), startAt);
...
startAt = ++startAt % MqttsnConstants.UNSIGNED_MAX_16;
```
- `UNSIGNED_MAX_16` is 65535, but `% 65535` skips the value 65535. For a proper 16-bit wrap-around you want `% 65536`. (Possibly intentional since msgId 0 is reserved, but it's worth a comment.)
- `++startAt % …` is the side-effect-in-expression pattern; rewrite as `startAt = (startAt + 1) % …`.
- The "no msg id available" `throw` at line 645 is unreachable as written (the loop's exit condition is the same predicate).

### 2.10 `NetworkAddressRegistry.findForClientId` returns `null` instead of `Optional.empty()`
Line 99: returning `null` from a method declared to return `Optional<…>` is a hard rule violation; the next time a caller does `.orElse(…)` they NPE.

---

## 3. High — security primitives

### 3.1 `Security.verifyHMac` uses non-constant-time comparison
`Security.java:198`:
```java
return Arrays.equals(hmac, hmac(algorithm, secretKey, readOriginalData(...), false));
```
`Arrays.equals` short-circuits on the first mismatching byte. For a MAC verifier exposed in any latency-observable code path, this is a textbook timing attack — recover the MAC byte by byte. Use `MessageDigest.isEqual(a, b)`, which is constant-time per JDK spec.

### 3.2 `MqttsnUtils.randomBytes` uses `ThreadLocalRandom`
`MqttsnUtils.java:212–216`:
```java
public static byte[] randomBytes(int length){
    byte[] a = new byte[length];
    ThreadLocalRandom.current().nextBytes(a);
    return a;
}
```
`ThreadLocalRandom` is a fast non-cryptographic PRNG seeded from `System.nanoTime`. Today's only caller is `LoadGeneratingBridge` (a test helper). But the method's name (`randomBytes`) is exactly what any future protection extension will reach for — at which point you have a silent crypto downgrade. **Either rename to `nonSecureRandomBytes` and route real callers to `SecureRandom`, or change the implementation to `SecureRandom.getInstanceStrong()`.**

### 3.3 `MqttsnSecurityException` thrown for I/O failures
`MqttsnFilesystemStorageService.writePreferenceInternal:162` throws `MqttsnSecurityException("unable to write settings file", e)` for an IOException. Mis-typed exceptions confuse incident response: a disk-full error pages the security team. Throw `MqttsnException` and reserve `MqttsnSecurityException` for actual security events.

### 3.4 `TransientObjectLocks` mutex map is fragile under load
`TransientObjectLocks.java:32–62`. Mutexes are returned by id but held only by `WeakHashMap` + a `WeakReference`. If the caller doesn't retain the returned `IMutex`, GC may evict the entry; a concurrent call for the same id then gets a *different* mutex, defeating mutual exclusion. The API works only if callers manually keep the reference alive for the full critical section — undocumented and easy to get wrong. Either document loudly or switch to `ConcurrentHashMap` + reference-counted release.

---

## 4. Medium — SOLID

### 4.1 `IMqttsnRuntimeRegistry` is a 35-method service locator
`IMqttsnRuntimeRegistry.java:33–211` exposes `getCodec`, `getMessageQueue`, `getMessageHandler`, `getMessageStateService`, `getNetworkRegistry`, `getTopicRegistry`, `getSubscriptionRegistry`, `getContextFactory`, `getSessionRegistry`, `getQueueProcessor`, `getQueueProcessorStateCheckService`, `getMessageRegistry`, `getWillRegistry`, `getAuthenticationService`, `getAuthorizationService`, `getSecurityService`, `getTopicModifier`, `getMetrics`, `getStorageService`, `getClientIdFactory`, `getDeadLetterQueue`, plus generic `withService`/`getService<T>`/`getOptionalService<T>`/`getServices`. Every consumer carries a dependency on every service.

Practical impact:
- A new SPI requires adding a getter on the registry and an impl on the abstract class — touches every test that mocks the registry.
- Tests can't isolate units; you need a registry whose every getter returns *something*.

Move to typed constructor injection (the `IMqttsnService` lifecycle already takes a registry, so this is a small step). Keep the registry for cross-service discovery but stop reaching for it from everywhere.

### 4.2 `IMqttsnMessageStateService` mixes lifecycle, queueing, send and persistence
158-line interface in `IMqttsnMessageStateService.java`. Split into:
- `IMqttsnSendQueue` (sendMessage, sendPublishMessage, scheduleFlush, unscheduleFlush, canSend),
- `IMqttsnInflightStore` (countInflight, removeInflight, clearInflight),
- `IMqttsnReceiveSink` (notifyMessageReceived, waitForCompletion, getMessageLast*FromContext).

Each is a smaller test target.

### 4.3 `IMqttsnSessionRegistry` has 16 `modify*` methods
`IMqttsnSessionRegistry.java:50–57`: `modifyClientState`, `modifyLastSeen`, `modifyKeepAlive`, `modifySessionExpiryInterval`, `modifyMaxPacketSize`, `modifyProtocolVersion` — these are setters on the session object dressed up as registry calls so that someone, somewhere, can log them centrally. The session object itself is the right place. The registry should expose `getSession` / `createSession` / `delete` and leave field updates to the model.

### 4.4 `AbstractMqttsnService` is raw-typed
`AbstractMqttsnService.java:29`: `implements IMqttsnService` — drops the `<T extends IMqttsnRuntimeRegistry>` generic. As a result, the entire subclass tree loses the typed `start(T runtime)` contract and casts back to the concrete registry inside subclasses.

### 4.5 `MqttsnService` is a constant-bearing annotation
`MqttsnService.java:31–38`:
```java
public @interface MqttsnService {
    int FIRST = 100, ANY = 50, LAST = 10, RESERVED = 0;
    int order() default ANY;
}
```
Constants on an annotation type are valid but unusual. They're effectively a "constant interface" by another name. Extract to an enum (`ServiceOrder.FIRST/ANY/LAST/RESERVED`) and have `order()` return that enum.

### 4.6 `RuntimeConfig` is a constant interface
Same anti-pattern as `MqttsnConstants` in the codec module. Convert to a final class.

### 4.7 `MqttsnSessionRegistry.clear(ISession)` swallows the checked exception
Line 142–148: the no-flag overload re-throws as `MqttsnRuntimeException`, while the flagged overload (line 118) throws `MqttsnException` declared. Pick one error model.

---

## 5. Medium — null-safety / API hygiene

### 5.1 `NetworkAddressRegistry.findForClientId` returns null for Optional
See §2.10 — repeating in the API-hygiene bucket because every Optional-returning method should be audited for the same pattern.

### 5.2 `getMqttsnContext` throws `MqttsnRuntimeException` for a missing key
`NetworkAddressRegistry.java:79`: returns or throws. Sibling `getContext(NetworkAddress)` (line 60) returns null for the same condition. Choose one and apply uniformly — current callers have to handle both.

### 5.3 `IMqttsnTransport` returns `Future<IPacketTXRXJob>` but throws checked from one overload, not the other
`IMqttsnTransport.java:43–47`: `writeToTransport` throws `MqttsnException`; `writeToTransportWithCallback` does not. Either both throw or neither does — divergence forces every caller to maintain two paths.

### 5.4 `MqttsnPublishReceivedListener` (and siblings) are uncontracted SAM types
Not reviewed in detail — but listeners that swallow exceptions silently are a frequent issue. Confirm each listener invocation site catches `Throwable`-from-listener and isolates faults to a single listener instead of breaking the broadcast loop.

### 5.5 `MqttsnInMemoryMessageStateService.getInflightMessages` returns the live map
Line 101–116: returns `pair.getLeft()` / `pair.getRight()` directly. The `Map` returned is a `synchronizedMap`, so individual operations are safe, but **iteration** by callers requires external sync the call site can't see. Either:
- return `Collections.unmodifiableMap(new HashMap<>(map))` (defensive copy), or
- expose only the operations callers actually need (`putInflight`, `removeInflight`).

### 5.6 `MqttsnSessionRegistry.hasSession` unsynchronized vs synchronized neighbours
`hasSession` (line 180) doesn't sync; `countTotalSessions` and `countSessions` do. Pick one. (`ConcurrentHashMap.containsKey` is already thread-safe, so removing the syncs is the right answer.)

---

## 6. Low — style / micro

- `NetworkAddressRegistry.java:54–56`: raw types on `new ConcurrentHashMap(initialCapacity)`. Use `<NetworkAddress, INetworkContext>` etc.
- `MqttsnUdpTransport.java:135`: `volatile boolean stopping = false;` declared *inside* the class body rather than with siblings at the top — moves with code edits and is easy to miss.
- `AbstractMqttsnMessageStateService.java:632`: `++startAt % …` (see §2.9).
- `AbstractMqttsnRuntime.handleConnectionLost`: only logs at `debug` — connection loss is operational; `info` is more useful.
- `IMqttsnRuntimeRegistry` exposes `withService`, `withServiceReplaceIfExists`, `getService`, `getServices(Class)`, `getServices()`, `getOptionalService` — five flavours of "find me a service". Two would suffice (`get(Class)` + `getAll(Class)`).
- `MqttsnInMemoryMessageStateService` synchronises on `this` inside `getInflightMessages` (line 104). Use a private final lock object.

---

## Recommended sequencing of fixes

1. **The two §1 bugs** (`stop()` inverted condition, clientId precedence). Both are one-line fixes with tests.
2. **`flushOperations` and the `inflightMessages` iteration** (§1.3, §1.4) — these are the live drainage zone for the deadlock that commit `5f2e934` partially patched.
3. **`Security.verifyHMac` constant-time comparison** (§3.1) — replace `Arrays.equals` with `MessageDigest.isEqual`. Tiny diff, removes a class of side-channel.
4. **`reapInflight` silent drop and `randomBytes` PRNG audit** (§2.8, §3.2).
5. **Listener-list concurrency** (§1.6) — swap to `CopyOnWriteArrayList`; touches every `add*Listener` call once.
6. **SessionRegistry: `computeIfAbsent`** (§1.5) — one-line refactor that removes a real race.
7. **Then** consider the SOLID work (§4) as a planned refactor. The `IMqttsnRuntimeRegistry` god interface is the long-term tax driver but isn't urgent.
