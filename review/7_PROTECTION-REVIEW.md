# `mqtt-sn-protection` + `mqtt-sn-protection-runtimes` — Security Review

Scope: every file under `mqtt-sn-protection/src/main` and `mqtt-sn-protection-runtimes/src/main`. Lens: cryptographic correctness, key handling, replay protection, fail-closed behaviour, and the wiring that decides whether traffic is actually protected. **Bugs here are CVE-class.** File:line references are 1-based.

## TL;DR

This extension is **not yet safe to ship as a security feature**. The defects are not subtle — they are textbook cryptographic mistakes that nullify the guarantees the protection envelope is supposed to provide:

1. **No replay protection.** `MqttsnProtectionService.readVerified` decrypts, looks up the sender, verifies the tag, returns. The received monotonic counter is **never compared against any previously-seen value**. The wire carries a counter; the receiver throws it away.
2. **The short monotonic counter is allowed to wrap on the sender side.** On `Short.MAX_VALUE + 1` it resets to `Short.MIN_VALUE` (so after 65 536 messages it can repeat — that, by definition, isn't monotonic). With the receiver not checking either, replays are accepted indefinitely.
3. **AES-GCM nonce is deterministic from the associated-data.** `nonce = SHA-256(associatedData)[:12]`. If the same packet shape is ever sent twice under the same key — easy if monotonic counter is disabled (the default-case "no monotonic counter" branch in `MqttsnProtectionService.writeVerified` leaves it at 0) — the IV repeats. GCM IV reuse destroys **both** confidentiality and authenticity for the affected key.
4. **CMAC verification uses `Arrays.equals`** — timing-attack vulnerable, same family as the core review's `Security.verifyHMac` finding.
5. **`MqttsnProtectionOptions.toString()` prints the master key.** `MqttsnProtectionService.start` does `logger.debug("Starting protection service with {}", getProtectionOptions())` — so the entire AES/HMAC key ends up in the logs at DEBUG level, in clear text.
6. **Example/demo key files (`gateway1-hmac.key`, `client1-{hmac,aes128,aes192,aes256}.key`) are committed under `mqtt-sn-protection-runtimes/src/main/resources/`** — i.e. inside the production source tree, not test. They are bundled into the runtime jar. `ProtectionUtils.loadKey` resolves them by *classpath* lookup, so anyone shipping the example runtime ships these keys as resolvable defaults.
7. **The protection extension is opt-in.** A `withSecurityService(new MqttsnProtectionService())` line that's forgotten leaves protection silently disabled — there is no boot-time assertion that a "protection-required" deployment actually has protection wired. The receiver has no global "only accept Protection packets" mode.

If we cannot fix these before the next release, the protection feature should be marked **experimental** in user-facing docs and not enabled in any shipped configuration.

---

## 1. Critical — cryptographic correctness

### 1.1 No replay protection on the receive path
`MqttsnProtectionService.java:160–185` (`readVerified`):
```java
MqttsnProtection packet = (MqttsnProtection) getRegistry().getCodec().decode(data);
ProtectedSender sender = getRegistry()...lookupProtectedSender(...);
if (sender != null) {
    byte[] authenticatedPayload = packet.unprotect(sender.getProtectionKeys());
    if (authenticatedPayload != null) {
        return packet.getEncapsulatedPacket();
    }
    throw new MqttsnSecurityException("Invalid Authentication Tag!");
}
```
`packet.getMonotonicCounter()` is read in `MqttsnProtection.decode` and is never consulted afterwards. **The receiver does not maintain a "highest counter seen per sender" or sliding window.** An attacker who captures one valid protected packet can replay it indefinitely.

**Fix**: per `ProtectedSender`, store the highest counter accepted (per scheme/key if needed); reject any packet whose counter is `<=` the stored value, or apply a sliding-window scheme (RFC 6479-style) for out-of-order tolerance.

### 1.2 Short monotonic counter wraps to negative
`MqttsnProtectionService.java:195–212` (`nextMonotonicCounterValue`):
```java
int newValue = counter.incrementAndGet();
if (shortVersion && newValue >= (Short.MAX_VALUE + 1))
    newValue = Short.MIN_VALUE;
return newValue;
```
- Initial value is `Short.MIN_VALUE` / `Integer.MIN_VALUE`, so first emitted counter is `MIN_VALUE + 1`. Negative values reach the wire — receivers doing naive ordered comparison will get the sign wrong.
- On wrap, the counter resets to `Short.MIN_VALUE` and **re-emits the entire range**. By definition that isn't a monotonic counter any more, and it kills any future per-sender replay window (1.1).
- The `AtomicInteger` mutation is atomic, but the wrap check is *not*: two concurrent senders that both observe `MAX_VALUE+1` will both reset and re-emit `MIN_VALUE`. Use `getAndUpdate` with a lambda that wraps atomically *and* rejects (preferred) once the keyspace is exhausted.

### 1.3 AES-GCM nonce is a hash of the AAD — deterministic
`AbstractProtectionSchemeGcm.java:92–98`:
```java
private AEADParameters getAEADParameters(byte[] associatedData, byte[] key) {
    byte[] nonce = Arrays.copyOfRange(digest.digest(associatedData), 0, nonceLength);
    return new AEADParameters(new KeyParameter(key), nominalTagLengthInBits, nonce, associatedData);
}
```
GCM **MUST** have unique nonces per key. Here the nonce is uniquely determined by the AAD. If the AAD ever repeats with the same key — for example, when the sender uses the "no monotonic counter" configuration in `MqttsnProtectionService.writeVerified:137–147` (`default: // No monotonic counter` — leaves counter at 0, AAD has no per-message variation) — the nonce repeats. **GCM IV reuse leaks the XOR of plaintexts and lets the attacker forge arbitrary messages under the same key**. This is the worst failure mode for AES-GCM.

**Fix**: either
- mandate that monotonic counter is always present for AEAD schemes (reject configuration where it's off), and ensure the counter contributes uniquely to the AAD; or
- generate the nonce from `SecureRandom` directly (`12 bytes`) and prepend it to the protected packet — far safer.

### 1.4 CMAC verification is not constant-time
`AbstractProtectionSchemeCmac.java:62`:
```java
if (Arrays.equals(tag, tagToBeVerified)) {
    return authenticatedPayload;
}
```
Same finding as the core review's `Security.verifyHMac`. Replace with `MessageDigest.isEqual(tag, tagToBeVerified)`. (Note: the GCM scheme delegates tag verification to BouncyCastle's `doFinal`, which is constant-time internally — only CMAC has the timing problem.)

### 1.5 Encryption uses a single key for all peers
`MqttsnProtectionService.writeVerified:148–150` always uses `getProtectionOptions().getProtectionKey()` — one global key. The decrypt side (`readVerified` → `packet.unprotect(sender.getProtectionKeys())`) supports per-sender key lists. The asymmetry means:
- Gateway → A and Gateway → B are encrypted with the same key.
- Anyone who is allowed to talk to the gateway has the key.
- Therefore A can decrypt B's traffic, and vice versa.

This defeats the per-sender model. The send path needs the same per-peer key lookup the receive path has.

### 1.6 No per-key crypto-period or rotation hook
The `MqttsnProtectionOptions` carries one `protectionKey` field. There is no rotation API, no "current key id" on the wire (the protocol *does* carry one — `cryptoMaterialLength`), and no maintenance schedule. GCM has a hard limit of ~2³² messages per key; without rotation, long-running deployments silently approach unsafe territory.

### 1.7 `SecureRandom.getInstanceStrong()` is called per scheme construction
`AbstractProtectionScheme.java:60`. Combined with `AbstractProtectionScheme.getProtectionScheme` (`AbstractProtectionScheme.java:82` onward) being called per packet, every encode/decode allocates a fresh scheme **and** a fresh strong SecureRandom. On Linux, `getInstanceStrong()` resolves to `NativePRNGBlocking` which can block on `/dev/random` entropy. Under packet load you can wedge the whole connection on entropy starvation. Use a long-lived `SecureRandom` initialised once.

### 1.8 `getProtectionScheme(byte)` uses reflection per call
Same line 82 onward — for each packet, iterates declared `byte` fields of `AbstractProtectionScheme`, matches by value, then `getDeclaredConstructor(...).newInstance(...)` — reflection + per-call allocation in the hot path. Plus returns `null` if no field matches; callers in `MqttsnProtection.decode` will NPE on the null. Move to a static `Map<Byte, Supplier<IProtectionScheme>>` populated at register-time.

---

## 2. Critical — key handling

### 2.1 Example key files committed under `src/main/resources/`
```
mqtt-sn-protection-runtimes/src/main/resources/
    gateway1-hmac.key
    client1-aes128.key
    client1-aes192.key
    client1-aes256.key
    client1-hmac.key
```
These files are **inside the production source tree** of the runtimes module and end up bundled into the jar (`target/classes/` also has them). `ProtectionUtils.loadKey(clientId, keyName)` reads `/clientId-keyName.key` from the classpath, so any deployment that uses the example wiring without overriding the keys is using the keys that ship in this repo. **Anyone with read access to the repo, the artifact, or Maven Central can decrypt or forge traffic against such a deployment.**

**Fix**:
- Move the example resources to `src/test/resources/` so they are not packaged.
- Stop shipping `ProtectionExampleGatewayCli`/`...ClientCli` as `src/main` if they require these keys at runtime — they are examples, not production entry points.
- Document a key-provisioning workflow that does not rely on classpath lookup.

### 2.2 `MqttsnProtectionOptions.toString()` logs the key in cleartext
`MqttsnProtectionOptions.java:74`:
```java
sb.append(", protectionKey=").append(Arrays.toString(protectionKey));
```
And `MqttsnProtectionService.java:86`:
```java
logger.debug("Starting protection service with {}", getProtectionOptions());
```
DEBUG logs leak the master key. Any deployment that enables DEBUG (very common during incident response) snapshots the key to disk / log aggregation / SIEM. **Override `toString()` to mask the key** (`"protectionKey=<redacted, " + length + " bytes>"`).

### 2.3 `ProtectionKey` does not defensive-copy (cross-referenced from codec review §2.4)
Already flagged in `review/3_CODEC-REVIEW.md`. Constructor stores the caller's byte[] by reference, getter exposes the same reference. A misbehaving registry implementation can mutate the master key in place. Add defensive copies and a `destroy()` that zeroises.

### 2.4 `MqttsnProtectionOptions.getProtectionKey()` exposes the array
Line 63 returns the internal `byte[]` reference. Any caller can mutate the key. Defensive-copy on both sides (or wrap in a SecretKey object).

### 2.5 Hardcoded test keys committed in commented-out blocks
`MqttsnProtectionService.java:89–106`: ~16 lines of literal hex bytes that look like 32-byte AES keys, sitting inside `// ` comments. Even if never executed, secrets in source attract copy-paste into someone's config. **Remove**.

### 2.6 `ProtectionUtils.loadKey` resolves by classpath
`ProtectionUtils.java:10–18`. A classpath resource is the wrong place for a key — the resource resolution order depends on jar order and any jar can claim a name. A malicious jar dropped earlier on the classpath shadows the real key file with attacker-chosen bytes, and the service silently picks them up. Use a filesystem path with explicit access controls.

### 2.7 Sender lookup uses a truncated hash prefix
`InMemoryProtectedSenderRegistry.deriveSenderId:38–47` (`Security.hash(clientId.getBytes(), …)[:senderPrefixLength]`). Default `senderPrefixLength = 8` (64 bits). Two clientIds whose hashes collide on the first 64 bits map to the same sender record — both can verify each other's traffic with each other's keys. With 2³² clients the birthday bound says collisions are likely. For most deployments 64 bits is fine, but the default and the failure mode (silent impersonation) need to be documented; ideally the registry should also check `sender.getClientId().equals(actualClientId)` after lookup.

---

## 3. High — fail-closed wiring

### 3.1 Protection is opt-in and silent when missing
The runtime wires protection only if `withSecurityService(new MqttsnProtectionService())` is called (`ProtectionExampleGatewayCli.java:67`). If it is missing, the gateway accepts unprotected traffic with no warning. There is no "require-protection" mode on `IMqttsnSecurityService` or on `MqttsnOptions` that would force the decision at boot.

**Fix**: add `MqttsnOptions.requireProtection(boolean)` checked in transport/handler; reject packets whose `msgType != PROTECTION` when set.

### 3.2 `isSecurityEnvelope` is v2.0-only
`MqttsnProtectionService.java:187–192`:
```java
private boolean isSecurityEnvelope(byte[] data){
    if (getRegistry().getCodec().supportsVersion(MqttsnConstants.PROTOCOL_VERSION_2_0)) {
        return MqttsnConstants.PROTECTION == MqttsnWireUtils.readMessageType(data);
    }
    return false;
}
```
A gateway running both v1.2 and v2.0 codecs will skip the envelope check for any v1.2 message — i.e. an attacker can downgrade to v1.2 by sending a CONNECT with `protocolVersion=1`. The whole protection layer is bypassed for the resulting session. Document that protection requires v2.0-only deployments, or add explicit v1.2 rejection when protection is required.

### 3.3 `readVerified` falls through to `super.readVerified` for non-envelope traffic
Line 182–184: `else { return super.readVerified(networkContext, data); }`. The parent (`MqttsnSecurityService`) accepts unprotected bytes. So once 3.1/3.2 are satisfied, *any* packet that isn't a PROTECTION envelope still flows through. Add a hard "drop if protection required" guard here too.

### 3.4 `unprotect` returns `null` on auth failure (cross-referenced from codec review §2.1)
Codec-side issue already flagged; here the service does check the null and throws `MqttsnSecurityException` (`MqttsnProtectionService.java:178`). Good. But if a future change forgets the null-check, the failure becomes silent. Fix `unprotect` to throw instead.

---

## 4. High — concurrency / state

### 4.1 BouncyCastle `GCMBlockCipher` and `CMac` are instance fields
- `AbstractProtectionSchemeGcm.java:24`: `private GCMBlockCipher gcmBlockCipher = null;`
- `AbstractProtectionSchemeCmac.java:15`: `private CMac cmac = null;`

These are stateful (key, AAD buffer, internal counters). Reusing the same instance across two concurrent calls corrupts the state and produces wrong tags / wrong ciphertexts.

This is safe *only* because `AbstractProtectionScheme.getProtectionScheme(byte)` constructs a new scheme instance per call. If anyone ever caches a scheme (very tempting given the reflection cost — see §1.8), the cipher becomes a shared mutable field. **Belt-and-braces fix**: construct the cipher per `protect`/`unprotect` call, *inside* the method, on the local thread.

### 4.2 `MessageDigest` is an instance field in `AbstractAeadProtectionScheme`
`AbstractAeadProtectionScheme.java:12`: `protected MessageDigest digest;` used in `AbstractProtectionSchemeGcm.getAEADParameters`. `MessageDigest` is not thread-safe. Same caveat as 4.1 — currently safe by accident of per-call construction.

### 4.3 `MqttsnProtectionService.digest` is an instance field
`MqttsnProtectionService.java:37`. Used once in `initProtectedKeyHash`. Demote to a local variable.

### 4.4 `MqttsnProtectionService.nextMonotonicCounterValue` per-context state
The counter is stored per `IClientIdentifierContext` via `getContextObject`/`putContextObject`. If the context object is recreated (the gateway review flagged a connect path that overwrites the session bean), the counter resets to `MIN_VALUE` mid-stream — receivers that *did* implement replay detection (eventually) would reject the next packet from the same client as a regression.

### 4.5 `Security.addProvider(new BouncyCastleProvider())` called in every scheme constructor
`AbstractProtectionSchemeGcm.java:34`, `AbstractProtectionSchemeCmac.java:24`. Repeated calls are idempotent but allocate a `BouncyCastleProvider` object each time. Hoist to a static initialiser in `MqttsnProtectionAlgorithmInitializer`.

---

## 5. Medium — SOLID / API hygiene

### 5.1 Demo-marked production class
`MqttsnProtectionService.java:26`: `// @Davide - super simple bit of DEMO code to get the messages secured 2 ways`. The class is the production entrypoint for the security extension. Either promote the class out of "demo" status with proper hardening or move it under `src/test`.

### 5.2 ~50 lines of commented-out code in `MqttsnProtectionService`
Lines 67–127 and 213–256 — old `setProtectionKey` / `setAllowedClients` / `setProtectionFlags` / `getProtectionConfiguration` / `deriveSenderId` / `addAllowedClientId` blocks. Delete or move to git history; commented code obscures the real surface.

### 5.3 `MqttsnProtectionOptions` is a mutable pseudo-builder
The `with*` methods return `this` instead of producing an immutable result. After `start`, mutations are still legal. Cryptographic configuration should be immutable post-init; otherwise a runtime change can silently desynchronise sender/receiver.

### 5.4 `protectionSchemeClasses` is a non-final `HashMap`
`AbstractProtectionScheme.java:47`. Populated at static init by `register()` calls. Not thread-safe; if registration ever happens from concurrent threads (e.g. dynamic plugin loading), corruption. Use `ConcurrentHashMap` or an immutable map built once.

### 5.5 `MqttsnProtectionAlgorithmInitializer` is a public class with one static method
Could be a static initialiser block on `AbstractProtectionScheme` so that the algorithm registry is self-bootstrapping. Today the runtime must remember to call `initDefaults()` — which `MqttsnProtectionService.start` does, but any other consumer of the codec might not, and `getProtectionScheme` then NPEs.

### 5.6 `MqttsnProtectionService` reads options via `instanceof` cast
`getProtectionOptions:258–264` casts `MqttsnSecurityOptions` → `MqttsnProtectionOptions`. If the wrong options type is wired, the service throws `MqttsnSecurityException` at every call (every packet!). Validate once in `start()` and store typed.

---

## 6. Low — style / micro

- `MqttsnProtectionService.java:35`: `static String COUNTER_CONTEXT_KEY = "protectionCounter.key"` — should be `private static final`.
- `AbstractProtectionSchemeGcm.java:88, 129`: per-call DEBUG of `getMac()` hex — when DEBUG is on, the tag is in the log. Less sensitive than the key itself but still cryptographic material.
- `MqttsnProtectionService.java:163`: `logger.info("Protection service handling {} ingress bytes 0x{} for {}", ..., MqttsnWireUtils.toHex(data), …)` — INFO level for every inbound packet, with its full hex. Move to DEBUG and rate-limit.
- `MqttsnProtectionService.java:133`: same for egress, INFO level with full hex (which is the **plaintext** encapsulated packet — payloads, clientIds, credentials all clear in the logs).
- `MqttsnProtectionService.java:28`: open architecture question in a code comment ("Question - should the network address be the source of truth..."). Decisions about authorization scope should land before this ships.
- `ProtectionExampleClientCli` / `ProtectionExampleGatewayCli` should be `src/test` since they exist only to demonstrate wiring.

---

## Recommended sequencing of fixes

1. **§2.1 / §2.2 / §2.5 / §2.6 — stop leaking keys.** Move example `.key` files out of `src/main/resources`; mask the key in `toString()`; delete the commented test keys; replace classpath resolution with explicit filesystem paths. None of these are hard; all are required before the next public release.
2. **§1.3 — fix AES-GCM nonce derivation.** Either generate randomly via SecureRandom and transmit, or refuse to operate without a monotonic counter. As-is, the most powerful scheme on offer is unsafe to use.
3. **§1.1 + §1.2 — actual replay protection.** Maintain per-sender counter state; refuse repeats; reject (don't wrap) on counter exhaustion.
4. **§1.5 — per-peer encrypt key.** Mirror the per-sender key lookup the receive path already has.
5. **§3.1 / §3.2 / §3.3 — fail-closed wiring.** Add a "protection required" boot option that rejects unprotected packets and rejects v1.2 codecs.
6. **§1.4 — `MessageDigest.isEqual` for CMAC.** One-line fix.
7. **§1.7 — long-lived SecureRandom; §1.8 — replace reflection with a supplier map.** Performance, not correctness, but mandatory for any non-trivial deployment.
8. **Then** SOLID / lifecycle cleanup (§5).
