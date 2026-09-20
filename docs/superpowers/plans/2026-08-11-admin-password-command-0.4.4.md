# Admin Password Command 0.4.4 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a permission-protected Velocity command for changing a Minecraft account password and release it as StarX 0.4.4.

**Architecture:** Reuse `AuthService.resetPassword` so password validation, hashing, repository updates, and trust revocation stay centralized. Extend the existing `sxadmin` command with `setpassword`, accepting console or `starx.password.reset` staff and never echoing the password.

**Tech Stack:** Java 21, Velocity SimpleCommand, JUnit 5, Gradle Shadow JAR.

---

### Task 1: Lock the command contract

**Files:**
- Modify: `starx-plugins/starx-velocity/src/test/java/io/github/addxiaoyi/starx/velocity/command/CommandNamingContractTest.java`
- Test: `starx-plugins/starx-velocity/src/test/java/io/github/addxiaoyi/starx/velocity/command/CommandNamingContractTest.java`

- [ ] Add assertions that `sxadmin` exposes `setpassword`, requires `starx.password.reset`, accepts console execution, and does not log or send the supplied password.
- [ ] Run the focused test and verify it fails because the command is absent.

### Task 2: Implement password reset routing

**Files:**
- Modify: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/admin/AdminCommandsModule.java`
- Modify: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/StarxVelocityPlugin.java`

- [ ] Inject `AuthService` into `AdminCommandsModule` and route `setpassword` from the existing `sxadmin` command.
- [ ] Validate `<player> <new-password>` at the command boundary, require `starx.password.reset`, call `AuthService.resetPassword`, and return generic success/failure text without the password.
- [ ] Add tab completion only for online player names and never for password arguments.
- [ ] Run the focused command contract test and common auth tests.

### Task 3: Version and release metadata

**Files:**
- Modify: `build.gradle.kts`
- Modify: `CHANGELOG.md`
- Create: `docs/releases/0.4.4.md`
- Modify: `README.md` and compatibility markers required by `verifyReleaseMetadata`

- [ ] Set the project version to `0.4.4` and document the command, permission, security behavior, and upgrade note.
- [ ] Run `clean check` and the Universal JAR boundary verification.

### Task 4: Package and deployment evidence

**Files:**
- Generated: `starx-plugins/starx-universal/build/libs/starx-universal-0.4.4.jar`

- [ ] Inspect the generated descriptors and verify they report `0.4.4` and contain the new command implementation.
- [ ] Do not replace production JARs automatically; report the artifact path and whether live deployment was performed.
