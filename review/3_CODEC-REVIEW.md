# `mqtt-sn-codec` — Code Review

Scope: every file under `mqtt-sn-codec/src/main/java`. Lens: SOLID, Java null-safety, thread-safety, and (for the protection types) basic crypto hygiene. Findings are roughly ordered by severity. File:line references are 1-based.

## TL;DR

The codec compiles and round-trips happy-path traffic, but it is **not hardened against hostile or malformed input** — exactly the threat model it lives in (UDP datagrams from the open network). The most important fixes:

- Several `createInstance` switch cases dereference `data[3]` / `data[5]` **before** length validation runs. Hostile shorter packets throw `ArrayIndexOutOfBoundsException` instead of `MqttsnCodecException`.
- `MqttsnPublish.createInstance` calls `msg.decode(data)` **twice** (once inside the case, once after the switch). Same in `Mqttsn_v2_0_Codec`. Wasted work plus a real risk if `decode` is not idempotent.
- `MqttsnConnect.encode` sizes the buffer using `clientId.length()` (char count) but writes `clientId.getBytes(UTF-8)` — silent overflow for any non-ASCII clientId.
- `AbstractMqttsnMessageWithTopicData.getTopicName()` has an **empty `if`** for `TOPIC_PREDEFINED` that was supposed to throw; today it returns garbage chars decoded from the topic-ID bytes.
- The Protection extension returns `null` on auth failure, logs failures at DEBUG, and stores secret key material by reference with no defensive copy or zeroization.

Everything else below is supporting detail.

---

## 1. Critical — input validation / wire safety

### 1.1 Length validation is too weak
`MqttsnSpecificationValidator.validatePacketLength` (lines 251–261) only checks `length >= 2` and `<= UNSIGNED_MAX_16`. It does **not**:

- Require length ≥ 4 when `data[0] == 0x01` (3-byte length prefix form).
- Verify the encoded length field matches `data.length`.

A 2-byte datagram beginning with `0x01` passes validation, then `MqttsnWireUtils.readMessageType(data)` reads `data[3]` → `ArrayIndexOutOfBoundsException` (unchecked, leaks out of the codec).

### 1.2 `createInstance` reads version byte before length check
`Mqttsn_v1_2_Codec.createInstance` (lines 171–186) and `Mqttsn_v2_0_Codec.createInstance` (lines 135–149) both read `data[3]` or `data[5]` to determine the protocol version *before* calling `validateLengthGreaterThanOrEquals(data, 6)` (or 12 for v2.0). Reorder: validate length first.

### 1.3 Double-decode bug
In both codecs, the `PUBLISH` case calls `msg.decode(data)` inside the case (`Mqttsn_v1_2_Codec.java:204`, `Mqttsn_v2_0_Codec.java:163`) and then the method's tail at `Mqttsn_v1_2_Codec.java:293` and `Mqttsn_v2_0_Codec.java:204` calls `msg.decode(data)` *again*. Delete the in-case calls.

### 1.4 Wire helpers throw the wrong exception
`MqttsnWireUtils.readMessageType` / `readMessageLength` / `read16bit` etc. do raw array indexing with no bounds checks. For hostile input they raise unchecked `ArrayIndexOutOfBoundsException` instead of the typed `MqttsnCodecException` that callers handle. Wrap reads in length-aware helpers (e.g. `readUInt8(data, idx)` that throws `MqttsnCodecException` if `idx >= data.length`).

### 1.5 `MqttsnWireUtils.readBuffer` ignores `off`
Line 60–65: `Math.min(len, buf.length)` should be `Math.min(len, buf.length - off)`. With `off > 0` and `len = buf.length`, the call falls into `System.arraycopy` and throws `ArrayIndexOutOfBoundsException`. Add a precondition `off + copyLength <= buf.length`.

### 1.6 V2.0 PUBLISH decoder trusts `topicLength` from the wire
`MqttsnPublish_V2_0.decode` (lines 174, 184, 202, 207) reads a 16-bit `topicLength` from the packet and immediately uses it to slice the buffer. A hostile packet with `topicLength = 65535` will throw `ArrayIndexOutOfBoundsException` via `System.arraycopy`. Validate `topicLength <= data.length - currentOffset - tailSize` first.

### 1.7 `MqttsnProtection.decode` can compute negative lengths
Line 173: `int encapsulatedPacketLength = data.length - (idx + authenticatedTagLength);` — no check for negativity. If a hostile packet declares oversized cryptoMaterial/monotonicCounter/tag, this goes negative → `NegativeArraySizeException` in `readBytesAdjusted` (line 174).

