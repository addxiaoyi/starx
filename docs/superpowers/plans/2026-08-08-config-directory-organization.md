# StarX Configuration And Runtime Assets Organization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the long StarX Velocity configuration into responsibility-based files, give Uworld structure files a stable runtime directory, and preserve existing single-file and legacy path behavior.

**Architecture:** Keep `plugins/starx/config.yml` as the stable entry point. It will contain only the schema and a validated list of files under `plugins/starx/config/`; the loader will merge those fragments with the embedded defaults before constructing `StarxConfig`. Existing monolithic `config.yml` files remain readable and are migrated once with a backup. Uworld structure files use `plugins/starx/assets/uworld/` by default while still honoring explicitly configured legacy relative paths.

**Tech Stack:** Java 21, Gradle, SnakeYAML, JUnit 5, Velocity plugin runtime, existing StarX Uworld file registry.

---

### Task 1: Lock the new layout and path contracts with failing tests

**Files:**
- Create: `starx-plugins/starx-velocity/src/test/java/io/github/addxiaoyi/starx/velocity/config/ConfigLayoutTest.java`
- Modify: `starx-plugins/starx-velocity/src/test/java/io/github/addxiaoyi/starx/velocity/module/auth/AuthUworldDefinitionTest.java`

- [ ] **Step 1: Write the failing split-config tests**

Cover these exact behaviors:

```java
@Test
void splitEntryPointMergesFilesByConfiguredOrder() throws Exception {
  Path entrypoint = this.tempDir.resolve("config.yml");
  Files.writeString(entrypoint, """
      schema-version: 5
      config-files:
        directory: config
        files: [core.yml, auth.yml]
      """);
  write("config/core.yml", "api-key: core-key\n");
  write("config/auth.yml", "auth:\n  password-bypass-minutes: 9\n");

  StarxConfig config = ConfigLoader.load(entrypoint);

  assertEquals("core-key", config.apiKey());
  assertEquals(9, config.auth().passwordBypassMinutes());
}

@Test
void legacyMonolithicConfigIsReadAndMigratedWithAUsableBackup() throws Exception {
  Path entrypoint = this.tempDir.resolve("config.yml");
  Files.writeString(entrypoint, "schema-version: 5\napi-key: legacy\n");

  StarxConfig config = ConfigLoader.load(entrypoint);

  assertEquals("legacy", config.apiKey());
  assertTrue(Files.isRegularFile(this.tempDir.resolve("config/core.yml")));
  assertTrue(Files.isRegularFile(this.tempDir.resolve("config.yml.split-backup")));
}
```

The migration assertion must use the actual unique backup naming returned by the implementation; the test must assert that the entry point is now the small index and that the fragment contains the legacy `api-key`.

- [ ] **Step 2: Write the failing Uworld asset-directory test**

Create `assets/uworld/auth.schem` under the temporary plugin data directory, configure `file-name: assets/uworld/auth.schem`, and assert that `AuthUworldDefinition` loads `BuiltInWorldFileType.WORLDEDIT_SCHEM` without generating platform blocks.

- [ ] **Step 3: Run the focused tests and verify RED**

Run:

```powershell
./gradlew :starx-plugins:starx-velocity:test --tests '*ConfigLayoutTest' --tests '*AuthUworldDefinitionTest'
```

Expected: compilation or assertion failure because split configuration and the canonical asset path do not exist yet. Do not change production code until this RED result is observed.

### Task 2: Implement the validated configuration layout

