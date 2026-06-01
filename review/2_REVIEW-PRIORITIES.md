# Review Priority List

Ordered by a mix of **blast radius**, **complexity**, and **recent bug activity** (the last 5 commits all touch gateway session/lifecycle code, which is the strongest signal we have about where defects live).

## Tier 1 — Critical Path (review first)

### 1. `mqtt-sn-gateway` — session & state machine
The hot zone. Recent commits (`bbc0246` connect race, `761f7b2` reproducer, `5f2e934` reaping deadlock, `57866b5` session expiry cap) all live here. Focus on:
- Connect / disconnect / reconnect flow and the locking around it
- ACTIVE → ASLEEP → AWAKE state transitions and queue flushing
- Session reaping / expiry handling
- Topic-ID registry lifecycle (per-session vs. predefined)
- Will handshake (`WILLTOPICREQ`/`WILLMSGREQ`)

### 2. `mqtt-sn-codec`
Foundation — a bug here corrupts every runtime. Smaller surface area, so this should be quick. Check:
- v1.2 and v2.0 encode/decode symmetry (round-trip tests)
- Length-prefix handling (1-byte vs. 3-byte boundary at 256 bytes)
- Topic-ID encoding for the three flavors (registered / predefined / short)
- Bounds checks on every field — UDP means hostile/malformed datagrams reach this layer directly

### 3. `mqtt-sn-core` — SPI, transport, registries
Defines the contracts that runtimes implement. Worth reviewing before any runtime so you know what's "supposed" to be pluggable vs. what's leaking. Focus on:
- `spi/` interfaces — are they coherent and free of leaks?
- `net/` UDP transport — buffer sizing (recent EIP alignment commit `a878c6c`), threading model
- `model/` session and message state objects — mutability, thread-safety
- Persistence abstractions — what survives restart, what doesn't

## Tier 2 — High Value

### 4. `mqtt-sn-gateway-connector-paho`
The default backend; most users will hit this. Look for:
- How MQTT-SN sessions map onto Paho MQTT sessions (1:1 transparent vs. aggregating)
- Reconnect / retry semantics when the upstream broker drops
- QoS translation (especially QoS-1 / fire-and-forget upward)
- Backpressure between the UDP frontend and the TCP backend

### 5. `mqtt-sn-protection` + `mqtt-sn-protection-runtimes`
Security extension — bugs here are CVEs, not just defects. Check:
- Key handling and rotation
- Replay protection / nonce handling
- BouncyCastle API usage (it's easy to misuse)
- How `protection-runtimes` wires it into client/gateway — is the protected path actually enforced, or can it be bypassed?

### 6. `mqtt-sn-client`
Reference client implementation. Lower blast radius than the gateway (less state), but bugs here will be reproduced by every downstream device adopter. Mirror the gateway state-machine review at a smaller scale.

## Tier 3 — Important but Lower Risk

### 7. `mqtt-sn-gateway-connector-aws-iotcore`
Same pattern as the Paho connector, but a niche backend with extra auth complexity (TLS + x.509). Review only if AWS IoT Core is a supported deployment target for HiveMQ Edge.

### 8. `mqtt-sn-gateway-console`
Operator UI + shipped assembly. Functionality is operational rather than protocol-critical. Check:
- Auth on the management surface
- Whether console actions can corrupt gateway state (forced disconnects, registry edits)
- Bundled-dependency hygiene (shade plugin output)

### 9. `mqtt-sn-cloud-client`
HTTP/JSON client to the upstream cloud service. Confirm whether it's actually used in the HiveMQ Edge deployment — if not, candidate for exclusion from the shipped jar.

## Tier 4 — Skim Only

### 10. `mqtt-sn-load-test`
Test tool, not production. Useful to *use* against the gateway during the Tier 1 review (it likely already reproduces some of the bugs you'll be hunting). Worth a structural skim only.

---

## Suggested Workflow

1. **Start with the recent commits** (`git log -p bbc0246 761f7b2 5f2e934 57866b5 a878c6c`) to understand the bug patterns the maintainers are currently fixing — those teach you the failure modes faster than reading code cold.
2. **Codec → Core → Gateway**, in that order, so you read each layer with knowledge of what it depends on.
3. **Then Paho connector + Protection** — these are the highest-leverage modules outside the foundation.
4. **Console / AWS / cloud-client** as a final sweep, focused on whether they're actually shipped and supported in HiveMQ Edge.