### 1.8 Silent narrowing casts to `byte` / `short`
- `MqttsnProtection.decode:149`: `protectionPacketLength = (short) data.length` — silent truncation/sign flip above 32767 (legal up to 65535).
- `MqttsnProtection.unprotect:219`: `byte associatedDataLength = (byte) (...)` — values > 127 wrap to negative, then used as array length → `NegativeArraySizeException`.
- `ProtectionKey.java:26`: `(short)protectionKey.length` — silent truncation for keys > 32767 bytes (not realistic for AEAD keys, but the cast is still wrong).

---

## 2. Critical — Protection / crypto hygiene

### 2.1 `unprotect` returns `null` on auth failure
`MqttsnProtection.unprotect` (lines 215, 245) returns `null` after every key fails. Caller code is responsible for noticing — but elsewhere in this codebase the convention is `MqttsnCodecException`. A missed null-check on the call site silently treats a forged packet as authentic. Throw a typed exception (e.g. `MqttsnProtectionAuthenticationException`) instead.

### 2.2 Auth-failure logging is too quiet
Lines 211, 241: `logger.debug("Authentication Tag invalid for key " + i)` — failed MAC verification is a security event, not a debug detail. Log at WARN (per-key attempt) and ERROR (all keys exhausted).

### 2.3 Exception message concatenates a `byte[]`
Line 180: `throw new MqttsnCodecException("Authentication Tag is " + authenticationTag + " bytes")` — `authenticationTag` is `byte[]`, so this stringifies as `[B@1234abcd`. Use `authenticationTag.length`.

### 2.4 `ProtectionKey` does not defensive-copy or zeroize key material
`ProtectionKey.java:25`: `this.protectionKey = protectionKey` stores the caller's array by reference. `ProtectionKey.java:30–33`: `getProtectionKey()` hands the same reference back. Combined with no `destroy()` / `clearKey()` API, secret bytes live for the GC lifetime and any caller can mutate the master key in place. Defensive-copy on construction, return a copy from the getter (or expose only the cryptographic operation), and provide a `Arrays.fill(protectionKey, (byte)0)` clearer.

### 2.5 `ProtectionKey` uses unsalted SHA-256 as a key identifier
Line 27: `protectionKeyHash = toHex(SHA256(protectionKey))`. For short pre-shared keys this is an offline brute-forceable target — anyone who exfiltrates the hash can dictionary-attack the key. Use HKDF-derived or HMAC-based identifiers, or rotate them as opaque IDs negotiated out-of-band.

### 2.6 `IProtectionScheme.getCryptoMaterial(byte cryptoMaterialLength)` returns `byte[]`
Same exposure pattern as 2.4 — interface should clarify ownership and ideally return a defensive copy.

---

## 3. High — null-safety

### 3.1 `AbstractMqttsnMessageWithTopicData.getTopicName()` has an empty guard
Lines 33–35:
```java
if (topicType == MqttsnConstants.TOPIC_PREDEFINED){
}
```
The commented-out line on line 37 (`throw new IllegalStateException(...)`) is the original intent. Today, callers asking for the topic name on a predefined-alias message get a `String` constructed from 2 binary bytes — garbage. Restore the throw (or change return type to `Optional<String>` / explicit handling).

### 3.2 `AbstractMqttsnMessageWithTopicData.setTopicName(null)` NPEs
Line 47: ternary short-circuits on null and chooses TOPIC_NORMAL. Line 48: `if(topicName.length() == 1)` dereferences the same null → NPE. Either accept `null` (delete the topic) or reject with `IllegalArgumentException`.

### 3.3 `MqttsnConnect.encode` sizing bug
Line 64: `int length = 6 + (clientId == null ? 0 : clientId.length());` — sizes the buffer by Java char count, but line 84 writes `clientId.getBytes(UTF-8)`. For any clientId containing multi-byte chars (or surrogates), the buffer is too small → `ArrayIndexOutOfBoundsException` on the `System.arraycopy`. Compare with the correct pattern in `MqttsnRegister.encode:54` which calls `getBytes` once and uses that length. Fix: precompute the byte array.

### 3.4 `MqttsnPublish` exposes mutable `data` directly
`MqttsnPublish.java:42, 46` — `getData()`/`setData()` return/store the caller's array. Defensive copies on both sides (or document the contract clearly).

### 3.5 `MqttsnPublish.encode` / `toString` NPE on uninitialised `data`
Line 67 `int length = data.length + 7` and line 99 `sb.append(data.length)` blow up if no `setData` call preceded them. Default to `EMPTY_BYTES` instead of `null`, or guard.

### 3.6 `MqttsnWireUtils.toBinary(byte... b)` puts null check inside loop condition
Line 41: `for (int i = 0; b != null && i < b.length; i++)` — cosmetic; better as an early `if (b == null) return "";`. (Minor.)

---

