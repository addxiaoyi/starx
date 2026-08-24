# StarX Multi-Platform Bridge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a Velocity plugin and one Paper/Folia backend plugin that have different platform responsibilities but exchange authenticated-source, versioned status and capability messages.

**Architecture:** `starx-api` owns a dependency-free binary bridge protocol. `starx-velocity` remains the network authority for authentication, routing, Uworld, queues, maintenance and backend-node visibility. A new `starx-server` JAR runs on Paper and Folia, reports local capabilities and status through a player-carried plugin channel, and contains no Velocity or Uworld internals.

**Tech Stack:** Java 21, Gradle 8.10, Velocity 3.5.0-SNAPSHOT build 606, Paper API 1.21.11, Folia-compatible Bukkit plugin messaging, JUnit 5, ShadowJar.

---

The root Git metadata is invalid in this workspace, so commit steps are replaced with explicit test and artifact checkpoints. Do not initialize or rewrite Git metadata.

### Task 1: Shared bridge protocol

**Files:**
- Create: `starx-plugins/starx-api/src/main/java/io/github/addxiaoyi/starx/api/bridge/BridgeMessage.java`
- Create: `starx-plugins/starx-api/src/main/java/io/github/addxiaoyi/starx/api/bridge/BridgeProtocol.java`
- Create: `starx-plugins/starx-api/src/main/java/io/github/addxiaoyi/starx/api/bridge/PlatformKind.java`
- Create: `starx-plugins/starx-api/src/test/java/io/github/addxiaoyi/starx/api/bridge/BridgeProtocolTest.java`
- Modify: `starx-plugins/starx-api/build.gradle.kts`

- [ ] **Step 1: Write round-trip and boundary tests**

```java
@Test
void roundTripsStatusWithoutChangingFieldOrder() {
  BridgeMessage source = new BridgeMessage(
      BridgeProtocol.STATUS_RESPONSE,
      "lobby",
      PlatformKind.PAPER,
      "request-1",
      Map.of("online", "4", "max", "100"));

  assertEquals(source, BridgeProtocol.decode(BridgeProtocol.encode(source)));
}

@Test
void rejectsUnsupportedProtocolVersion() {
  byte[] payload = BridgeProtocol.encode(BridgeMessage.hello("lobby", PlatformKind.PAPER));
  payload[5] = 2;
  assertThrows(IllegalArgumentException.class, () -> BridgeProtocol.decode(payload));
}
```

- [ ] **Step 2: Verify RED**

Run:

```powershell
& .\scripts\invoke-gradle-ascii.ps1 `
  :starx-plugins:starx-api:test --tests '*BridgeProtocolTest' `
  --no-daemon --console=plain
```

Expected: compilation fails because the bridge protocol classes do not exist.

- [ ] **Step 3: Implement the bounded protocol**

Use magic `STARX`, protocol version `1`, UTF-8 strings with explicit byte lengths, a maximum packet size of 32766 bytes, at most 32 attributes, and deterministic attribute ordering. Reject unknown platform identifiers, duplicate keys, trailing bytes and over-limit strings.

- [ ] **Step 4: Verify GREEN**

Run the Task 1 command and require all bridge tests to pass.

### Task 2: Paper/Folia backend plugin

**Files:**
- Create: `starx-plugins/starx-server/build.gradle.kts`
- Create: `starx-plugins/starx-server/src/main/resources/plugin.yml`
- Create: `starx-plugins/starx-server/src/main/resources/config.yml`
- Create: `starx-plugins/starx-server/src/main/java/io/github/addxiaoyi/starx/server/StarxServerPlugin.java`
- Create: `starx-plugins/starx-server/src/main/java/io/github/addxiaoyi/starx/server/ServerPlatform.java`
- Create: `starx-plugins/starx-server/src/main/java/io/github/addxiaoyi/starx/server/ServerCapabilities.java`
- Create: `starx-plugins/starx-server/src/main/java/io/github/addxiaoyi/starx/server/BackendBridgeSession.java`
- Create: `starx-plugins/starx-server/src/main/java/io/github/addxiaoyi/starx/server/BukkitBackendBridge.java`
- Create: `starx-plugins/starx-server/src/main/java/io/github/addxiaoyi/starx/server/StarxServerCommand.java`
- Create: `starx-plugins/starx-server/src/test/java/io/github/addxiaoyi/starx/server/ServerPlatformTest.java`
- Create: `starx-plugins/starx-server/src/test/java/io/github/addxiaoyi/starx/server/BackendBridgeSessionTest.java`
- Create: `starx-plugins/starx-server/src/test/java/io/github/addxiaoyi/starx/server/PluginDescriptorTest.java`
- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts`

- [ ] **Step 1: Write platform and session tests**

```java
@Test
void foliaAdvertisesRegionSchedulers() {
  assertEquals(ServerPlatform.FOLIA, ServerPlatform.detect(name -> true));
  assertTrue(ServerCapabilities.forPlatform(ServerPlatform.FOLIA)
      .contains("scheduler.region"));
}

