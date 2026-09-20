# StarX Adaptive Network Platform Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build risk-adaptive login, one-time cross-device approval, a live network digital twin, smart routing, correlated incident timelines, graceful degradation, player privacy/identity, and autonomous node draining on the existing StarX bridge.

**Architecture:** Pure decision engines live in `starx-common`; Velocity owns live network state and enforcement; Paper/Folia reports node telemetry through the existing heartbeat protocol; the website persists timelines and exposes player/admin views. Every cross-system action carries a correlation ID and is idempotent. Optional website and skin services enhance behavior but their outage never removes password/TOTP login or cached skins.

**Tech Stack:** Java 21, Velocity/Paper APIs, JUnit 5, SQLite/JDBC, Java HTTP server, Node.js 20, Express, Node test runner, React/TypeScript.

---

### Task 1: Shared Decisions

**Files:**
- Create: `starx-plugins/starx-common/src/main/java/io/github/addxiaoyi/starx/common/platform/RiskDecisionEngine.java`
- Create: `starx-plugins/starx-common/src/main/java/io/github/addxiaoyi/starx/common/platform/ServerRoutingEngine.java`
- Create: `starx-plugins/starx-common/src/main/java/io/github/addxiaoyi/starx/common/platform/DegradationPolicy.java`
- Test: `starx-plugins/starx-common/src/test/java/io/github/addxiaoyi/starx/common/platform/*Test.java`

- [ ] Write failing tests proving trusted device/region suppresses step-up, new devices require TOTP, unhealthy nodes are excluded, friend affinity is bounded, ETA is stable, and degraded services select local fallbacks.
- [ ] Run `scripts/invoke-gradle-ascii.ps1 :starx-plugins:starx-common:test --tests '*platform*' --rerun-tasks` and verify missing classes fail compilation.
- [ ] Implement immutable records and deterministic scoring functions. Risk output is `ALLOW`, `REQUIRE_TOTP`, or `REQUIRE_WEB_APPROVAL`; routing output contains selected node, score factors, ETA, and rejected-node reasons.
- [ ] Re-run the focused tests and require `BUILD SUCCESSFUL`.

### Task 2: Correlated Incident Timeline

**Files:**
- Create: `starx-plugins/starx-common/src/main/java/io/github/addxiaoyi/starx/common/platform/TraceContext.java`
- Create: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/operations/IncidentTimeline.java`
- Create: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/http/admin/IncidentTimelineHandler.java`
- Test: matching `TraceContextTest`, `IncidentTimelineTest`, and handler tests.

- [ ] Test that a trace preserves one correlation ID across login, transfer, skin, throttle, kick, and email events; cap each trace at 256 events and redact password, TOTP, token, API key, and webhook secret fields.
- [ ] Implement bounded in-memory storage with JSON snapshots and `/v1/admin/incidents` plus `/v1/admin/incidents/{correlationId}`.
- [ ] Subscribe it to `security:*`, auth, transfer, skin, queue, email, and heartbeat events.
- [ ] Verify concurrent publication, redaction, retention, and API authentication.

### Task 3: Persistent Trusted Devices and Adaptive Login

**Files:**
- Create JDBC trusted-device repository and schema migration in `starx-common`.
- Modify `RiskModule.java`, `AuthModule.java`, and account security website routes.

- [ ] Test hashed device fingerprints, normalized region keys, expiry, revocation, maximum ten devices, and no raw fingerprint persistence.
- [ ] Replace `RiskModule`'s process-local IP map with repository observations and `RiskDecisionEngine`.
- [ ] Enforce TOTP/web approval before releasing the exact Uworld auth lease.
- [ ] Expose devices in the privacy center with revoke-one and revoke-all-other-device operations.

### Task 4: One-Time Cross-Device Approval

**Files:**
- Create Velocity approval challenge service and Paper QR map command.
- Create website `/api/user/minecraft/approval/:token` routes and approval page.

- [ ] Test 256-bit opaque tokens, SHA-256 storage, five-minute expiry, UUID/name/action binding, one-time CAS consumption, replay rejection, cancellation, and mismatched-account rejection.
- [ ] Display a local QR map containing `https://star-web.top/minecraft/approve?token=...`.
- [ ] Support atomic actions `bind_email`, `enable_totp`, and `bind_skin_account`; approval returns only status to the game and never exposes the token after consumption.

### Task 5: Digital Twin and Smart Queue

**Files:**
- Extend heartbeat telemetry, backend node registry, network status handler, and website StarX network service.
- Replace FIFO-only admission decisions with `ServerRoutingEngine` while preserving FIFO fairness within equal priority.

- [ ] Test node latency, MSPT, TPS, memory, capacity, queue length, player path, last-seen age, failure reason, and stale-data marking.
- [ ] Test preference, server type, bounded friend affinity, capacity, MSPT, maintenance, draining, and ETA inputs.
- [ ] Expose a redacted public topology and a detailed administrator topology over SSE with snapshot fallback.

### Task 6: Progressive Degradation and Skin Cache

**Files:**
- Add durable last-known-good skin/profile cache and service circuit states.
- Modify auth, skin bridge, and redirect behavior.

- [ ] Test website outage retains password/TOTP, skin outage serves a verified cached texture, cache miss uses the bundled default, and offline targets recommend only compatible healthy nodes.
- [ ] Publish every fallback decision into the incident timeline with service, reason, chosen fallback, and recovery state.

### Task 7: Privacy Center and Network Identity

**Files:**
- Extend website security/user routes and React account views.
- Add StarX achievement aggregates and TAB/PAPI variables.

- [ ] Test IP masking for normal views, full-IP access only for the owning player, JSON export ownership, session revocation, bindings, playtime, server footprint, community contribution, votes, and reputation.
- [ ] Expose `%starx_playtime_total%`, `%starx_server_footprint%`, `%starx_reputation%`, and `%starx_trust_level%` through the existing variable registry.

### Task 8: Autonomous Node Lifecycle

**Files:**
- Create node health state machine around `BackendNodeRegistry`.
- Integrate state with routing, queue, redirects, and website operations.

- [ ] Test `HEALTHY -> SUSPECT -> DRAINING -> OFFLINE -> WARMING -> HEALTHY`, consecutive heartbeat thresholds, hysteresis, no new admission while draining, existing-player preservation, and gradual 10/25/50/100 percent recovery weights.
- [ ] Add administrator override with expiry and a complete audit/timeline record.

### Task 9: Verification and Deployment

**Files:**
- Update API/OpenAPI docs, default YAML, environment doctor, and acceptance evidence.

- [ ] Run all Java and website suites, clean shadow JAR builds, production frontend type/build checks, bridge smoke, and strict Uworld verification.
- [ ] Deploy website backend with rollback, then Velocity and Paper with SHA verification.
- [ ] Verify real email, QR approval, adaptive login, topology SSE, smart routing, cache fallback, privacy export, and autonomous drain/recovery on the test server.
