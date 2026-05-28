# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

A dependency-free, pure-Java implementation of MQTT-SN (MQTT for Small Things) supporting both protocol **version 1.2** and **version 2.0**. It provides a client, an aggregating/transparent gateway, codecs, cloud connectors, and load-testing tools. This is HiveMQ's fork of `simon622/mqtt-sn` (the `upstream` remote), hosted at `hivemq/hivemq-edge-mqtt-sn`; the default branch is `master`.

Java 8 is the source/target level (`maven.compiler.source/target = 1.8`) even though a newer JDK may be installed locally — keep new code Java-8 compatible.

## Build & Test Commands

Maven multi-module build (no wrapper; use a system `mvn`). All commands run from the repo root unless noted.

```bash
mvn clean install                      # Full cascading build of all modules
mvn -pl mqtt-sn-codec -am install      # Build one module + its dependencies
mvn -f mqtt-sn-gateway clean install   # Build a single module by directory (as CI does)
mvn -pl mqtt-sn-core test              # Run all tests in one module
mvn -pl mqtt-sn-codec test -Dtest=Mqttsn2_0WireTests           # Run a single test class
mvn -pl mqtt-sn-codec test -Dtest=Mqttsn2_0WireTests#someMethod # Run a single test method
```

CI (`buildspec.yml`) builds only `mqtt-sn-codec`, `mqtt-sn-core`, then `mqtt-sn-gateway` in order on `openjdk8`. Tests use JUnit 4. Note `mqtt-sn-gateway` itself has no unit tests — protocol behavior is exercised by tests in `mqtt-sn-codec`, `mqtt-sn-core`, and `mqtt-sn-client` (e.g. `ClientConnectionTest`, `InflightMessageStateRaceConditionTest`).

### Running locally (interactive CLIs)

Shaded runnable jars are produced via `maven-shade-plugin`. Main entry points:

- Gateway console (web UI on `:8080`, default auth `admin/password`): `org.slj.mqtt.sn.console.MqttsnGatewayMain` (`mqtt-sn-gateway-console`)
- Loopback gateway (no backend broker needed): `org.slj.mqtt.sn.gateway.impl.connector.LoopbackGatewayInteractiveMain` (`mqtt-sn-gateway`)
- Paho-backed gateway: `PahoGatewayInteractiveMain` (`mqtt-sn-gateway-connector-paho`)
- Interactive client: `org.slj.mqtt.sn.client.impl.cli.ClientInteractiveMain` (`mqtt-sn-client`)

Run a built jar: `java -jar <module>/target/<finalName>-<version>.jar <port> <gatewayId>`.

### Internal release (GitHub Actions)

`.github/workflows/mqtt-sn-release.yml` ("Release MQTT-SN") publishes an internal HiveMQ build. It is **manually triggered** (`workflow_dispatch`) with a required `version` input (HiveMQ naming, e.g. `0.2.2-1+hivemq`). The job builds on Java 8 (temurin) and `mvn deploy`s **all** modules to this repo's GitHub Packages registry — `https://maven.pkg.github.com/${{ github.repository }}` (i.e. `hivemq/hivemq-edge-mqtt-sn`), auth via the built-in `GITHUB_TOKEN`.

The workflow stamps the version in two steps, and **both are required**: `versions:set` rewrites the parent + child `<parent>` versions, and `versions:set-property -Dproperty=mqtt-sn.version` updates the `${mqtt-sn.version}` property. Inter-module dependencies (e.g. core→codec) resolve through that property, so changing the version without updating it breaks the reactor build. The `versions:set` step passes `-DprocessAllModules=true -DgroupId='*' -DartifactId='*' -DoldVersion='*'` because `mqtt-sn-codec` declares its own `org.mqtt-sn` groupId and its own `<version>` (distinct from the `org.slj` parent); without the wildcards its version is left unstamped and the other modules resolve a codec version that was never published. Keep this in mind whenever you bump or restructure versions.

The `maven-deploy-plugin` version is pinned in the root `pom.xml` (`pluginManagement`, via the `maven-deploy-plugin.version` property) so the deploy step doesn't silently inherit whatever default the runner's Maven ships.

## Architecture

Everything is wired through a **runtime registry** + **pluggable service (SPI)** model. There is no dependency-injection framework; components are assembled with a fluent builder and looked up by interface at runtime.

### Module dependency layering

```
mqtt-sn-codec   (wire format, zero deps)
     ↑
mqtt-sn-core    (SPI interfaces, runtime, in-memory service impls, MQTT topic tree)
     ↑
mqtt-sn-client / mqtt-sn-gateway   (concrete client & gateway runtimes)
     ↑
connectors (paho, aws-iotcore), console, load-test, protection
```

Code lives under the `org.slj.mqtt.sn` package across all modules.

### Core runtime model (mqtt-sn-core)