## 4. High — thread-safety

### 4.1 `MqttsnCodecs` exposes shared mutable codec singletons
`MqttsnCodecs.java:37–46` exposes `MQTTSN_CODEC_VERSION_1_2` as a static `IMqttsnCodec`. The Mqttsn_v1_2_Codec stores `messageFactory` as a mutable field (DCL pattern, see 4.2). Because every caller in the codebase reaches the codec through this shared interface constant, the codec **must** be thread-safe — and the wire decoders mostly are, because they're stateless apart from `messageFactory`. Add `@ThreadSafe` to the class or otherwise document the contract.

### 4.2 Double-checked locking is unnecessary and noisy
`Mqttsn_v1_2_Codec.createMessageFactory` (lines 298–306) uses DCL on a volatile field, but `Mqttsn_v1_2_MessageFactory.getInstance(strict)` is itself a singleton. The DCL costs an extra volatile read per call and adds branch noise; just call `getInstance(strict)` directly. (Same in `Mqttsn_v2_0_Codec`.)

### 4.3 Message objects are mutable values shared across threads
`AbstractMqttsnMessage.id` and `returnCode` are non-volatile mutable fields. The gateway is expected to rewrite `id` when forwarding (per the IMqttsnMessage javadoc) — so the same instance can be touched on multiple threads. Either:
- Make fields `volatile` (cheap; visibility only), or
- Make messages immutable and return a rewritten copy from `withId(int)`.

The latter is the SOLID-friendly answer (see §5).

### 4.4 `MessageDigest` is held as instance state in `ProtectionKey`
`ProtectionKey.java:13` — `MessageDigest` is *not* thread-safe and isn't reset after use. The field is only used in the constructor; demote to a local variable and drop the field.

---

## 5. Medium — SOLID compliance

### 5.1 SRP — `IMqttsnCodec` is a god interface
`IMqttsnCodec` (173 lines, ~25 methods) mixes:

- Encoding / decoding (`encode`, `decode`, `readMessageSize`)
- Type predicates (`isConnect`, `isPublish`, `isPuback`, `isPubRel`, `isPubRec`, `isDisconnect`, `isActiveMessage`)
- Field accessors (`getClientId`, `getKeepAlive`, `getDuration`, `getQoS`, `isRetainedPublish`, `isCleanSession`)
- Factory (`createMessageFactory`)
- Validation (`validate`)
- Capability negotiation (`supportsVersion`, `getProtocolVersion`, `getProtocolDescriptor`)
- Debugging (`print`)

This is why every codec class is 300+ lines of `if(message instanceof X)` chains. The intended design has marker interfaces (`IMqttsnConnectPacket`, `IMqttsnPublishPacket`, `IMqttsnIdentificationPacket`) — they exist (in the SPI package) but are almost entirely **empty** and unused. Either:

- Push field accessors onto those marker interfaces (`IMqttsnConnectPacket.getClientId()`, `…getDuration()`) and have the codec just return the cast; or
- Replace the predicate methods with a single visitor pattern.

Today, adding a new message type to v2.0 requires editing both the codec switch and every accessor. That's an Open/Closed violation.

### 5.2 OCP — adding a v3 codec means editing the v1 codec
`Mqttsn_v2_0_Codec extends Mqttsn_v1_2_Codec` and falls back to `super.createInstance(data)` for unknown message types. A future v3 codec would have to chain through the same inheritance, accumulating compatibility shims. Prefer composition (a `MessageTypeRegistry` strategy keyed on `msgType`) so each protocol version is closed and a new one is additive.

### 5.3 LSP — `Mqttsn_v2_0_Codec` is-a `Mqttsn_v1_2_Codec`?
Same point as 5.2 from the Liskov angle. A v2.0 codec falling back to v1.2 parsing for unknown types is fine *if* the spec says so, but `isPublish`, `isCleanSession`, etc. inherited from the v1.2 implementation are accidentally correct because v2.0 mostly piggybacks on the same class hierarchy. The relationship is "shares wire-format primitives", not "is-a".

### 5.4 ISP — marker interfaces are mostly empty
`IMqttsnConnectPacket`, `IMqttsnDisconnectPacket` are completely empty. They suggest a planned cleanup that never landed. Either remove them or finish the migration (see 5.1).

### 5.5 DIP — wire helpers are static
`MqttsnWireUtils` is a class of static methods that everything depends on directly. Not a problem in practice (these are pure functions), but it makes testing edge cases harder — e.g. you can't inject a stricter validator. Acceptable as-is; flag only if someone wants to swap in a generated parser.

### 5.6 Constants in an interface (anti-pattern)
`MqttsnConstants` is declared as an `interface`. Java's "constant interface" anti-pattern lets any class accidentally implement it and inherit the entire bag of constants. Convert to `public final class` with `private` constructor.

