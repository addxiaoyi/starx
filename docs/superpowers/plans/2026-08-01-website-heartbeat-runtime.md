# Website Heartbeat Runtime Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep the Velocity `vc` node continuously registered on the website after the first successful heartbeat.

**Architecture:** Reproduce the production symptom at the `WebsiteSyncRuntime` boundary with a real scheduled runtime and a deterministic client. Make the smallest runtime change that keeps the heartbeat schedule alive and exposes failures with a stable error code, then build the universal StarX artifact and restart only the MCSManager `vc` instance. Verify both the plugin health endpoint and the website's public node freshness over multiple heartbeat intervals.

**Tech Stack:** Java 21, Gradle, JUnit 5, Velocity plugin, MCSManager Socket.IO instance control, website JSON store and public HTTP API.

---

### Task 1: Reproduce continuous heartbeat loss

**Files:**
- Modify: `starx-plugins/starx-website-sync/src/test/java/io/github/addxiaoyi/starx/website/WebsiteSyncRuntimeTest.java`

- [ ] **Step 1: Write the failing test**

Add a test that starts an authenticated runtime with a five-second heartbeat interval, waits for the first successful heartbeat, then requires a second heartbeat before the test deadline.

- [ ] **Step 2: Run the focused test and confirm the failure**

Run `./gradlew :starx-plugins:starx-website-sync:test --tests io.github.addxiaoyi.starx.website.WebsiteSyncRuntimeTest.publishesHeartbeatsAfterInitialSuccess`.

Expected: the new assertion fails if the scheduler stops after the first production heartbeat.

### Task 2: Repair runtime scheduling and diagnostics

**Files:**
- Modify: `starx-plugins/starx-website-sync/src/main/java/io/github/addxiaoyi/starx/website/WebsiteSyncRuntime.java`
- Test: `starx-plugins/starx-website-sync/src/test/java/io/github/addxiaoyi/starx/website/WebsiteSyncRuntimeTest.java`

- [ ] **Step 1: Implement the minimal scheduling fix**

Preserve the one-in-flight guard, keep periodic scheduling independent of a single failed tick, and make non-auth heartbeat failures call the existing logger with the node id and error code so production does not silently enter backoff.

- [ ] **Step 2: Run the focused test and confirm it passes**

Run the same focused Gradle test and expect `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run all website-sync tests**

Run `./gradlew :starx-plugins:starx-website-sync:test` and expect all tests to pass.

### Task 3: Build and deploy only `vc`

**Files:**
- Artifact: `starx-plugins/starx-universal/build/libs/starx-universal-0.3.5.jar`
- Remote target: `/data/minecraft/vc/plugins/starx-universal.jar`

- [ ] **Step 1: Build the universal artifact and run its checks**

Run `./gradlew :starx-plugins:starx-universal:build` and record the resulting SHA-256.

- [ ] **Step 2: Back up the remote jar and copy the new artifact**

Create a timestamped backup under `/data/minecraft/backups/`, copy the artifact to the `vc` plugin path, preserve root ownership and readable permissions, and do not alter the website node token or other server configuration.

- [ ] **Step 3: Restart only the MCSManager `vc` instance**

Use the local daemon Socket.IO control channel with the daemon key loaded only in memory, issuing `instance/restart` for the `vc` UUID.

### Task 4: Verify live cross-server behavior

**Files:**
- No source changes.

- [ ] **Step 1: Verify plugin health and fresh startup**

Confirm `/v1/health` returns HTTP 200, the new Java process has a fresh start time, and the log contains the new StarX initialization.

- [ ] **Step 2: Verify website registration twice**

Read `https://star-web.top/api/public/plugin-network` from the plugin server immediately and again after at least 30 seconds. Require `vc` to be present both times and its `lastSeenAt` to advance.

- [ ] **Step 3: Check residual state**

Confirm the website PM2 process is online, no new missing-secret warning is present, and report any remaining stale `ping-only` nodes separately from the repaired `vc` path.