**Files:**
- Create: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/config/ConfigLayout.java`
- Modify: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/config/ConfigLoader.java`
- Modify: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/config/ConfigSchemaUpgrader.java`
- Modify: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/config/VelocityAutoConfigurator.java`
- Modify: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/VelocityCompatibility.java`

- [ ] **Step 1: Add `ConfigLayout` with explicit defaults and path validation**

Use these default files and ownership boundaries:

```text
config/core.yml       auto-config, compatibility, api-key, http, webhook, database
config/auth.yml       auth, uniauth, totp
config/network.yml    website-sync, network-automation, napcat
config/modules.yml    modules, player-list
config/uworld.yml     uworld
```

Reject absolute fragment paths, `..` traversal, non-YAML fragment names, non-string list items, and duplicate fragment names with `IllegalArgumentException` naming `config-files`.

- [ ] **Step 2: Make first boot copy the index and all default fragments**

The loader must create `config.yml` and the five files above from classpath resources. Missing fragments in an existing split install are restored from defaults; existing files are never overwritten.

- [ ] **Step 3: Merge fragments before the existing typed parser**

Merge embedded defaults, then configured fragments in list order. Keep the existing `ConfigSchemaUpgrader` and Uworld/limbo alias rules for monolithic files. Split installs must never be rewritten back into a monolithic `config.yml`.

- [ ] **Step 4: Migrate a legacy monolithic file once**

After the existing schema/alias upgrade, write the five fragment files, atomically replace `config.yml` with the small index, and preserve a recoverable sibling backup. A second load must not rewrite or duplicate the migration.

- [ ] **Step 5: Route managed auto-configuration edits to the owning fragment**

`api-key`, optional module flags, `website-sync`, and `uworld.auth.target-server` must be edited in their fragment when the split layout is active. Monolithic configs must retain the existing text-edit behavior.

- [ ] **Step 6: Read compatibility settings from the effective merged configuration**

`VelocityCompatibility.Settings.load` must use the same effective root as `ConfigLoader`, so `compatibility.strict-platform` and `compatibility.report-file` continue to work after moving to `core.yml`.

- [ ] **Step 7: Run the focused tests and verify GREEN**

Run the Task 1 command again. Expected: all focused tests pass, including the existing Uworld, schema-upgrade, auto-config, and compatibility tests that cover the old layout.

### Task 3: Split embedded defaults and define runtime asset placement

**Files:**
- Modify: `starx-plugins/starx-velocity/src/main/resources/default-config.yml`
- Create: `starx-plugins/starx-velocity/src/main/resources/config/core.yml`
- Create: `starx-plugins/starx-velocity/src/main/resources/config/auth.yml`
- Create: `starx-plugins/starx-velocity/src/main/resources/config/network.yml`
- Create: `starx-plugins/starx-velocity/src/main/resources/config/modules.yml`
- Create: `starx-plugins/starx-velocity/src/main/resources/config/uworld.yml`
- Modify: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/auth/AuthUworldDefinition.java`
- Modify: `starx-plugins/starx-velocity/src/test/java/io/github/addxiaoyi/starx/velocity/config/RegisteredModuleConfigTest.java`

- [ ] **Step 1: Replace the long embedded entry with the compact index**

The index must declare schema version 5 and the five fragment names under `config-files`. It must not contain secrets or a second copy of the typed defaults.

- [ ] **Step 2: Move each existing top-level block without changing values**

The fragment files must preserve the current defaults, module IDs, message text, legacy comments needed for operators, and key spelling. No authentication or integration behavior changes are allowed in this task.

- [ ] **Step 3: Set the canonical Uworld filename**

Set the default to `assets/uworld/auth_world.schem`. Keep the existing boundary check and allow explicitly configured legacy paths such as `auth_world.schem` to continue working.

- [ ] **Step 4: Add an asset path explanation to source-level contract tests**

Assert that the default Uworld fragment contains `file-name: assets/uworld/auth_world.schem` and that all supported extensions remain documented in the loader contract.

- [ ] **Step 5: Run resource and focused tests**

Run:

```powershell
./gradlew :starx-plugins:starx-velocity:test --tests '*RegisteredModuleConfigTest' --tests '*ConfigLoader*' --tests '*AuthUworldDefinitionTest'
```

Expected: PASS.

### Task 4: Document the directory layout and operator workflow

**Files:**
- Create: `docs/STARX_CONFIGURATION_LAYOUT.md`
- Modify: `docs/UWORLD_CONFIGURATION.md`
- Modify: `docs/UWORLD_ENVIRONMENT.md`
- Modify: `starx-plugins/starx-velocity/README.md`

- [ ] **Step 1: Document the exact runtime tree**

Show this layout and explain the load boundary:

```text
plugins/starx/
  config.yml
  config/{core,auth,network,modules,uworld}.yml
  assets/uworld/
    *.schem
    *.schematic
    *.nbt
    *.litematic
  uworld/core.yml
  data.db
  cache/
```

- [ ] **Step 2: Explain where each file goes and how it is selected**

State that `uworld.auth.world.file-name` is a relative path from `plugins/starx/`, recommend `assets/uworld/<name>`, list the four supported formats, and state that files in `src/main/resources` are classpath defaults rather than production assets.

- [ ] **Step 3: Document migration and rollback**

Describe the one-time monolithic migration, generated backup, legacy root-path compatibility, and the required full restart. Keep external-player/handshake details out of README files.

- [ ] **Step 4: Run documentation marker checks**

Run:

```powershell
./gradlew verifyReleaseMetadata
git diff --check
```

Expected: PASS with no whitespace errors.

### Task 5: Final verification and acceptance boundary

**Files:**
- No new files; review only the files changed above.

- [ ] **Step 1: Run all Velocity tests**

Run:

```powershell
./gradlew :starx-plugins:starx-velocity:test
```

Expected: PASS.

- [ ] **Step 2: Inspect the diff for unrelated changes**

Run `git status --short` and `git diff --stat`; preserve all pre-existing dirty files and generated artifacts outside this task.

- [ ] **Step 3: Report acceptance layers separately**

Report source/config tests, local JAR build status, production deployment status, and real-client/production resource loading separately. Do not claim deployment because this task intentionally does not deploy.
