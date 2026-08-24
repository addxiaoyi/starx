# Uworld Environment Closure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use test-driven development for every behavior change. This workspace is not a valid Git worktree; do not initialize Git or add commit steps.

**Goal:** Make the Uworld product boundary and supported operating environment explicit, executable, and consistent with runtime behavior while preserving the running Velocity and Paper processes.

**Architecture:** Keep `starx-velocity.jar` as the only deployable artifact. Fix three runtime behaviors that contradict the approved Uworld specification, normalize the verified Velocity and database defaults, then add a read-only environment doctor that validates an installed Velocity home without printing secrets. Documentation distinguishes candidate, staged, deployed, cold-started, and real-client-verified states.

**Tech Stack:** Java 21, Velocity 3.5.0-SNAPSHOT build 606, Gradle 8.10, JUnit 5, PowerShell, SQLite, CodeGraph.

**Safety constraints:** Never stop or replace protected Velocity PID `82260` or Paper PID `96576`. Never edit a forwarding secret or database. Run Gradle only through `scripts/invoke-gradle-ascii.ps1` and never in parallel.

---

### Task 1: Align Runtime Failure Semantics

**Files:**
- Modify: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/auth/AuthUworldDefinition.java`
- Modify: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/uworld/EmbeddedUworldRuntime.java`
- Modify: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/uworld/ManagedUworld.java`
- Modify: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/uworld/UworldDiagnostics.java`
- Test: `starx-plugins/starx-velocity/src/test/java/io/github/addxiaoyi/starx/velocity/module/auth/AuthUworldDefinitionTest.java`
- Test: `starx-plugins/starx-velocity/src/test/java/io/github/addxiaoyi/starx/velocity/module/uworld/EmbeddedUworldRuntimeTest.java`
- Test: `starx-plugins/starx-velocity/src/test/java/io/github/addxiaoyi/starx/velocity/module/uworld/UworldDiagnosticsTest.java`

- [ ] **Step 1: Make a configured missing loader file fail RED**

Replace `missingWorldFileFallsBackToTheConfiguredPlatform` with a test that calls `definition.generator().generate(editor)`, expects `NoSuchFileException`, and asserts `editor.blocks`, `editor.loads`, and info messages remain empty.

- [ ] **Step 2: Run the loader test and observe the old fallback**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/invoke-gradle-ascii.ps1 `
  :starx-plugins:starx-velocity:test `
  --tests '*AuthUworldDefinitionTest.missingWorldFileFailsBeforePublishingAPlatform' `
  --rerun-tasks --no-daemon --console=plain
```

Expected RED: no exception is thrown and a 5x5 platform is generated.

- [ ] **Step 3: Fail closed for non-VOID missing files**

In `loadConfiguredWorld`, retain the warning with the absolute normalized path, then throw `new NoSuchFileException(file.toString())`. Only `loader-type: VOID` may call `generatePlatform`.

- [ ] **Step 4: Add a runtime-shutdown outcome RED test**

Create an active session, call `fixture.runtime.closeAsync(Component.text("shutdown"))`, and assert the completion type is `UworldOutcomeType.RUNTIME_STOPPING`. Keep the existing direct `world.closeAsync` assertion as `WORLD_CLOSED`.

- [ ] **Step 5: Distinguish runtime shutdown from owner world closure**

Add package-private `ManagedUworld.closeForRuntime(Component)` and a private close helper that shares the same `closeFuture`. Change `EmbeddedUworldRuntime.closeWorld` to accept the terminal type. Public handle closure passes `WORLD_CLOSED`; `closeRegisteredWorlds` passes `RUNTIME_STOPPING`.

- [ ] **Step 6: Add an unavailable diagnostics return target RED test**

Rename the current test to `unavailableFallbackFailsAndCleansTheDiagnosticsSession`. Extend `SessionProbe.fail(Component)` to record the reason and return true. Assert `failReason` contains `return server is unavailable`, no transfer target is set, and the player receives the same plain-language failure.

- [ ] **Step 7: Fail the diagnostics session when no return target exists**

Call `session.fail(reason)` instead of leaving the player in an active diagnostics session. If the terminal operation is already rejected, send `The Uworld session is no longer active.`. Let `DiagnosticsHandler.onOutcome` perform exact state cleanup.

