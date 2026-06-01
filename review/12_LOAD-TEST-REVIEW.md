# `mqtt-sn-load-test` — Structural Skim

Scope: `mqtt-sn-load-test/src/main/java`. Per `review/2_REVIEW-PRIORITIES.md` this is Tier 4 — a structural skim, not a full review. The module is a test/benchmark harness, not production code; the question is whether it's *useful for finding gateway bugs* (the reason it's still on the priority list) rather than "is it production-clean".

## TL;DR

Use this module for what it's good at: reproducing gateway-side race conditions and queue-saturation behaviour. A few small fixes would make it more reliable as a regression harness:

- `AbstractLoadTestRunner.start` computes the per-profile ramp pause as `(rampSeconds / numInstances) * 1000` — integer division. With `numInstances > rampSeconds`, the pause is 0 and every "ramped" client launches in a tight loop. The thundering herd makes most "ramp" tests meaningless.
- The `client` field in `MqttsnClientProfile` uses the same broken DCL pattern flagged in the client review (§1.2): `client` is not `volatile`. Tests usually run single-threaded per profile so this rarely manifests, but it's worth fixing to match the client-side change.
- `activeThreads` is a plain `ArrayList` mutated from the `ThreadFactory.newThread` callback — concurrent submissions risk `ConcurrentModificationException`.
- The watchdog reads `runningCount`, `completeCount`, `errorCount`, `latch` from a different thread than the writers; the counters are `AtomicInteger` (good) but `latch` and `start` are non-volatile.

None of these are bugs in the gateway under test — they are bugs in the *test harness*. They reduce the harness's ability to reproduce gateway race conditions cleanly.

---

## 1. What the harness already exercises (worth keeping)

- **`AbstractLoadTestRunner`** — per-profile thread runner with watchdog and latch-based completion tracking. Useful for measuring "how many of N clients made it through" against the gateway.
- **`ThreadPerProfileLoadTestRunner` / `ThreadPoolLoadTestRunner`** — two contention models. The thread-per-profile variant is what reproduces fan-in races on the gateway side; the pool variant is what stresses the gateway's request queue.
- **`MqttsnClientProfile`** — wires a real `MqttsnClient` against the gateway via UDP. So load-test runs are end-to-end through `mqtt-sn-client → mqtt-sn-core (UDP) → gateway`, which is exactly the surface where the gateway and core reviews flagged the most defects.
- **`ConnectPublishProfile`, `ConnectSubscribeUnsubscribeLoopProfile`, `ConnectSubscribeWaitProfile`** — pre-baked workloads for the obvious patterns (publish flood, subscribe churn, subscribe-and-wait). Most of the recent gateway commits in the git history would benefit from regression coverage here.

---

## 2. Small fixes that would make it a better regression harness

### 2.1 Integer-division ramp
`AbstractLoadTestRunner.java:87`:
```java
int perProfilePause = (rampSeconds / numInstances) * 1000;
```
For `numInstances = 100, rampSeconds = 60`, this is `(60/100)*1000 = 0`. The subsequent `Thread.sleep(ThreadLocalRandom.current().nextInt(1, Math.max(perProfilePause, 10)))` then samples in `[1, 10)` ms — *not* the intended ramp.

**Fix**: `long perProfilePauseMs = (rampSeconds * 1000L) / Math.max(1, numInstances);`.

### 2.2 `activeThreads` is unsynchronised
`AbstractLoadTestRunner.java:51`: `final List<Thread> activeThreads = new ArrayList<>();` — mutated from `factory.newThread` (line 65). If the runner uses an `ExecutorService` whose `submit` triggers concurrent `newThread` calls (the pool runner does), `add` races. Use `Collections.synchronizedList` or a `CopyOnWriteArrayList`.

### 2.3 `MqttsnClientProfile.createOrGetClient` broken DCL
Line 67–93: `client` is non-`volatile`, DCL with `synchronized(this)`. Same bug class as the client review §1.2. Single-threaded per profile so it doesn't manifest today; mirror the fix to keep both code-bases honest.