- **`IMqttsnRuntimeRegistry`** / `AbstractMqttsnRuntimeRegistry` — the central service container. It holds the `MqttsnOptions`, the codec, the transport, and a `Map<Class<? extends IMqttsnService>, List<IMqttsnService>>` of all services. You wire it with a fluent `defaultConfiguration(options).withTransport(...).withCodec(...)....` chain. Client and gateway each have their own subclass (`MqttsnClientRuntimeRegistry`, `MqttsnGatewayRuntimeRegistry`).
- **`AbstractMqttsnRuntime`** — owns lifecycle (`start`/`stop`), the managed thread pools (`createManagedExecutorService`), and the listener fan-out (publish received/sent, connection-state, traffic). All services implementing `IMqttsnService` get `start()`/`stop()` callbacks ordered via `ServiceSort`.
- **Services are interfaces in `spi/`**, with default RAM-backed implementations in `impl/ram/` (e.g. `MqttsnInMemoryMessageQueue`, `MqttsnInMemoryTopicRegistry`, `MqttsnInMemorySubscriptionRegistry`, `MqttsnInMemoryWillRegistry`, `MqttsnInMemoryMessageRegistry`). Swap these out to change persistence. Key services: `IMqttsnMessageHandler`, `IMqttsnMessageStateService` (inflight tracking), `IMqttsnMessageQueueProcessor`, `IMqttsnSessionRegistry`, `IMqttsnTransport`, `IMqttsnSecurityService`, `IMqttsnAuthenticationService`/`IMqttsnAuthorizationService`.
- **Contexts**: `INetworkContext` (a network address/transport binding) is distinct from `IClientIdentifierContext` (a logical MQTT-SN client/session). `INetworkAddressRegistry` maps between them.
- **Session model** in `model/session/`: a session has a `ClientState` of `ACTIVE, DISCONNECTED, AWAKE, ASLEEP, LOST`. Sleeping-client message buffering and the single-message-inflight rule are core to MQTT-SN semantics.
- `MqttsnFilesystemStorageService` / `IMqttsnStorageService` persists runtime preferences; `org.slj.mqtt.tree` is a standalone radix-tree implementation used for topic-filter matching.

### Codec (mqtt-sn-codec)

- `MqttsnCodecs` exposes the four singletons used everywhere: `MQTTSN_CODEC_VERSION_1_2`, `MQTTSN_CODEC_VERSION_1_2_RELAXED`, `MQTTSN_CODEC_VERSION_2_0`, `MQTTSN_CODEC_VERSION_2_0_RELAXED` (relaxed = lenient validation). The boolean ctor arg toggles strict spec validation.
- `wire/version1_2/payload/` and `wire/version2_0/payload/` hold one class per packet type. An `IMqttsnCodec` produces an `IMqttsnMessageFactory` for building packets and parses raw bytes back into `IMqttsnMessage`. See `ExampleUsage.java` for the canonical read/write flow.

### Gateway (mqtt-sn-gateway)

- Two gateway flavors in `impl/gateway/type/`: `MqttsnAggregatingGateway` (the primary, production target — multiplexes many SN clients onto shared backend broker connections) and `MqttsnTransparentGateway`.
- The gateway side translates SN sessions to/from a real MQTT broker via the **connector** SPI (`spi/connector/IMqttsnConnector`, `IMqttsnBackendService`). A connector creates `IMqttsnConnectorConnection`s to the backend. Implementations: `LoopbackMqttsnConnector` (in-module, no broker), Paho TCP (`mqtt-sn-gateway-connector-paho`), AWS IoT Core X.509 (`mqtt-sn-gateway-connector-aws-iotcore`).
- `MqttsnGatewayPerformanceProfile` provides preset thread-pool/queue tunings (`BALANCED_CLOUD_GENERAL_PURPOSE`, `INGRESS_CLOUD`, `EGRESS_CLOUD`, `BALANCED_GATEWAY_GENERAL_PURPOSE`).
- `spi/bridge/` (`IProtocolBridge`) supports gateway clustering / cross-protocol bridging.

### Client (mqtt-sn-client)

`MqttsnClient` exposes both a blocking API and an async publish API, hiding topic registration and connection management. Default transport is UDP (`MqttsnClientUdpTransport`); plug in others by extending `AbstractMqttsnTransport`.

### Configuration & extension conventions

- All tunables flow through `MqttsnOptions` (and `MqttsnGatewayOptions`). **Any option can be overridden at launch via a matching `-D<optionName>=<value>` system property** (e.g. `-DmaxClientSessions=45`). `contextId` (clientId for client / gatewayId for gateway) is required and has no default.
- To extend behavior, implement the relevant `IMqttsn*Service`/`IMqttsn*Listener` interface and register it on the runtime registry via the corresponding `with...(...)` builder method — don't fork the abstract base classes.
- Security: `MqttsnSecurityOptions` configures HMAC/checksum message integrity; `mqtt-sn-protection` + `mqtt-sn-protection-runtimes` implement the v2.0 packet-protection schemes (CCM, GCM, ChaCha20-Poly1305, CMAC, HMAC-SHA3).
