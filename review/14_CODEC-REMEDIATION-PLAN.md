# `mqtt-sn-codec` — Remediation Plan

Codec findings from [3_CODEC-REVIEW.md](3_CODEC-REVIEW.md), grouped by severity. Ordered within each severity so earlier items either (a) close the most acute risk first or (b) unblock or simplify later work.

---

## Severity 1 — Critical

Wire-safety bugs reachable by hostile UDP input, silent data corruption, and the one unsafe defaults that can compromise the protection extension's keys.

1. **Length-validate hostile packets before any byte access.** `MqttsnSpecificationValidator.validatePacketLength` only checks `length >= 2` and does not require `>= 4` when `data[0] == 0x01` (extended-length form). Both `Mqttsn_v1_2_Codec.createInstance` and `Mqttsn_v2_0_Codec.createInstance` then read `data[3]` / `data[5]` for the version byte before the length check on line 184 / 145. Result: a hostile 2-byte datagram starting with `0x01` throws `ArrayIndexOutOfBoundsException` (unchecked) instead of the typed `MqttsnCodecException` callers handle. [Codec §1.1–1.2](3_CODEC-REVIEW.md)
2. **Remove the PUBLISH double-decode.** `msg.decode(data)` is called twice — once inside the `case` branch and once in the loop tail. Same bug in both v1.2 and v2.0 codecs. Wasted work plus a real risk if `decode` ever becomes non-idempotent. [Codec §1.3](3_CODEC-REVIEW.md)
3. **Restore the throw in `AbstractMqttsnMessageWithTopicData.getTopicName()` for `TOPIC_PREDEFINED`.** Today the `if (topicType == TOPIC_PREDEFINED) { }` branch is empty (commented `throw IllegalStateException` is the original intent). Callers asking for the topic name on a predefined-alias message get a `String` constructed from 2 binary bytes — garbage that silently flows through the system. [Codec §3.1](3_CODEC-REVIEW.md)
4. **Fix `MqttsnConnect.encode` UTF-8 sizing.** Buffer sized by `clientId.length()` (char count) but written from `clientId.getBytes(UTF-8)`. Any non-ASCII clientId either underfills the buffer or overflows with `ArrayIndexOutOfBoundsException` on the `System.arraycopy`. `MqttsnRegister.encode` shows the correct pattern (compute bytes once). [Codec §3.3](3_CODEC-REVIEW.md)
5. **Validate wire-supplied lengths in `MqttsnPublish_V2_0.decode` and `MqttsnProtection.decode`.** A hostile packet with `topicLength = 65535` calls `System.arraycopy` past the end; a packet with oversized cryptoMaterial / monotonic counter / auth tag makes `encapsulatedPacketLength` go negative → `NegativeArraySizeException`. Validate `topicLength <= remainingBytes` and `encapsulatedPacketLength >= 0` before allocation. [Codec §1.6–1.7](3_CODEC-REVIEW.md)
6. **Remove the truncating `(byte)` / `(short)` casts on length fields.** `MqttsnProtection.decode` line 149 casts `data.length` to `short` (sign-flip above 32767), and `unprotect` line 219 casts an `int` length to `byte` (wraps negative above 127, then used as array length). Use `int` throughout; add an explicit overflow check at the size boundary. [Codec §1.8](3_CODEC-REVIEW.md)
7. **Add bounds-checking helpers and route raw array reads through them.** `MqttsnWireUtils.readMessageType` / `readMessageLength` / `read16bit` etc. do raw indexed reads with no length checks. Every read path inherits the same hostile-input risk as items 1–5. Wrap reads in helpers that throw `MqttsnCodecException` on out-of-bounds. [Codec §1.4](3_CODEC-REVIEW.md)
8. **Fix `MqttsnWireUtils.readBuffer` ignoring the `off` parameter.** `Math.min(len, buf.length)` should be `Math.min(len, buf.length - off)` — otherwise `System.arraycopy` throws `ArrayIndexOutOfBoundsException` on legitimate inputs with a non-zero offset. [Codec §1.5](3_CODEC-REVIEW.md)

---

## Severity 2 — High

Security primitives in the protection types used by the AEAD/AUTH-only schemes, and one ergonomic protection-side failure mode.

