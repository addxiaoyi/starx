# StarX Self-Contained Runtime Implementation Plan

> **For agentic workers:** Execute this plan inline and follow test-driven development for every production-code change.

**Goal:** Make the Velocity, Paper, and Folia StarX artifacts run without other Minecraft plugins or runtime code downloads, while preserving the built-in Uworld authentication flow and Chinese player UX.

**Architecture:** Velocity owns authentication, Uworld, routing, player-list UX, variables, security, HTTP, and backend coordination. Paper/Folia share the server artifact and report platform-specific capabilities through the existing `starx:bridge` protocol. Optional administrator-configured business endpoints remain explicit opt-ins; default startup and player login never contact public services.

**Tech Stack:** Java 17, Velocity 3.5 API, Paper/Folia API, Adventure, SnakeYAML, JUnit 5, Gradle Shadow.

---

### Task 1: Complete the generated Chinese authentication UX configuration

**Files:**

- Modify: `starx-plugins/starx-velocity/src/main/resources/default-config.yml`
- Modify: `starx-plugins/starx-velocity/src/test/java/io/github/addxiaoyi/starx/velocity/config/ConfigLoaderUworldTest.java`
- Test: `starx-plugins/starx-velocity/src/test/java/io/github/addxiaoyi/starx/velocity/config/ConfigLoaderAuthUxTest.java`

- [ ] Run `ConfigLoaderAuthUxTest.generatedConfigContainsTheCompleteUxTree` and verify it fails because the bundled resource has no `auth` root.
- [ ] Add `auth.allow-offline-default: false` and the complete `auth.ux` tree with Chinese title/subtitle defaults and three namespaced sound keys.
- [ ] Add `auth` to the complete-resource root-key assertion.
- [ ] Run both configuration test classes and verify they pass.

### Task 2: Add enforceable self-contained runtime contracts

**Files:**

- Create: `starx-plugins/starx-velocity/src/test/java/io/github/addxiaoyi/starx/velocity/SelfContainedRuntimeContractTest.java`
- Create: `starx-plugins/starx-velocity/src/test/java/io/github/addxiaoyi/starx/velocity/config/RegisteredModuleConfigTest.java`
- Modify: `starx-plugins/starx-velocity/src/main/resources/default-config.yml`

- [ ] Write a source-contract test that scans Velocity production Java for external-plugin API class names and runtime classloader/download patterns.
- [ ] Run it and verify failure points at the current LuckPerms, Floodgate, TAB, and PlaceholderAPI reflection code.
- [ ] Write a module-config test that extracts every registered module ID and asserts the bundled YAML provides a boolean `enabled` switch for it.
- [ ] Run it and verify the currently missing module switches are reported by name.
- [ ] Keep both tests red until Tasks 3-5 remove the dependencies and complete the configuration.

### Task 3: Replace external-plugin integrations with built-in StarX services

**Files:**

- Create: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/variable/StarxVariableService.java`
- Create: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/playerlist/PlayerListModule.java`
- Create: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/auth/OfflineIdentityModule.java`
- Modify: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/StarxVelocityPlugin.java`
- Delete: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/context/BindingContextCalculator.java`
- Delete: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/auth/FloodgateModule.java`
- Delete: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/auth/TabIntegrationModule.java`
- Delete: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/placeholder/StarxPlaceholderModule.java`
- Test: `starx-plugins/starx-velocity/src/test/java/io/github/addxiaoyi/starx/velocity/variable/StarxVariableServiceTest.java`
- Test: `starx-plugins/starx-velocity/src/test/java/io/github/addxiaoyi/starx/velocity/module/auth/OfflineIdentityModuleTest.java`

- [ ] Define and test built-in variables for player, authentication, binding, current server, proxy online count, and first/last login values using explicit Chinese fallbacks.
- [ ] Define and test the offline-prefix identity policy without claiming Bedrock protocol conversion.
- [ ] Add a Velocity player-list module that renders configurable Adventure header/footer text through the built-in variable service.
- [ ] Register the new services/modules and remove all external-plugin reflection and swallowed registration failures.
- [ ] Run the contract and focused unit tests until green.

### Task 4: Remove default public-network lookups from login and security

**Files:**

- Modify: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/welcome/WelcomeModule.java`
- Modify: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/security/RiskModule.java`
- Test: `starx-plugins/starx-velocity/src/test/java/io/github/addxiaoyi/starx/velocity/network/LocalAddressInfoTest.java`

- [ ] Write tests for loopback, private, public, and invalid address classification using only local Java networking APIs.
- [ ] Verify the tests fail because no local classifier exists.
- [ ] Implement local address labels and persist an empty ISP when no administrator-configured provider is enabled.
- [ ] Remove hard-coded `ip-api.com` requests from login and risk paths.
- [ ] Run the network and self-contained contract tests until green.

### Task 5: Finish module defaults, YAML documentation, and cross-platform docs

**Files:**

- Modify: `starx-plugins/starx-velocity/src/main/resources/default-config.yml`
- Modify: `docs/STARX_PLATFORMS.md`
- Modify: `docs/UWORLD_CONFIGURATION.md`
- Modify: `docs/UWORLD_ENVIRONMENT.md`
- Modify: `docs/UWORLD_ACCEPTANCE.md`
- Modify: `starx-plugins/README.md`

- [ ] Add a switch for every registered module and enable safe built-in UX modules by default.
- [ ] Group YAML by common setup, Uworld login, player UX, security, and optional outbound business connections; document every field in Chinese.
- [ ] Document that Velocity supplies the virtual authentication world while Paper/Folia supply backend game worlds and capability reports.
- [ ] Document the offline-mode forwarding requirements and the no-runtime-download/no-plugin-dependency contract.
- [ ] Run all configuration and contract tests.

### Task 6: Build, inspect, deploy, and exercise the real login path

**Files:**

- Build: `starx-plugins/starx-velocity/build/libs/starx-velocity.jar`
- Build: `starx-plugins/starx-server/build/libs/starx-server.jar`
- Deploy: `velocity-test/plugins/starx-velocity.jar`
- Deploy: `velocity-test/.paper-runtime/instances/factions/plugins/starx-server.jar`

- [ ] Run all tests for `starx-common`, `starx-limbo-api`, `starx-standalone-limbo`, `starx-velocity`, and `starx-server`.
- [ ] Build both Shadow JARs and inspect their entries for bundled StarX runtime classes and absence of downloader/classloader integrations.
- [ ] Back up current deployed JARs/configuration and replace only the two StarX artifacts.
- [ ] Restart Velocity and Paper/Folia, then verify startup logs contain no missing-plugin or English player-facing fallback errors.
- [ ] Connect an offline test player through port `25579`, verify entry into Uworld, Chinese title/action bar/sounds, registration/login failure and success feedback, then transfer to the configured backend.
- [ ] Record exact automated and manual acceptance results in `docs/UWORLD_ACCEPTANCE.md`.