### 2.4 Watchdog sees non-volatile `latch` / `start`
`AbstractLoadTestRunner.java:49, 50`: `private Long start; private CountDownLatch latch;` — written from `start()`, read from the watchdog thread. The watchdog thread is started *inside* `start()`, but visibility of `latch`/`start` after the assignment is not guaranteed without `volatile` (or a synchronisation edge between the writer and the watchdog's first read). In practice the JIT is generous here, but a stricter watchdog wants `volatile` or `final` (assignment during construction).

### 2.5 `interrupt.wait(5000)` without holding a synchronizer
`AbstractLoadTestRunner.java:120–125`:
```java
synchronized (interrupt) {
    interrupt.wait(5000);
    ...
}
```
OK, the `synchronized(interrupt)` is the monitor. But `interrupt.set(true)` on shutdown won't wake the wait — `AtomicBoolean.set` does not notify the monitor. To shut the watchdog down cleanly, the shutdown path must do `synchronized(interrupt) { interrupt.set(true); interrupt.notifyAll(); }`.

### 2.6 Subclassing `Number` as a sleep duration
`AbstractLoadTestRunner.java:94`:
```java
Thread.sleep(ThreadLocalRandom.current().nextInt(1, Math.max(perProfilePause, 10)));
```
`ThreadLocalRandom.nextInt(origin, bound)` requires `origin < bound`. With §2.1 fixed, `perProfilePause` could legitimately be `< 10`, so `Math.max(perProfilePause, 10)` saves us — but the original intent ("pause between 1 and perProfilePause") is lost. Decide which.

---

## 3. What's *not* here (and probably should be, for the gateway review's findings)

The gateway / Paho reviews surfaced several behaviours that are hard to spot without traffic:

- **Sleeping-client wake-up under message backlog** (gateway review §1.6 — multiple-ping while AWAKE → `clearInflight`). No profile exercises this.
- **Connection-lost recovery** (gateway §2.1 — broker drops never reconnect). No profile here can simulate broker drop; would need a connector-side trigger.
- **Reaping deadlock under reconnect storm** (core review §1.3 / §1.4 — `flushOperations` lock discipline). The connect/subscribe/unsubscribe loop comes close; a "connect-disconnect storm" profile would be more directly relevant.
- **Queue-full silent drops** (gateway §2.2–§2.4) — the `MaxMessagesInQueue=10000` setting in `MqttsnClientProfile.createOrGetClient` is well above what the gateway's broker-side queue can survive; a profile that intentionally over-fills the broker-side queue would surface those drops.

If a future PR adds:
- `BrokerDisconnectProfile` (drop the broker mid-flow),
- `ConnectStormProfile` (N concurrent connect/disconnect over the same network address),
- `BackpressureProfile` (publish faster than the broker can ack),

…the harness becomes much more useful for catching the bug classes the rest of the review series flagged.

---

## 4. Style / micro

- `AbstractLoadTestRunner.java:97`: catches `Exception` inside the ramp loop and wraps as `LoadTestException`. The InterruptedException case loses interrupt status.
- `ProfileRunner.run` does `runningCount.incrementAndGet()` then a try/finally that decrements — fine, but the test's own logs and the watchdog's "currently running" metric depend on this never throwing before the increment.
- `Numbers.percent(runningCount.get(), latch.getCount())` (line 124) divides by `latch.getCount()`, which may be 0 once everything is done. Division-by-zero log noise at the end of the run.
- `TestHelper` not skimmed in detail; if it has hard-coded gateway addresses, document them in the load-test README.

---

## Recommendation

Treat this module as a useful tool that needs polishing, not as a source of bugs to fix urgently:

1. Apply the four §2 fixes (ramp arithmetic, `activeThreads` sync, `client` DCL, watchdog `notifyAll`).
2. Add the three §3 profiles (`BrokerDisconnectProfile`, `ConnectStormProfile`, `BackpressureProfile`) — these become regression tests for the most-flagged gateway behaviours.
3. Run the load-test profiles against any branch that touches `MqttsnGatewaySessionService.connect`, `MqttsnAggregatingGateway`, or the connector layers before merging.

No production-blocking findings here.