1. **`MqttsnProtection.unprotect` should throw on auth failure, not return `null`.** Callers must remember to null-check; one missed conditional silently treats a forged packet as authentic. Throw a typed `MqttsnProtectionAuthenticationException` instead. [Codec §2.1](3_CODEC-REVIEW.md)
2. **Log auth-failure at WARN per key attempt and ERROR after all keys exhausted.** Today both are at DEBUG — a security event hidden as a debug detail. [Codec §2.2](3_CODEC-REVIEW.md)
3. **Fix the `"Authentication Tag is " + authenticationTag + " bytes"` exception message.** `authenticationTag` is `byte[]`; stringifies as `[B@1234abcd`. Use `authenticationTag.length`. [Codec §2.3](3_CODEC-REVIEW.md)
4. **`ProtectionKey` must defensive-copy the key on construction and on every getter.** Today the constructor stores the caller's array by reference and `getProtectionKey()` returns the same reference. Combined with no `clear()` / `destroy()` API, secret bytes live until GC and any caller can mutate the master key in place. Add defensive copies, a `clear()` that zeroes the array, and ideally promote to `javax.crypto.SecretKey`. [Codec §2.4](3_CODEC-REVIEW.md)
5. **Replace the unsalted SHA-256(key) "key hash" identifier in `ProtectionKey`.** For short pre-shared keys this is an offline brute-force target. Use an HKDF-derived or HMAC-based identifier, or an opaque ID negotiated out of band. [Codec §2.5](3_CODEC-REVIEW.md)
6. **Document the `IProtectionScheme.getCryptoMaterial(byte)` return-array ownership** (or make it return a defensive copy). Same exposure pattern as `ProtectionKey`. [Codec §2.6](3_CODEC-REVIEW.md)

---

## Severity 3 — Medium

Real null-safety / mutability / thread-safety hazards, plus the two SOLID smells (god interface, abandoned marker-interface refactor) that drive the protocol-version coupling found across every downstream module review.

