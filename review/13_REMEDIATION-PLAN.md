# Remediation Plan

Issues from reviews 3–12 grouped by severity. Within each severity, items are ordered so that earlier fixes either (a) close the most acute risk first or (b) unblock or simplify later fixes. Each item links back to the review document where it's described in full.

---

## Severity 1 — Critical (must fix; unsafe to ship as-is)

Defaults that compromise out-of-the-box security, cryptographic defects, and bugs that can lose authentication or admit replays.

1. **Console: turn off by default; bind loopback; require a password at first boot.** Default `admin`/`password` on `0.0.0.0:8080` is the single most acute exposure. [Console §1.1](10_CONSOLE-REVIEW.md)
2. **Console: require auth at the framework layer, not per-handler.** Today only `*.html` requests are authenticated; every AJAX/JSON endpoint is open, including the one that adds credentials to the gateway's allowed-clients list. [Console §1.2](10_CONSOLE-REVIEW.md)
3. **Protection: move example `.key` files out of `src/main/resources` and treat the existing keys as compromised.** They ship inside the runtime jar; `ProtectionUtils.loadKey` resolves them by classpath. [Protection §2.1](7_PROTECTION-REVIEW.md)
4. **Protection / Console: mask secrets in `toString()` and remove the DEBUG log line that prints options.** Today both `MqttsnProtectionOptions.toString` and `MqttsnConsoleOptions.toString` print the master key / admin password in cleartext. [Protection §2.2](7_PROTECTION-REVIEW.md), [Console §2.7](10_CONSOLE-REVIEW.md)
5. **Protection: implement replay detection on the receive path.** Receiver decodes the monotonic counter and discards it. [Protection §1.1](7_PROTECTION-REVIEW.md)
6. **Protection: stop wrapping the short monotonic counter.** On `Short.MAX_VALUE + 1` the counter resets to `Short.MIN_VALUE`, so after 65 k messages it repeats — no longer monotonic. Reject (and require key rotation) instead. [Protection §1.2](7_PROTECTION-REVIEW.md)
7. **Protection: fix AES-GCM nonce derivation.** Today `nonce = SHA-256(AAD)[:12]` — deterministic. If the AAD ever repeats under the same key (easy when monotonic counter is disabled), GCM IV reuse destroys confidentiality and authenticity. Either generate the nonce randomly and transmit, or refuse configurations without a monotonic counter. [Protection §1.3](7_PROTECTION-REVIEW.md)
8. **Core: fix `MqttsnUdpTransport.stop()`.** The body's guard is `if(running && !stopping)` after `stopping = true` — always false. Sockets never close, threads never get interrupted. Causes leaked resources and hung shutdowns; almost certainly the root of several recent fix commits. [Core §1.1](4_CORE-REVIEW.md)
9. **Core: fix PINGREQ clientId-mismatch operator-precedence bug.** `clientId == null || "".equals(clientId.trim()) && msgType == PINGREQ` parses as `null || (empty && pingreq)`. A non-PINGREQ packet with a null clientId is accepted as the previous session's client. [Core §1.2](4_CORE-REVIEW.md)
10. **Console / Core / Protection: replace `Arrays.equals` / `equals` MAC and password comparisons with `MessageDigest.isEqual` (constant-time).** Same one-line fix in three places: console basic auth, `Security.verifyHMac`, CMAC `unprotect`. [Console §1.3](10_CONSOLE-REVIEW.md), [Core §3.1](4_CORE-REVIEW.md), [Protection §1.4](7_PROTECTION-REVIEW.md)
11. **Console: enforce HTTPS (or refuse non-loopback bind without TLS).** Until then, credentials and all admin traffic travel in cleartext. [Console §1.4](10_CONSOLE-REVIEW.md)

---

## Severity 2 — High (data loss, silent state corruption, or known reachable bugs)

Defects that don't break the security model outright but cause messages to disappear, sessions to desynchronise, or services to fail silently. The "silent SUCCESS" family makes any test that relies on the gateway's success codes unreliable.