### 5.7 `AbstractMqttsnCodec` constructor calls overridable method via subclass field initialization
`AbstractMqttsnMessage` constructor (line 40): `messageType = getMessageType()` calls an `abstract` method from the constructor. Works today only because every subclass returns a constant — but it is a textbook anti-pattern (and the `final int messageType` cache is mostly redundant; `getMessageType()` could just be a constant). Either remove the cache or remove the abstract method and use a constructor argument.

---

## 6. Medium — code smells / micro-bugs

### 6.1 `MqttsnCodecException extends RuntimeException`, but method signatures declare `throws`
All `throws MqttsnCodecException` annotations in the SPI (and on the abstract codec) are decorative — callers aren't forced to handle it. Either make it checked (probably what was intended) or drop the throws clauses.

### 6.2 `AbstractMqttsnCodec.encode` reflection-tests via `isAssignableFrom`
Line 60–62: `if (!AbstractMqttsnMessage.class.isAssignableFrom(msg.getClass()))` — equivalent to `msg instanceof AbstractMqttsnMessage`, which is the idiomatic form and cheaper.

### 6.3 `AbstractMqttsnMessageWithFlagsField.writeFlags` checks `QoS == 3`
Line 117 checks the raw value `3` even though `setQoS` rejects it. Dead branch.

### 6.4 `Mqttsn_v1_2_Codec.isActiveMessage` is opinionated
Line 136–138: "active" = not PINGREQ/PINGRESP/DISCONNECT. Doesn't account for ADVERTISE, SEARCHGW, GWINFO (broadcast/discovery). May be intentional but worth a comment.

### 6.5 `MqttsnPublish.encode` uses awkward arithmetic to find payload offset
Line 91: `System.arraycopy(data, 0, msg, msg.length - data.length, data.length)`. Works, but every other class uses `idx`. Use `idx` for consistency.

### 6.6 Serializable without `serialVersionUID`
`IMqttsnMessage extends Serializable`. None of the concrete message classes declare `serialVersionUID` (except `MqttsnProtection`). Persisted messages will silently break on class evolution. Either add `serialVersionUID = 1L` everywhere or drop `Serializable` from the interface if the codec is not the persistence boundary.

### 6.7 Side-effect assignment inside ternary
`MqttsnRegister.encode:52` — `(topicByteArr = topicName.getBytes(CHARSET)).length` — clever but obscures the flow. Compute on its own line.

### 6.8 `MqttsnWireUtils` is non-final and instantiable
Add `final` and a `private` constructor. Same for `MqttsnSpecificationValidator`.

### 6.9 Repeated reading of length-prefix byte
`AbstractMqttsnMessage.readHeaderByteWithOffset` (line 196) calls `MqttsnWireUtils.isLargeMessage(data)` on every byte read of a decode pass — that's dozens of redundant reads of `data[0]` per packet. Compute the offset once at the top of `decode` and pass it down. Minor perf, but the pattern also bakes the "small vs large header" branch into every helper.

---

## 7. Low — style and consistency

- `MqttsnAuth`, `ProtectionKey`, `MqttsnProtection` and several v2.0 payloads are missing the standard copyright header that every other file has.
- Tab/space mixing inside `MqttsnProtection` and `mqtt-sn-protection` package files. Pick one and reformat.
- `IMqttsnCodec.print` is debug functionality on a production SPI — consider moving to a separate `IMqttsnCodecDebug` interface.
- Many files declare `import org.slj.mqtt.sn.wire.version1_2.payload.*` — wildcard imports across packages obscure dependencies and break IDE go-to-symbol.
- `Mqttsn_v1_2_Codec.getQoS` throws `MqttsnCodecException` rather than `IllegalArgumentException` for type errors that are programmer mistakes (caller passed a non-flagged message). Choose one convention.

---

## Recommended sequencing of fixes

1. **Bounds & double-decode** (§1.1–1.4, §1.6, §1.7) — small diffs, high payoff. Add a `Mqttsn1_2WireTests` case that feeds 2-byte hostile packets and asserts `MqttsnCodecException` (not unchecked) for every msgType.
2. **`MqttsnConnect` UTF-8 sizing bug** (§3.3) — single-line fix, but it's a wire bug that escapes only with non-ASCII clientIds.
3. **`getTopicName` empty guard** (§3.1) — silent data corruption today.
4. **Protection: throw on auth fail, log loudly, defensive-copy keys** (§2.1–2.5).
5. **Thread-safety doc + DCL cleanup** (§4.1–4.4) — small.
6. **Then** consider the SOLID refactor (§5) as a separate effort — high value but disruptive; do it when v2.0 stabilizes.
