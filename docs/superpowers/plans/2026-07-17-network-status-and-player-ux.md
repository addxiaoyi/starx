# Network Status And Player UX Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose a safe, live StarX network-status contract for the website and use the same data in player-facing status text.

**Architecture:** A small Velocity-only snapshot model will normalize proxy totals, per-server counts, target state, and collection metadata. The authenticated HTTP API will return that snapshot without player identities, while the variable service will render the same counts in configurable player-list templates. The existing Plan collector remains internal and is enabled by default so its history is available to the API as an optional metrics section.

**Tech Stack:** Java 21, Velocity 3.5, Gson, JUnit 5, SnakeYAML.

---

### Task 1: Define And Test The Network Snapshot

**Files:**
- Create: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/status/NetworkStatusSnapshot.java`
- Test: `starx-plugins/starx-velocity/src/test/java/io/github/addxiaoyi/starx/velocity/status/NetworkStatusSnapshotTest.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
void ordersServersAndPreservesNetworkTotals() {
  NetworkStatusSnapshot snapshot = NetworkStatusSnapshot.of(
      Instant.parse("2026-07-17T04:00:00Z"), 5, 100,
      List.of(new ServerStatus("factions", 3, 50), new ServerStatus("lobby", 2, 100)));

  assertEquals(5, snapshot.onlinePlayers());
  assertEquals("factions", snapshot.servers().get(0).name());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :starx-plugins:starx-velocity:test --tests '*NetworkStatusSnapshotTest'`

Expected: FAIL because `NetworkStatusSnapshot` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
public record NetworkStatusSnapshot(
    Instant collectedAt, int onlinePlayers, int maxPlayers, List<ServerStatus> servers) {
  public static NetworkStatusSnapshot of(Instant collectedAt, int onlinePlayers, int maxPlayers,
      List<ServerStatus> servers) { /* validate and sort by name */ }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :starx-plugins:starx-velocity:test --tests '*NetworkStatusSnapshotTest'`

Expected: PASS.

### Task 2: Add Authenticated Website Status API

**Files:**
- Create: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/http/NetworkStatusHandler.java`
- Modify: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/http/HttpApiServer.java`
- Modify: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/StarxVelocityPlugin.java`
- Test: `starx-plugins/starx-velocity/src/test/java/io/github/addxiaoyi/starx/velocity/http/NetworkStatusHandlerTest.java`

- [ ] **Step 1: Write the failing API contract test**

```java
assertEquals(5, payload.get("onlinePlayers"));
assertEquals(2, ((List<?>) payload.get("servers")).size());
assertFalse(payload.containsKey("players"));
```

- [ ] **Step 2: Run it and verify it fails**

Run: `./gradlew :starx-plugins:starx-velocity:test --tests '*NetworkStatusHandlerTest'`

Expected: FAIL because the handler is absent.

- [ ] **Step 3: Implement `/v1/network/status`**

Register it behind the existing API-key filter. Return only `collectedAt`, `onlinePlayers`, `maxPlayers`, and each server's `name`, `onlinePlayers`, and `maxPlayers`; never expose player names, remote addresses, secrets, or database records.

- [ ] **Step 4: Run the API test**

Run: `./gradlew :starx-plugins:starx-velocity:test --tests '*NetworkStatusHandlerTest'`

Expected: PASS.

### Task 3: Activate Plan Collection And Expose Its Summary

**Files:**
- Modify: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/integrations/PlanIntegrationModule.java`
- Modify: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/StarxVelocityPlugin.java`
- Test: `starx-plugins/starx-velocity/src/test/java/io/github/addxiaoyi/starx/velocity/module/integrations/PlanIntegrationModuleTest.java`

- [ ] **Step 1: Write the failing default-config test**

```java
assertTrue(PlanIntegrationModule.Config.defaultConfig().enabled());
```

- [ ] **Step 2: Run it and verify it fails**

Run: `./gradlew :starx-plugins:starx-velocity:test --tests '*PlanIntegrationModuleTest'`

Expected: FAIL because the current default is disabled.

- [ ] **Step 3: Enable the internal collector and inject it into the status handler**

Keep collection local, bounded, and independent of the external Plan plugin. Include recent sample count and last collection time only when enabled.

- [ ] **Step 4: Run focused tests**

Run: `./gradlew :starx-plugins:starx-velocity:test --tests '*PlanIntegrationModuleTest' --tests '*NetworkStatusHandlerTest'`

Expected: PASS.

### Task 4: Enrich Player-Facing Runtime Variables

**Files:**
- Modify: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/variable/StarxVariableService.java`
- Modify: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/variable/StarxPlayerContextFactory.java`
- Test: `starx-plugins/starx-velocity/src/test/java/io/github/addxiaoyi/starx/velocity/variable/StarxVariableServiceTest.java`

- [ ] **Step 1: Write failing variable assertions**

```java
assertEquals("3", variables.resolve("starx_server_online", player));
assertEquals("5", variables.resolve("starx_network_online", player));
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :starx-plugins:starx-velocity:test --tests '*StarxVariableServiceTest'`

Expected: FAIL because the variables are not in the catalog.

- [ ] **Step 3: Add network and current-server count fields**

Add `starx_server_online`, `starx_server_max`, `starx_network_online`, and `starx_network_max`. Keep fallback values explicit and never use the player's IP as a global placeholder.

- [ ] **Step 4: Run focused test**

Run: `./gradlew :starx-plugins:starx-velocity:test --tests '*StarxVariableServiceTest'`

Expected: PASS.

### Task 5: Document The Website Contract And Verify Packaging

**Files:**
- Create: `docs/NETWORK_STATUS_API.md`
- Modify: `starx-plugins/starx-velocity/src/main/resources/default-config.yml`

- [ ] **Step 1: Document the API-key requirement and JSON payload**

Document `GET /v1/network/status`, the `X-API-Key` header, its schema, and the fact that it excludes player identities and IP addresses.

- [ ] **Step 2: Run module tests and package**

Run: `./gradlew :starx-plugins:starx-velocity:test :starx-plugins:starx-velocity:shadowJar`

Expected: focused tests pass and `starx-velocity.jar` is produced.

- [ ] **Step 3: Deploy to the isolated test runtime and probe the authenticated endpoint**

Run the existing Uworld verification after replacing the candidate JAR, then request `/v1/network/status` locally with the configured API key. Confirm health, authorization rejection without a key, and the schema with a key.