1. **Paho connector: wire `deliveryComplete` so `publish` only returns SUCCESS on broker ack.** Today SUCCESS is returned as soon as Paho buffers the message. [Paho §1.1, §1.3](6_PAHO-CONNECTOR-REVIEW.md)
2. **Paho connector: let `messageArrived` exceptions propagate.** Swallowing them tells Paho the message was delivered, even when fan-out to MQTT-SN sessions failed. Combined with the gateway's expansion-handler swallow, this is the second wall a message must fail at silently. [Paho §1.2](6_PAHO-CONNECTOR-REVIEW.md)
3. **Gateway: null the `connection` field after closing it in `MqttsnAggregatingGateway.doWork`.** Today the field stays non-null after close; the reconnect branch is unreachable until the process restarts. One-line fix. [Gateway §2.1](5_GATEWAY-REVIEW.md)
4. **Gateway / Core: route every `MqttsnQueueAcceptException` swallow to the DLQ.** Three call sites (`MqttsnGatewayExpansionHandler.receiveToSessions`, `MqttsnAggregatingGateway.initPublisher` × 2, `AbstractMqttsnMessageStateService.reapInflight`). Consider also making `MqttsnQueueAcceptException` checked to force the pattern. [Gateway §2.2–2.4](5_GATEWAY-REVIEW.md), [Core §2.8](4_CORE-REVIEW.md)
5. **Gateway: fix `MqttsnGatewaySessionService.connect`.** `synchronized(context)` on a per-message `IMqttsnMessageContext` provides no mutual exclusion; the `finally` dereferences `result` after acknowledging it may be null. Lock on `context.getClientContext()`; move bookkeeping into the `try`. [Gateway §1.1](5_GATEWAY-REVIEW.md)
6. **Gateway: stop replacing live session beans in `connect()`.** Even when an active session exists, `createNewSession` overwrites it — in-flight operations on another thread keep references to the evicted bean. [Gateway §1.2](5_GATEWAY-REVIEW.md)
7. **Client: `publish()` reads stale `session` field before the local declaration when QoS=-1.** Fresh client → NPE. Reconnected client → wrong session. Move the QoS=-1 validation after `checkSession(...)`. [Client §1.1](8_CLIENT-REVIEW.md)
8. **Client: `session` field is non-volatile but used in DCL.** Either `volatile` or replace `discoverGatewaySession` DCL with `computeIfAbsent`. [Client §1.2](8_CLIENT-REVIEW.md)
9. **Gateway: verify or fix the hardcoded short-topic name `"ab"` in `markSessionLost` will-publish path.** Either deliberate scaffolding or a planted bug; the will-publish wire message advertises the wrong topic regardless. [Gateway §1.4](5_GATEWAY-REVIEW.md)
10. **Gateway: reject (don't silently PINGRESP) a PINGREQ whose clientId doesn't match the bound session.** Pairs with item 9 in Severity 1 — both must be fixed for the "wrong client" defence to be coherent. [Gateway §1.5](5_GATEWAY-REVIEW.md)
11. **Cloud: fix `checkResponse` NPE.** `if (response == null) throw new MqttsnCloudServiceException("…" + response.getRequestUrl() + "…")` dereferences the very value it just null-checked. [Cloud §1.1](11_CLOUD-CLIENT-REVIEW.md)
12. **Cloud: start the monitor thread correctly.** `running = true` is set *after* `cloudClientMonitor.start()` — the thread sees `false` and exits. The monitor never runs in production. [Cloud §1.2](11_CLOUD-CLIENT-REVIEW.md)
13. **Cloud: stop logging the request body at INFO and the response body at ERROR.** Request body carries PII (`AccountDetails`: name, email, MAC). Response body carries the bearer token. [Cloud §2.1, §2.2](11_CLOUD-CLIENT-REVIEW.md)
14. **Cloud: fix or remove the unused `MqttsnCloudAccount account` parameter on `authorizeCloudAccount`.** Method ignores its argument; either the API is misleading or the POST body is silently broken. [Cloud §1.3](11_CLOUD-CLIENT-REVIEW.md)
15. **Codec: validate packet length before reading version byte; remove double-decode of PUBLISH.** `createInstance` in both v1.2 and v2.0 codecs reads `data[3]`/`data[5]` before length validation, and decodes PUBLISH twice. [Codec §1.1–1.3](3_CODEC-REVIEW.md)
16. **Codec: fix `MqttsnConnect.encode` UTF-8 sizing.** Buffer sized by `clientId.length()` (chars) but written from `getBytes(UTF-8)` — silent overflow for non-ASCII clientIds. [Codec §3.3](3_CODEC-REVIEW.md)
17. **Codec: restore the throw in `AbstractMqttsnMessageWithTopicData.getTopicName()` for `TOPIC_PREDEFINED`.** Empty `if` today returns garbage characters decoded from binary topic-ID bytes. [Codec §3.1](3_CODEC-REVIEW.md)
18. **Codec: validate wire-supplied lengths in v2.0 PUBLISH and PROTECTION decoders.** A hostile packet with `topicLength = 65535` or an oversized tag length triggers `ArrayIndexOutOfBoundsException` / `NegativeArraySizeException` instead of `MqttsnCodecException`. [Codec §1.6, §1.7](3_CODEC-REVIEW.md)
19. **Protection: `unprotect` should throw on auth failure, not return `null`.** Callers must remember to null-check. Codec-side fix; the service already happens to check, but the silent-fail mode is one missed conditional away. [Codec §2.1](3_CODEC-REVIEW.md), [Protection §3.4](7_PROTECTION-REVIEW.md)

---

## Severity 3 — Medium (concurrency hardening, lifecycle, robustness)

Real bugs reachable under load or in degraded conditions, but unlikely to cause silent data loss in the steady state. The same patterns are spread across modules — fixing them together avoids the same bug-class returning in a future module.

1. **Core: replace `AbstractMqttsnMessageStateService.flushOperations` `HashMap` with `ConcurrentHashMap`.** Mixed-discipline locking against the four neighbouring `synchronizedMap` fields. Same area as recent deadlock fix. [Core §1.3](4_CORE-REVIEW.md)
2. **Core: snapshot `inflightMessages.keySet()` before iterating in `doWork`.** `clearInflightInternal` callbacks mutate the map; CME risk. [Core §1.4](4_CORE-REVIEW.md)
3. **Core: replace the `MqttsnSessionRegistry.getSession` DCL with `computeIfAbsent`.** DCL is incorrectly applied to a `ConcurrentHashMap`. [Core §1.5](4_CORE-REVIEW.md)
4. **Core: switch `AbstractMqttsnRuntime` listener lists to `CopyOnWriteArrayList`.** Plain `ArrayList`s traversed under `forEach`; visible asymmetry with `activeServices` (already `synchronizedList`). [Core §1.6](4_CORE-REVIEW.md)
5. **Gateway: adopt a per-client lock for connect/disconnect/sleep transitions.** `TransientObjectLocks` already exists in core; today `connect` and `disconnect` lock on different monitors. [Gateway §1.3, §3.1](5_GATEWAY-REVIEW.md)
6. **Gateway: simplify `MqttsnAggregatingGateway` queue to `BlockingQueue.take()`.** Today it's a `LinkedBlockingQueue` used with `poll` + a separate `wait/notify` monitor — worst of both. [Gateway §3.7](5_GATEWAY-REVIEW.md)
7. **Gateway: `int * 1000 * 1.5` keepAlive overflow; `timeout * 1000` advertise overflow.** Use `long`. Same pattern shows up in Paho/AWS/UDP — fix them together. [Gateway §3.2, §3.5](5_GATEWAY-REVIEW.md), [Paho §2.3](6_PAHO-CONNECTOR-REVIEW.md), [AWS §6](9_AWS-IOTCORE-CONNECTOR-REVIEW.md)
8. **Paho / AWS: snapshot `client` locally before use; private-final lock instead of `synchronized(this)`.** `connectionLost` mutates `client` without sync; readers do check-then-act on the volatile reference. [Paho §3.2](6_PAHO-CONNECTOR-REVIEW.md), [AWS §4.4](9_AWS-IOTCORE-CONNECTOR-REVIEW.md)
9. **Paho: decide reconnect ownership (Paho auto-reconnect *or* gateway-managed) and document it.** Today auto-reconnect is disabled and gateway-managed reconnect was broken (Severity 2 item 3). Both off was the worst case. [Paho §1.4](6_PAHO-CONNECTOR-REVIEW.md)
10. **Paho: change default subscribe QoS away from QoS 2.** For an aggregating gateway, subscribing the upstream at QoS 2 for every topic is expensive; the cause is a misleading "no message context" fall-through. [Paho §2.1](6_PAHO-CONNECTOR-REVIEW.md)
11. **AWS: change the default port from `3306` (MySQL) to `1883` and fix the `clientId` parameter that's ignored.** Wait, the port one is Paho's `CustomMqttBrokerConnector`. [Paho §2.7](6_PAHO-CONNECTOR-REVIEW.md), [AWS §5.2](9_AWS-IOTCORE-CONNECTOR-REVIEW.md)
12. **AWS: replace `System.out.println` error reporting in `AwsCertUtils` with logger + typed exceptions.** Six call sites all return `null` after stdout; the caller sees a confusing NPE. [AWS §1.1–1.3](9_AWS-IOTCORE-CONNECTOR-REVIEW.md)
13. **Console: fix the `awaitTermination(10000, SECONDS)` typo (2.78 h shutdown wait).** [Console §3.1](10_CONSOLE-REVIEW.md)
14. **Console: stop returning `e.getMessage()` in 500 bodies.** Information disclosure. [Console §2.1](10_CONSOLE-REVIEW.md)
15. **Console: tighten `sanitizePath` (only collapses `//` today) and `getFileExtension` (looses-match enables the `*.html` auth gate from being tricked).** [Console §2.3, §2.5](10_CONSOLE-REVIEW.md)
16. **Console: add CSRF protection to state-changing endpoints.** Pairs with Severity 1 item 2: once auth is enforced, CSRF becomes the next attack surface. [Console §1.6](10_CONSOLE-REVIEW.md)
17. **Client: handle spurious wakeup in `supervisedSleepWithWake`.** Author left a `// TODO`; a spurious wake currently ends the sleep window early. [Client §1.3](8_CLIENT-REVIEW.md)
18. **Client: make `setWillData` atomic (or document the partial-success window).** Two sequential `*UPD` round-trips with no rollback. Also lock on `functionMutex` like its siblings. [Client §1.4](8_CLIENT-REVIEW.md)
19. **Client: restore `Thread.currentThread().interrupt()` after catching `InterruptedException` in `discoverGatewaySession`.** [Client §2.3](8_CLIENT-REVIEW.md)
20. **Client: set DISCONNECTED state *after* the remote DISCONNECT is successfully sent.** Today the local state is flipped first; a send failure leaves local "disconnected" while gateway still thinks ACTIVE. [Client §2.7](8_CLIENT-REVIEW.md)
21. **AWS: pass the broker's retained flag through `onMessage` instead of hardcoding `false`.** [AWS §2.6](9_AWS-IOTCORE-CONNECTOR-REVIEW.md)
22. **AWS: support EC keys (or reject them with a clear message).** `PrivateKeyReader` is RSA-only today; AWS IoT's modern default is ECDSA. [AWS §3.2](9_AWS-IOTCORE-CONNECTOR-REVIEW.md)
23. **Cloud: stop blocking the constructor on `checkCloudStatus()`.** Network I/O during construction breaks tests and offline boots. [Cloud §4.1](11_CLOUD-CLIENT-REVIEW.md)
24. **Cloud: cap response size in `HttpClient.createResponse`.** Unbounded `ByteArrayOutputStream`. [Cloud §3.1](11_CLOUD-CLIENT-REVIEW.md)
25. **Cloud: refuse `http://` endpoints; ideally pin the server certificate.** Cleartext bearer tokens are an unforced loss. [Cloud §3.7](11_CLOUD-CLIENT-REVIEW.md)
26. **Codec: convert `MqttsnConstants` and `RuntimeConfig` from constant interfaces to final classes.** Anti-pattern; opens accidental implementation. [Codec §5.6](3_CODEC-REVIEW.md), [Core §4.6](4_CORE-REVIEW.md)
27. **Codec: decide whether `MqttsnCodecException` should be checked or unchecked, and align the SPI signatures.** Today `extends RuntimeException` but every signature declares `throws` — decoratively misleading. [Codec §6.1](3_CODEC-REVIEW.md)
28. **Codec: fix `MqttsnWireUtils.readBuffer` ignoring the `off` parameter.** One-line; can throw `ArrayIndexOutOfBoundsException` on legitimate inputs. [Codec §1.5](3_CODEC-REVIEW.md)
29. **Protection: hoist `SecureRandom.getInstanceStrong()` out of the per-call scheme constructor.** Under packet load, `getInstanceStrong()` can block on `/dev/random`. [Protection §1.7](7_PROTECTION-REVIEW.md)
30. **Protection: replace reflection-per-call with a `Map<Byte, Supplier<IProtectionScheme>>`.** `getProtectionScheme(byte)` iterates declared fields and `getDeclaredConstructor(...).newInstance(...)` on every encode/decode. [Protection §1.8](7_PROTECTION-REVIEW.md)
31. **Protection: encrypt with the recipient's key, not the global one.** Today `writeVerified` uses one global `protectionKey`; per-sender keys exist only on the receive side. Defeats per-sender confidentiality. [Protection §1.5](7_PROTECTION-REVIEW.md)
32. **Protection / Console fail-closed wiring.** Add a `requireProtection` boot flag that rejects unprotected packets and rejects v1.2 codec for protected sessions. Today the protection layer can be silently bypassed by sending v1.2 packets or by forgetting to wire the service. [Protection §3.1–3.3](7_PROTECTION-REVIEW.md)
33. **Load test: fix `(rampSeconds / numInstances) * 1000` integer division.** Makes most "ramp" tests behave as a thundering herd. [Load test §2.1](12_LOAD-TEST-REVIEW.md)

---

## Severity 4 — Low (SOLID / structural / hygiene)

Items that aren't bugs in themselves but are the long-term cost driver. Fix as opportunistic cleanups in PRs that already touch the area; track the big ones (god interface, marker-interface refactor) as separate initiatives.

1. **Propagate the generic on `AbstractMqttsnService`.** Raw-typed today (`implements IMqttsnService` instead of `<T extends IMqttsnRuntimeRegistry>`). Removes ~20 casts across gateway/console. [Core §4.4](4_CORE-REVIEW.md)
2. **Finish the marker-interface refactor in the codec.** `IMqttsnConnectPacket`, `IMqttsnDisconnectPacket`, `IMqttsnPublishPacket` are mostly empty. Adding typed accessors (`getClientId`, `getDuration`, `getQoS`) makes every consumer version-agnostic — fixes the v1.2 ClassCastException family in gateway/client/Paho/AWS at once. [Codec §5.1, §5.4](3_CODEC-REVIEW.md)
3. **Decompose `IMqttsnRuntimeRegistry`.** 35-method service locator that every consumer depends on entirely. Long road; the smaller win is to stop adding new methods to it. [Core §4.1](4_CORE-REVIEW.md)
4. **Split `MqttsnGatewaySessionService` (423 lines), `MqttsnClient` (819 lines), `MqttsnGatewayMessageHandler` (377 lines), `AbstractMqttsnMessageStateService` (894 lines).** God classes — flagged in every review of these files. [Gateway §4.2](5_GATEWAY-REVIEW.md), [Client §3.1](8_CLIENT-REVIEW.md), [Core §4.2](4_CORE-REVIEW.md)
5. **Replace the hand-rolled Cloud `HttpClient` with `java.net.http.HttpClient` (JDK 11+) or Apache HttpClient.** Removes three resource-leak findings (§3.3–3.5), gives proper timeouts, and is what the class's own Javadoc recommends. [Cloud §5.1](11_CLOUD-CLIENT-REVIEW.md)
6. **Replace the `com.sun.net.httpserver` console with Jetty/Undertow embedded.** Brings TLS, request-size limits, and a proper auth pipeline for free. Multi-week. [Console §4.1](10_CONSOLE-REVIEW.md)
7. **Extract a `PahoConnectorBase` shared by `CustomMqttBrokerConnector`, `ThingstreamConnector`, and (analogously) the AWS connector.** All three duplicate the connection-creation and `getConnectionString` boilerplate. [Paho §4.1](6_PAHO-CONNECTOR-REVIEW.md), [AWS §5.1](9_AWS-IOTCORE-CONNECTOR-REVIEW.md)
8. **Make result types (`ConnectResult`, `DisconnectResult`, etc.) immutable.** Today they're filled in by the session service after construction — bites in `MqttsnGatewaySessionService.connect` §1.1. [Gateway §4.4](5_GATEWAY-REVIEW.md)
9. **Replace `e.printStackTrace()` calls with the logger.** Spread across gateway, console, AWS. [Gateway §6](5_GATEWAY-REVIEW.md), [Console §2.2](10_CONSOLE-REVIEW.md)
10. **Move example/CLI code out of `src/main`.** `Example.java` (client), `ProtectionExampleGatewayCli`/`ClientCli` (protection-runtimes), `*InteractiveMain` classes — all ship in the runtime jar today. [Client §4.4](8_CLIENT-REVIEW.md), [Protection §5.1](7_PROTECTION-REVIEW.md)
11. **Drop `IMqttsnMessage extends Serializable`** (or add `serialVersionUID` to every concrete message). If the codec isn't the persistence boundary, the inheritance is just future debugging pain. [Codec §6.6](3_CODEC-REVIEW.md)
12. **Reduce per-packet `INFO`-level hex dumps in the Protection service** and the `info` advertise log in `MqttsnGatewayAdvertiseService`. Operational noise. [Protection §6](7_PROTECTION-REVIEW.md), [Gateway §3.5](5_GATEWAY-REVIEW.md)
13. **`KeyStorePasswordPair` cleanup**: `final` fields, `char[]` password, `destroy()`. [AWS §3.1](9_AWS-IOTCORE-CONNECTOR-REVIEW.md)
14. **Load test: add `BrokerDisconnectProfile`, `ConnectStormProfile`, `BackpressureProfile`.** Makes the load-test module a regression harness for the bug families flagged in the gateway and Paho reviews. [Load test §3](12_LOAD-TEST-REVIEW.md)
15. **General style:** remove commented-out code blocks (notably `MqttsnProtectionService` lines 89–126 of literal hex keys and lines 67–127 / 213–256 of dead methods); replace constant-interface anti-patterns; make `AwsCertUtils.KeyStorePasswordPair` fields private. Opportunistic. [Protection §2.5, §5.2](7_PROTECTION-REVIEW.md), [AWS §3.1](9_AWS-IOTCORE-CONNECTOR-REVIEW.md)

---

## Notes on ordering across severities

- **Sev 1 and Sev 2 are independent of each other** — work can proceed in parallel.
- **Sev 3 items 1–4 (core memory-model fixes)** should land before deeper Sev 2 work on the gateway, because the gateway session bugs (Sev 2 #5, #6) hit the registries those fixes harden.
- **Sev 4 #2 (marker-interface refactor)** unblocks several Sev 2 items that exist only because of the v1.2 ClassCastException family (Sev 2 #15, parts of #1). Once #2 lands, those callers can be cleaned up cheaply.
- **Sev 1 #11 (HTTPS)** is the only Sev 1 item that's genuinely a larger effort — everything else in Sev 1 is a small, contained change.