- [ ] **Step 8: Run focused and full Velocity tests**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/invoke-gradle-ascii.ps1 `
  :starx-plugins:starx-velocity:test `
  --tests '*AuthUworldDefinitionTest' `
  --tests '*EmbeddedUworldRuntimeTest' `
  --tests '*UworldDiagnosticsTest' `
  --rerun-tasks --no-daemon --console=plain
```

Expected GREEN: all focused tests pass with zero failures.

### Task 2: Normalize the Supported Configuration Baseline

**Files:**
- Modify: `starx-plugins/starx-common/src/main/java/io/github/addxiaoyi/starx/common/config/DatabaseConfig.java`
- Modify: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/config/ConfigLoader.java`
- Modify: `starx-plugins/starx-velocity/src/test/java/io/github/addxiaoyi/starx/velocity/config/ConfigLoaderUworldTest.java`
- Modify: `velocity-test/fixtures/uworld/velocity.toml`
- Modify: `scripts/tests/smoke-uworld.Tests.ps1`

- [ ] **Step 1: Add a missing-database-section RED test**

Load `NEW_CONFIG`, which intentionally has no `database` root, and assert type `sqlite`, database path `plugins/starx/data.db`, and pool max size `2`.

- [ ] **Step 2: Centralize the bundled SQLite default**

Set `DatabaseConfig.defaults()` to `sqlite`, `plugins/starx/data.db`, and pool size `2`. Make Velocity `parseDatabaseConfig` read every fallback from `DatabaseConfig.defaults()` instead of hard-coded H2 values. Do not claim MySQL, PostgreSQL, or H2 as bundled until their drivers and integration tests exist.

- [ ] **Step 3: Replace the smoke backend table with build-606 canonical TOML**

Use:

```toml
[servers]
lobby = "127.0.0.1:25566"
```

Keep the smoke profile free of an arbitrary `try` fallback. Update the script test to assert this exact table and reject `[[server]]` in the fixture.

- [ ] **Step 4: Run config and smoke script tests**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/invoke-gradle-ascii.ps1 `
  :starx-plugins:starx-velocity:test `
  --tests '*ConfigLoaderUworldTest' `
  --rerun-tasks --no-daemon --console=plain
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/smoke-uworld.Tests.ps1
```

### Task 3: Add a Secret-Safe Installed Environment Doctor

**Files:**
- Create: `scripts/check-uworld-environment.ps1`
- Create: `scripts/tests/check-uworld-environment.Tests.ps1`

- [ ] **Step 1: Write failing doctor fixture tests**

Create a temporary Velocity home containing a build-606 Velocity JAR, canonical `[servers]`, matching candidate/deployed StarX JARs, primary Uworld config, writable SQLite parent, and matching modern-forwarding Paper files. Assert the script prints `UWORLD_ENVIRONMENT=PASS`. Create a negative fixture with a stale deployed JAR, legacy-only config, external LimboAPI, closed backend, mismatched forwarding secrets, and read-only SQLite parent. Assert exit 1 and named failures without either secret appearing in output.

- [ ] **Step 2: Implement doctor inputs and result model**

```powershell
param(
  [Parameter(Mandatory = $true)] [string] $VelocityHome,
  [Parameter(Mandatory = $true)] [string] $CandidateJar,
  [Parameter(Mandatory = $true)] [string] $ServiceIdentity,
  [string] $VelocityJar,
  [string] $JavaExecutable,
  [string] $PaperGlobalConfig,
  [string] $PaperServerProperties,
  [switch] $RequireBackend
)
```

Collect checks as `{ Name, Status, Detail }`; print only paths, hashes, ports, and booleans. Never print API keys, passwords, database URLs, forwarding secrets, or file contents.

- [ ] **Step 3: Implement release-readiness checks**

Require Java 21, Velocity 3.5.0-SNAPSHOT build 606, one deployed StarX JAR, zero external LimboAPI JARs, deployed/candidate SHA equality, primary `starx.uworld` and `uworld` roots, a registered target server, and a writable SQLite parent. With `-RequireBackend`, perform a one-second TCP connect to the target address. For modern forwarding, require nonempty Velocity and Paper secrets and compare them in memory; require backend `server.properties` `online-mode=false` and Paper Velocity forwarding enabled.

- [ ] **Step 4: Run doctor tests against fixtures and the live test home**

The fixture must PASS. The current `velocity-test` home is expected to FAIL until a maintenance window because it contains the old deployed JAR, legacy config, and unreachable configured backend addresses. Preserve that failure as deployment evidence; do not weaken checks to make it green.

### Task 4: Complete Product and Environment Documentation

**Files:**
- Create: `starx-plugins/README.md`
- Create: `docs/UWORLD_ENVIRONMENT.md`
- Modify: `starx-plugins/starx-velocity/README.md`
- Modify: `docs/UWORLD_CONFIGURATION.md`
- Modify: `docs/UWORLD_DEVELOPMENT.md`
- Modify: `docs/UWORLD_ACCEPTANCE.md`
- Modify: `scripts/verify-uworld.ps1`
- Modify: `scripts/tests/verify-uworld.Tests.ps1`

- [ ] **Step 1: Define the product boundary**

State that StarX Velocity is one proxy plugin and Uworld is its embedded managed virtual-world runtime for Auth, Diagnostics, Queue, Maintenance, Tutorial, and other isolated flows. State non-goals: no second plugin, no external LimboAPI, no hot reload, no arbitrary backend fallback, and no claim that unrelated StarX modules share Uworld's acceptance status.

- [ ] **Step 2: Document the supported topology and security boundary**

Document public Velocity -> private Paper -> SQLite, canonical build-606 `[servers]`, modern forwarding requirements, matching secrets, backend `online-mode=false`, Paper Velocity forwarding enabled, and firewall rules that prevent direct public access to backend and HTTP management ports.

- [ ] **Step 3: Document files, permissions, backup, upgrade, and rollback**

Cover Java 21, Gradle 8.10, Velocity build 606, directory ownership, SQLite parent write access, consistent backups of `.db`, `-wal`, and `-shm` while stopped, loader files, Windows and Linux deployment commands, installed/candidate SHA verification, external LimboAPI backup before removal, and service-user ownership.

- [ ] **Step 4: Correct acceptance wording**

Cold start requires a registered target name; backend TCP reachability is required by environment doctor and real-client transfer acceptance. Diagnostics uses light-blue concrete. Split missing return target from registered-but-offline transfer failure. Runtime shutdown expects `RUNTIME_STOPPING`; direct handle closure expects `WORLD_CLOSED`. Keep all unexecuted client rows `UNVERIFIED`.

- [ ] **Step 5: Gate the new documentation and doctor**

Add `starx-plugins/README.md` and `docs/UWORLD_ENVIRONMENT.md` to the verifier document lists. Require `scripts/check-uworld-environment.ps1` and its test. Extend verifier tests so missing files, broken links, legacy-only examples, or absent environment commands fail.

### Task 5: Rebuild and Audit Candidate vs Deployment

- [ ] **Step 1: Run all PowerShell tests**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/check-uworld-environment.Tests.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/smoke-uworld.Tests.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/verify-uworld.Tests.ps1
```