@Test
void statusRequestProducesCorrelatedResponse() {
  BackendBridgeSession session = new BackendBridgeSession(
      "lobby", ServerPlatform.PAPER, () -> Map.of("online", "3"));
  BridgeMessage request = BridgeMessage.statusRequest("proxy", "request-7");

  BridgeMessage response = session.receive(request).orElseThrow();

  assertEquals(BridgeProtocol.STATUS_RESPONSE, response.type());
  assertEquals("request-7", response.correlationId());
  assertEquals("3", response.attributes().get("online"));
}
```

- [ ] **Step 2: Verify RED**

Run `:starx-plugins:starx-server:test` and require missing-class failures.

- [ ] **Step 3: Implement the backend adapter**

Register incoming and outgoing `starx:bridge` channels. Send `backend.hello` on player join, answer `proxy.hello` and `proxy.status.request`, and expose `/starxserver status`. Detect Folia by probing `io.papermc.paper.threadedregions.RegionizedServer`; do not call Bukkit's global scheduler or access worlds from an async task. Mark `folia-supported: true` in `plugin.yml`.

- [ ] **Step 4: Verify GREEN and package**

Run:

```powershell
& .\scripts\invoke-gradle-ascii.ps1 `
  :starx-plugins:starx-server:test `
  :starx-plugins:starx-server:shadowJar `
  --no-daemon --console=plain
```

Require `starx-server.jar`, one `plugin.yml`, embedded bridge classes, and no Velocity classes.

### Task 3: Velocity backend-node bridge

**Files:**
- Create: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/bridge/BackendNode.java`
- Create: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/bridge/BackendNodeRegistry.java`
- Create: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/bridge/VelocityBackendBridge.java`
- Create: `starx-plugins/starx-velocity/src/test/java/io/github/addxiaoyi/starx/velocity/bridge/BackendNodeRegistryTest.java`
- Create: `starx-plugins/starx-velocity/src/test/java/io/github/addxiaoyi/starx/velocity/bridge/VelocityBackendBridgeContractTest.java`
- Modify: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/StarxVelocityPlugin.java`
- Modify: `starx-plugins/starx-velocity/src/main/resources/default-config.yml`

- [ ] **Step 1: Write registry and source-trust tests**

```java
@Test
void newerStatusReplacesTheSameRegisteredServerOnly() {
  BackendNodeRegistry registry = new BackendNodeRegistry();
  registry.update("lobby", BridgeMessage.status("lobby", PlatformKind.PAPER,
      "request-1", Map.of("online", "2")), Instant.parse("2026-07-16T00:00:00Z"));

  assertEquals(2, registry.find("lobby").orElseThrow().onlinePlayers());
  assertTrue(registry.find("other").isEmpty());
}
```

- [ ] **Step 2: Verify RED**

Run the two Velocity bridge test classes and require missing-class failures.

- [ ] **Step 3: Implement trusted server messaging**

Register `starx:bridge`. On `ServerConnectedEvent`, send `proxy.hello` and a correlated status request through the exact `ServerConnection`. Accept bridge packets only when the event source is a `ServerConnection`, mark them handled, decode with `BridgeProtocol`, and key state by Velocity's registered server name rather than the backend-declared node ID. Add `/starxbackend status [server]` with permission `starx.command.backend`.

- [ ] **Step 4: Verify GREEN**

Run the focused tests, then all `starx-velocity` tests.

### Task 4: Documentation and release gates

**Files:**
- Create: `docs/STARX_PLATFORMS.md`
- Create: `starx-plugins/starx-server/README.md`
- Modify: `starx-plugins/README.md`
- Modify: `starx-plugins/starx-velocity/README.md`

- [ ] **Step 1: Document the responsibility matrix**

Record that Velocity owns network-global state and Uworld; Paper owns main-thread backend state; Folia owns regionized backend state. Document that plugin messages require a connected player carrier, so an absent backend report means `UNSEEN`, not offline.

- [ ] **Step 2: Document installation**

Place `starx-velocity.jar` only in Velocity and `starx-server.jar` in each Paper/Folia backend. The backend `node-id` should match the Velocity `[servers]` key for operator clarity, while Velocity still treats its own registered-server identity as authoritative.

- [ ] **Step 3: Run release verification**

Run all module tests and both ShadowJar tasks through `scripts/invoke-gradle-ascii.ps1`. Inspect both JAR descriptors, start an isolated Paper instance with `starx-server.jar`, and run CodeGraph sync/status. Keep Folia runtime status explicit if no compatible local Folia server artifact is available.