1. **`AbstractMqttsnMessageWithTopicData.setTopicName(null)` NPE.** Ternary short-circuits on null and assigns `TOPIC_NORMAL`; the next line `if (topicName.length() == 1)` then NPEs on the same null. Either accept null (delete the topic) or reject with `IllegalArgumentException`. [Codec §3.2](3_CODEC-REVIEW.md)
2. **`MqttsnPublish.encode` / `toString` NPE when `data` is uninitialised.** `int length = data.length + 7` blows up if no `setData(...)` preceded it. Default to `EMPTY_BYTES` or guard. [Codec §3.5](3_CODEC-REVIEW.md)
3. **`MqttsnPublish` exposes mutable `byte[] data` directly.** Both `getData()` and `setData(...)` return/store the caller's array reference. Defensive-copy on both sides (or document the contract). Same pattern likely in other payload classes carrying `byte[]`. [Codec §3.4](3_CODEC-REVIEW.md)
4. **Document the thread-safety contract on `MqttsnCodecs` constants.** The codec singletons `MQTTSN_CODEC_VERSION_1_2` / `…_2_0` are exposed as shared statics; every caller in the codebase reaches the codec through them, so they MUST be thread-safe. Add `@ThreadSafe` (or document on the class) so the invariant survives future edits. [Codec §4.1](3_CODEC-REVIEW.md)
5. **Make message-object mutable fields visible across threads.** `AbstractMqttsnMessage.id` and `returnCode` are non-volatile but the gateway rewrites `id` when forwarding (per Javadoc) — the same instance is touched on multiple threads. Either `volatile`, or make messages immutable and return a rewritten copy from `withId(int)`. The latter is the SOLID-friendly answer (see item 8). [Codec §4.3](3_CODEC-REVIEW.md)
6. **Drop the DCL on `Mqttsn_v1_2_Codec.messageFactory` (and v2.0).** `Mqttsn_v1_2_MessageFactory.getInstance(strict)` is already a singleton. The double-checked locking is pointless overhead and noise; call `getInstance(strict)` directly. [Codec §4.2](3_CODEC-REVIEW.md)
7. **`AbstractMqttsnCodec` / `AbstractMqttsnMessage` constructor must not call overridable methods.** `AbstractMqttsnMessage` constructor does `messageType = getMessageType()` — works today only because every subclass returns a constant. Either drop the `final int` cache (it's redundant; `getMessageType()` could just be the constant) or pass the value as a constructor argument. [Codec §5.7](3_CODEC-REVIEW.md)
8. **Finish the marker-interface refactor — push field accessors onto `IMqttsnConnectPacket`, `IMqttsnPublishPacket`, `IMqttsnIdentificationPacket`.** These interfaces exist but are mostly empty; that's why every codec class is 300+ lines of `if (message instanceof X)` chains. After this lands, downstream consumers stop branching on `context.getProtocolVersion()` and stop casting to v1.2 types (the v1.2 ClassCastException family flagged in gateway/client/Paho/AWS reviews disappears). [Codec §5.1, §5.4](3_CODEC-REVIEW.md)
9. **Pick `MqttsnCodecException` checked-or-unchecked and align signatures.** Today it `extends RuntimeException` but every SPI method declares `throws MqttsnCodecException` — decorative and misleading. Either make it checked (probably the original intent), or drop the `throws` clauses everywhere. [Codec §6.1](3_CODEC-REVIEW.md)
10. **Convert `MqttsnConstants` from a constant interface to a `final class`** with a private constructor. The "constant interface" anti-pattern lets any class accidentally implement it and inherit the whole bag. [Codec §5.6](3_CODEC-REVIEW.md)

---

## Severity 4 — Low

Style and structure items. None of these are bugs in themselves; address opportunistically in PRs that already touch the same file. The two SOLID items (`IMqttsnCodec` god interface, the `v2.0 extends v1.2` inheritance) are tracked here for visibility but want their own initiative.

1. **Make `MqttsnWireUtils` and `MqttsnSpecificationValidator` `final` with private constructors.** Pure static utilities; today both are non-final and instantiable. [Codec §6.8](3_CODEC-REVIEW.md)
2. **Replace `isAssignableFrom` with `instanceof` in `AbstractMqttsnCodec.encode`.** Equivalent semantics, idiomatic, cheaper. [Codec §6.2](3_CODEC-REVIEW.md)
3. **Remove the dead `QoS == 3` branch from `AbstractMqttsnMessageWithFlagsField.writeFlags`.** `setQoS` rejects 3; the branch is unreachable. [Codec §6.3](3_CODEC-REVIEW.md)
4. **Replace the side-effect ternary in `MqttsnRegister.encode` line 52.** `(topicByteArr = topicName.getBytes(CHARSET)).length` — compute the byte array on its own line. [Codec §6.7](3_CODEC-REVIEW.md)
5. **Cache the small-vs-large header offset at the top of each `decode`** instead of paying for `readHeaderByteWithOffset` → `isLargeMessage(data)` on every byte read. Minor perf, and a too-short buffer (length 0) currently throws on every read. [Codec §6.9](3_CODEC-REVIEW.md)
6. **Make `MqttsnPublish.encode` use the running `idx` for the payload offset** instead of `msg.length - data.length`. Consistency with every other class. [Codec §6.5](3_CODEC-REVIEW.md)
7. **Replace the wildcard cross-package imports** (`import org.slj.mqtt.sn.wire.version1_2.payload.*`) with explicit imports. Hides dependencies and breaks go-to-symbol. [Codec §7](3_CODEC-REVIEW.md)
8. **Add the missing copyright headers to `MqttsnAuth`, `ProtectionKey`, `MqttsnProtection`, and the other v2.0 payloads that don't carry one.** [Codec §7](3_CODEC-REVIEW.md)
9. **Reformat tab/space mixing in `MqttsnProtection` and the protection payload classes.** Pick one and reformat. [Codec §7](3_CODEC-REVIEW.md)
10. **Decide on `Serializable` on `IMqttsnMessage`.** If the codec isn't the persistence boundary, drop the inheritance; otherwise add `serialVersionUID` to every concrete message class (currently only `MqttsnProtection` has one). [Codec §6.6](3_CODEC-REVIEW.md)
11. **Move `IMqttsnCodec.print` to a separate `IMqttsnCodecDebug` interface.** Debug surface on a production SPI. [Codec §7](3_CODEC-REVIEW.md)
12. **Align the `Mqttsn_v1_2_Codec.getQoS` exception type.** Throws `MqttsnCodecException` for programmer mistakes (caller passed a non-flagged message). `IllegalArgumentException` would fit better. [Codec §7](3_CODEC-REVIEW.md)
13. **Reconsider `isActiveMessage` semantics** — currently "active = not PINGREQ/PINGRESP/DISCONNECT" but ADVERTISE/SEARCHGW/GWINFO are also non-application messages. Document or expand. [Codec §6.4](3_CODEC-REVIEW.md)

### Larger SOLID initiatives (tracked under Sev 4 but want their own scope)

- **Split `IMqttsnCodec` (~25 methods) by concern.** Encoding/decoding, type predicates, field accessors, factory, validation, capability negotiation, and debug printing currently share one interface. Replaces the predicate methods with a visitor; pushes field accessors onto the marker interfaces from Sev 3 item 8. [Codec §5.1](3_CODEC-REVIEW.md)
- **Re-think `Mqttsn_v2_0_Codec extends Mqttsn_v1_2_Codec`.** v2.0 is-a v1.2 only because they share wire-format primitives; the fallback `default: msg = super.createInstance(data)` lets a v2.0 client accidentally send v1.2 messages. Replace with composition (a `MessageTypeRegistry` strategy keyed on `msgType`) so each protocol version is closed and a new one (v3) is additive. [Codec §5.2–5.3](3_CODEC-REVIEW.md)

---

## Notes on ordering

- **Sev 1 items 1, 2, 7, 8** are the foundation — every other read path in the codec depends on the hostile-input contract. Land these first.
- **Sev 1 items 3, 4** are isolated data-corruption bugs; can land in parallel with the bounds work.
- **Sev 1 items 5, 6** live in the protection payload — bundle them with the Sev 2 protection items (they touch the same files).
- **Sev 3 item 8 (finish the marker-interface refactor)** is the highest-leverage Sev 3 item: it doesn't fix a bug in the codec itself, but it removes the protocol-version branching in every consumer module (gateway / client / Paho / AWS / console message handlers). Schedule it early in the medium-priority work.
- **Sev 4 SOLID initiatives** are deliberately last — they re-shape the SPI and should follow the marker-interface refactor, not precede it.