- [ ] **Step 2: Run the full Uworld build gate**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-uworld.ps1
```

- [ ] **Step 3: Run Default and Diagnostics cold starts serially**

Use the exact candidate produced by the full gate. Preserve `UWORLD_DIAGNOSTICS_CLIENT_FLOW=UNVERIFIED` until a real client performs the flow.

- [ ] **Step 4: Sync CodeGraph and run the installed environment doctor**

```powershell
codegraph sync .
codegraph status .
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-uworld-environment.ps1 `
  -VelocityHome velocity-test `
  -CandidateJar starx-plugins/starx-velocity/build/libs/starx-velocity.jar `
  -ServiceIdentity 'NT SERVICE\Velocity' `
  -PaperGlobalConfig velocity-test/.paper-runtime/instances/factions/config/paper-global.yml `
  -PaperServerProperties velocity-test/.paper-runtime/instances/factions/server.properties `
  -RequireBackend
```

Expected live result before maintenance: FAIL with stale deployed JAR, legacy config, and unreachable configured target, while Java 21 and forwarding-secret equality pass. Do not stop PIDs `82260` or `96576`.

- [ ] **Step 5: Re-audit the completion definition**

Automated build, static gate, fixture doctor, and cold-start requirements may become PASS. Installed deployment and all real registration/password/TOTP/recovery/duplicate-UUID/diagnostics player scenarios remain `UNVERIFIED` or FAIL until the exact candidate is deployed during a maintenance window and exercised by a real client.
