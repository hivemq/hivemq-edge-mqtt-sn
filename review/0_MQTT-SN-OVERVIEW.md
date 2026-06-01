# MQTT-SN: A Brief Overview for MQTT Experts

MQTT-SN (Sensor Networks) is a variant of MQTT designed for constrained devices and non-TCP networks. Think of it as MQTT adapted for environments where TCP is too heavy or unavailable — typically ZigBee, 6LoWPAN, Bluetooth, or bare UDP over 802.15.4.

## Transport & Framing

The biggest mental shift: **MQTT-SN does not run over TCP.** It's a datagram protocol, almost always over UDP (but transport-agnostic by spec).

- No streaming, no TLS-as-you-know-it. Each PDU is a self-contained datagram.
- Messages are length-prefixed (1 or 3 bytes) followed by a 1-byte MsgType — far more compact than MQTT's variable-length remaining-length encoding.
- No persistent transport-level connection means session state on the broker side has to survive client silence in a way the transport can't help with.

## Topology: The Gateway

Clients don't usually speak to a broker directly. They talk to a **Gateway**, which translates MQTT-SN ↔ MQTT and holds the actual TCP connection to the broker.

Three gateway flavors:
- **Transparent**: one MQTT connection per MQTT-SN client (1:1 mapping).
- **Aggregating**: a single MQTT connection multiplexes many MQTT-SN clients (better for thousands of sensors).
- **Forwarder**: dumb relay sitting between the radio side and a real gateway (encapsulates the original PDU).

HiveMQ Edge's MQTT-SN extension is typically an aggregating/transparent gateway.

## Topic IDs — The Defining Feature

Sending the string `home/livingroom/temperature` in every PUBLISH is unaffordable when your MTU is ~127 bytes. So MQTT-SN replaces topic strings with **2-byte topic IDs**.

Three flavors:
1. **Registered topic IDs** — client sends `REGISTER` with the string, gateway responds with `REGACK` containing a numeric ID. The client uses that ID in subsequent PUBLISHes. Mappings live for the session.
2. **Pre-defined topic IDs** — agreed out-of-band between client and gateway, no REGISTER needed. Hardcoded.
3. **Short topic names** — exactly 2 ASCII chars, used directly without registration.

The gateway must also REGISTER topics to *subscribing* clients before forwarding a PUBLISH on a topic the client doesn't yet know — common gotcha.

## CONNECT / Session Semantics

Familiar but with twists:
- `CleanSession` flag exists (like MQTT 3.1.1). No MQTT 5-style session expiry interval in the base spec.
- Will is set up via a back-and-forth handshake (`WILLTOPICREQ` / `WILLMSGREQ`) rather than embedded in CONNECT — keeps CONNECT small.
- No username/password fields in CONNECT in v1.2. Authentication is essentially absent at the protocol level (a real operational issue — usually handled at gateway).

## Sleeping Clients — The Killer Feature

This has no MQTT equivalent. A client can `DISCONNECT` with a duration value, signaling "I'm sleeping for N seconds; buffer my messages." The gateway moves it to **asleep** state and queues incoming PUBLISHes.

When the client wakes:
- Sends `PINGREQ` (with ClientId, unlike MQTT's empty PINGREQ).
- Gateway flushes queued messages.
- Gateway sends `PINGRESP` to signal "you're caught up; you may sleep again."

State machine: ACTIVE → ASLEEP → AWAKE → ASLEEP → … → ACTIVE/DISCONNECTED.

## QoS

QoS 0, 1, 2 mirror MQTT semantics. Plus a unique one:

- **QoS -1 (or 3)** — fire-and-forget *without* a session. Client sends a PUBLISH with no prior CONNECT. Gateway must be able to handle "anonymous" publishes with predefined topic IDs. Useful for ultra-cheap one-shot sensors.

## SUBSCRIBE / Wildcards

You can subscribe by topic *name* (with wildcards `+` and `#`) or by topic ID (no wildcards, since IDs are numeric). The SUBACK returns the topic ID assigned to the (non-wildcard) subscription, so future PUBLISHes from the gateway to this client can use the ID.

For wildcard subscriptions, the gateway has to REGISTER each concrete matching topic to the client before delivering — extra round-trip but unavoidable.

## Discovery

Clients can broadcast `SEARCHGW` and gateways respond with `GWINFO` (also broadcast-able by other clients on behalf of the gateway). Useful on multicast-capable networks. No analog in MQTT.

## What's Missing vs. MQTT 5

- No properties / user properties
- No reason codes (just basic ReturnCodes: Accepted, Congestion, InvalidTopicId, NotSupported)
- No shared subscriptions
- No topic aliases (topic IDs *are* the alias mechanism)
- No flow control / receive maximum
- No enhanced auth
- No request/response semantics

## Practical Gotchas

- **Packet fragmentation**: keep PDUs under the link MTU; large PUBLISHes simply don't fit.
- **Topic ID lifecycle**: IDs are per-session for registered topics. After a clean reconnect, all REGISTERs must be redone. Get this wrong and you silently publish to topic ID 0 or get InvalidTopicId.
- **Gateway as single point of state**: client-side state is intentionally minimal; the gateway carries subscription state, queued messages for sleepers, will config, topic registry.
- **No TLS over UDP in v1.2**: DTLS is the usual answer but isn't standardized in the spec. v2.0 (newer, less deployed) addresses some of this.

## v2.0 (briefly)

Released 2020. Adds: auth, larger topic IDs, properties (closer to MQTT 5), better will handling, formalized return codes. Adoption is still spotty — most deployments are still v1.2.

---

If you're working on the HiveMQ Edge MQTT-SN gateway, the mental model is: **MQTT-SN clients are MQTT clients with a much weaker transport and an aggressive compression scheme for topic names, and the gateway is responsible for translating both the wire format and the session semantics into something the upstream MQTT broker understands.**
