# Uworld Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build Uworld as the single-JAR, managed StarX virtual-world runtime, migrate authentication onto it without bypass windows, and provide complete configuration, diagnostics, documentation, licensing, and verification.

**Architecture:** `UworldModule` owns one embedded `StarxUworldFactory`, a registry of immutable world handles, and a registry of CAS-backed player sessions. Public contracts live beside the low-level Limbo API while Velocity-specific lifecycle and event routing stay in `starx-velocity`; `AuthModule` becomes a Uworld flow consumer with its own connection-owner state machine.

**Tech Stack:** Java 21, Velocity 3.5.0-SNAPSHOT build 606, Gradle 8.10, JUnit 5, Adventure Components, embedded Elytrium LimboAPI sources.

**Workspace note:** The aggregate workspace has an empty `.git` directory and is not a valid Git worktree. Execute the listed commit commands only after these files are placed in a real checkout; in the current workspace, use the task tests and file manifest as checkpoints without initializing or rewriting Git metadata.

---

## File Map

### Public Uworld contracts

- Create `starx-plugins/starx-limbo-api/src/main/java/io/github/addxiaoyi/starx/uworld/UworldRuntime.java`
- Create `starx-plugins/starx-limbo-api/src/main/java/io/github/addxiaoyi/starx/uworld/UworldSpec.java`
- Create `starx-plugins/starx-limbo-api/src/main/java/io/github/addxiaoyi/starx/uworld/UworldHandle.java`
- Create `starx-plugins/starx-limbo-api/src/main/java/io/github/addxiaoyi/starx/uworld/UworldWorldGenerator.java`
- Create `starx-plugins/starx-limbo-api/src/main/java/io/github/addxiaoyi/starx/uworld/UworldWorldEditor.java`
- Create `starx-plugins/starx-limbo-api/src/main/java/io/github/addxiaoyi/starx/uworld/UworldFlowOptions.java`
- Create `starx-plugins/starx-limbo-api/src/main/java/io/github/addxiaoyi/starx/uworld/UworldFlowHandler.java`
- Create `starx-plugins/starx-limbo-api/src/main/java/io/github/addxiaoyi/starx/uworld/UworldFlowSession.java`
- Create `starx-plugins/starx-limbo-api/src/main/java/io/github/addxiaoyi/starx/uworld/UworldEnterResult.java`
- Create `starx-plugins/starx-limbo-api/src/main/java/io/github/addxiaoyi/starx/uworld/UworldEnterStatus.java`
- Create `starx-plugins/starx-limbo-api/src/main/java/io/github/addxiaoyi/starx/uworld/UworldOutcome.java`
- Create `starx-plugins/starx-limbo-api/src/main/java/io/github/addxiaoyi/starx/uworld/UworldOutcomeType.java`
- Create `starx-plugins/starx-limbo-api/src/main/java/io/github/addxiaoyi/starx/uworld/UworldPhase.java`
- Create `starx-plugins/starx-limbo-api/src/main/java/io/github/addxiaoyi/starx/uworld/UworldCreationException.java`

### Embedded core and runtime

- Create `starx-plugins/starx-standalone-limbo/src/main/java/io/github/addxiaoyi/starx/uworld/StarxUworldFactory.java`
- Modify `starx-plugins/starx-standalone-limbo/src/main/java/io/github/addxiaoyi/starx/limbo/StarxLimboFactory.java`
- Modify `starx-plugins/starx-standalone-limbo/src/main/java/io/github/addxiaoyi/starx/limbo/LimboAPI.java`
- Create `starx-plugins/starx-standalone-limbo/src/main/java/io/github/addxiaoyi/starx/limbo/LimboPlayerState.java`
- Create `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/uworld/UworldSessionState.java`
- Create `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/uworld/UworldRegistry.java`
- Create `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/uworld/ManagedUworldSession.java`
- Create `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/uworld/ManagedUworld.java`
- Create `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/uworld/UworldWorldEditorImpl.java`
- Create `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/uworld/EmbeddedUworldRuntime.java`
- Create `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/uworld/UworldModule.java`
- Remove superseded `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/limbo/LimboModule.java`, `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/limbo/LimboTransportSession.java`, and `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/limbo/LimboTransferState.java` after consumers migrate.

### Configuration, authentication, and diagnostics

- Replace `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/config/LimboConfig.java` with `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/config/UworldConfig.java`
- Create `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/config/UworldCorePathResolver.java`
- Modify `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/config/ConfigLoader.java`, `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/config/StarxConfig.java`, and `starx-plugins/starx-velocity/src/main/resources/default-config.yml`
- Create `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/auth/AuthFlowIndex.java`
- Create `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/auth/AuthAdmission.java`
- Modify `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/auth/AuthModule.java`
- Create `starx-plugins/starx-common/src/main/java/io/github/addxiaoyi/starx/common/auth/AuthActions.java`
- Modify `starx-plugins/starx-common/src/main/java/io/github/addxiaoyi/starx/common/auth/AuthCommandHandler.java`, `starx-plugins/starx-common/src/main/java/io/github/addxiaoyi/starx/common/auth/AuthService.java`, and `starx-plugins/starx-common/src/main/java/io/github/addxiaoyi/starx/common/auth/SessionManager.java`
- Rename `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/proxytools/LimboHubModule.java` to `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/proxytools/HubCommandModule.java`
- Create `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/uworld/UworldDiagnostics.java`
- Create `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/uworld/UworldDiagnosticsState.java`
- Modify `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/StarxVelocityPlugin.java` and `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/ModuleManager.java`

### Tests, docs, and verification

- Create every exact JUnit path named in the `Files` block of Tasks 2-8.
- Create `scripts/invoke-gradle-ascii.ps1`, `scripts/tests/verify-uworld.ps1`, and `scripts/tests/smoke-uworld.ps1`.
- Create `starx-plugins/starx-velocity/README.md`, `starx-plugins/starx-standalone-limbo/README.md`, `docs/UWORLD_CONFIGURATION.md`, `docs/UWORLD_DEVELOPMENT.md`, `docs/UWORLD_ACCEPTANCE.md`, `LICENSES/MIT.txt`, `LICENSES/AGPL-3.0.txt`, and `NOTICE`.

---

### Task 1: Reproducible Windows Gradle Runner

**Files:**
- Create: `scripts/invoke-gradle-ascii.ps1`
- Test: `scripts/tests/invoke-gradle-ascii.Tests.ps1`

- [ ] **Step 1: Write the failing PowerShell test**

```powershell
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$script = Join-Path $root 'scripts\invoke-gradle-ascii.ps1'

if (Test-Path -LiteralPath $script) {
  throw "Expected runner to be absent during RED verification"
}
```

- [ ] **Step 2: Run the test and verify RED**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/invoke-gradle-ascii.Tests.ps1`

Expected: non-zero exit with `Expected runner to be absent` or missing script.

- [ ] **Step 3: Implement the ASCII drive runner**

```powershell
[CmdletBinding()]
param(
  [Parameter(ValueFromRemainingArguments = $true)]
  [string[]] $GradleArgs,
  [ValidatePattern('^[A-Z]:$')]
  [string] $WorkspaceDrive = 'S:',
  [ValidatePattern('^[A-Z]:$')]
  [string] $CacheDrive = 'G:'
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$cache = Join-Path $env:USERPROFILE '.gradle'
New-Item -ItemType Directory -Force -Path $cache | Out-Null
$cache = (Resolve-Path $cache).Path
$mounted = [System.Collections.Generic.List[string]]::new()
$locationPushed = $false
$gradleExit = 1

function Mount-AsciiDrive([string] $drive, [string] $target) {
  if (Test-Path "$drive\") {
    throw "Drive $drive is already in use"
  }
  & subst $drive $target
  if ($LASTEXITCODE -ne 0) {
    throw "Unable to map $drive to $target"
  }
  $mounted.Add($drive)
}

try {
  Mount-AsciiDrive $WorkspaceDrive $root
  Mount-AsciiDrive $CacheDrive $cache
  Push-Location "$WorkspaceDrive\"
  $locationPushed = $true
  $env:GRADLE_USER_HOME = "$CacheDrive\"
  & .\gradlew.bat @GradleArgs
  $gradleExit = $LASTEXITCODE
} finally {
  if ($locationPushed) {
    Pop-Location
  }
  for ($index = $mounted.Count - 1; $index -ge 0; $index--) {
    & subst $mounted[$index] /d | Out-Null
  }
}
exit $gradleExit
```

- [ ] **Step 4: Replace the RED test with behavior assertions**

Replace the test body with the following. It proves both the success path and cleanup when the second mapping fails:

```powershell
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$script = Join-Path $root 'scripts\invoke-gradle-ascii.ps1'

function Assert-Unmapped([string] $drive) {
  if (Test-Path "$drive\") {
    throw "Expected $drive to be unmapped"
  }
}

Assert-Unmapped 'S:'
Assert-Unmapped 'G:'

$versionOutput = & powershell -NoProfile -ExecutionPolicy Bypass -File $script --version 2>&1
if ($LASTEXITCODE -ne 0) {
  throw "Gradle --version failed with exit $LASTEXITCODE`n$versionOutput"
}
if (($versionOutput -join "`n") -notmatch 'Gradle 8\.10') {
  throw "Expected Gradle 8.10 in runner output"
}
Assert-Unmapped 'S:'
Assert-Unmapped 'G:'

& subst G: $root
if ($LASTEXITCODE -ne 0) {
  throw 'Unable to reserve G: for cleanup regression'
}
try {
  & powershell -NoProfile -ExecutionPolicy Bypass -File $script --version *> $null
  if ($LASTEXITCODE -eq 0) {
    throw 'Expected the occupied cache drive to fail'
  }
  Assert-Unmapped 'S:'
  if (-not (Test-Path 'G:\')) {
    throw 'Runner removed a drive that it did not mount'
  }
} finally {
  & subst G: /d | Out-Null
}

Assert-Unmapped 'S:'
Assert-Unmapped 'G:'
Write-Host 'PASS: Gradle ran through ASCII mappings and mappings were removed'
```

- [ ] **Step 5: Run the test and verify GREEN**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/invoke-gradle-ascii.Tests.ps1`

Expected: `PASS: Gradle ran through ASCII mappings and mappings were removed`.

- [ ] **Step 6: Commit in a real worktree**

```bash
git add scripts/invoke-gradle-ascii.ps1 scripts/tests/invoke-gradle-ascii.Tests.ps1
git commit -m "build: add reproducible Windows Gradle runner"
```

### Task 2: Public Uworld API Contracts

**Files:**
- Create: `starx-plugins/starx-limbo-api/src/main/java/io/github/addxiaoyi/starx/uworld/UworldRuntime.java`
- Create: `starx-plugins/starx-limbo-api/src/main/java/io/github/addxiaoyi/starx/uworld/UworldSpec.java`
- Create: `starx-plugins/starx-limbo-api/src/main/java/io/github/addxiaoyi/starx/uworld/UworldHandle.java`
- Create: `starx-plugins/starx-limbo-api/src/main/java/io/github/addxiaoyi/starx/uworld/UworldWorldGenerator.java`
- Create: `starx-plugins/starx-limbo-api/src/main/java/io/github/addxiaoyi/starx/uworld/UworldWorldEditor.java`
- Create: `starx-plugins/starx-limbo-api/src/main/java/io/github/addxiaoyi/starx/uworld/UworldFlowOptions.java`
- Create: `starx-plugins/starx-limbo-api/src/main/java/io/github/addxiaoyi/starx/uworld/UworldFlowHandler.java`
- Create: `starx-plugins/starx-limbo-api/src/main/java/io/github/addxiaoyi/starx/uworld/UworldFlowSession.java`
- Create: `starx-plugins/starx-limbo-api/src/main/java/io/github/addxiaoyi/starx/uworld/UworldEnterResult.java`
- Create: `starx-plugins/starx-limbo-api/src/main/java/io/github/addxiaoyi/starx/uworld/UworldEnterStatus.java`
- Create: `starx-plugins/starx-limbo-api/src/main/java/io/github/addxiaoyi/starx/uworld/UworldOutcome.java`
- Create: `starx-plugins/starx-limbo-api/src/main/java/io/github/addxiaoyi/starx/uworld/UworldOutcomeType.java`
- Create: `starx-plugins/starx-limbo-api/src/main/java/io/github/addxiaoyi/starx/uworld/UworldPhase.java`
- Create: `starx-plugins/starx-limbo-api/src/main/java/io/github/addxiaoyi/starx/uworld/UworldCreationException.java`
- Modify: `starx-plugins/starx-limbo-api/build.gradle.kts`
- Test: `starx-plugins/starx-limbo-api/src/test/java/io/github/addxiaoyi/starx/uworld/UworldSpecTest.java`
- Test: `starx-plugins/starx-limbo-api/src/test/java/io/github/addxiaoyi/starx/uworld/UworldEnterResultTest.java`

- [ ] **Step 1: Add JUnit and write failing `UworldSpecTest`**

```java
package io.github.addxiaoyi.starx.uworld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class UworldSpecTest {

@Test
void rejectsInvalidDistancesAndBlankNames() {
  assertThrows(IllegalArgumentException.class, () -> UworldSpec.defaults(" "));
  UworldSpec base = UworldSpec.defaults("tutorial");
  assertThrows(IllegalArgumentException.class, () -> base.withViewDistance(0));
  assertThrows(IllegalArgumentException.class, () -> base.withSimulationDistance(33));
}

@Test
void normalizesAValidWorldName() {
  assertEquals("tutorial", UworldSpec.defaults(" tutorial ").name());
}
}
```

- [ ] **Step 2: Run RED**

Run: `scripts/invoke-gradle-ascii.ps1 :starx-plugins:starx-limbo-api:test --tests '*UworldSpecTest' --no-daemon --console=plain`

Expected: compilation fails because `UworldSpec` does not exist.

- [ ] **Step 3: Implement the immutable value types**

Create `UworldSpec.java` with validated defaults and copy methods:

```java
package io.github.addxiaoyi.starx.uworld;

import io.github.addxiaoyi.starx.chunk.Dimension;
import io.github.addxiaoyi.starx.player.GameMode;
import java.util.Objects;

public record UworldSpec(
    String name, Dimension dimension,
    double spawnX, double spawnY, double spawnZ,
    float yaw, float pitch, GameMode gameMode,
    int viewDistance, int simulationDistance,
    int readTimeoutMillis, long worldTime
) {
  public UworldSpec {
    name = Objects.requireNonNull(name, "name").trim();
    if (name.isEmpty()) {
      throw new IllegalArgumentException("Uworld name is blank");
    }
    Objects.requireNonNull(dimension, "dimension");
    Objects.requireNonNull(gameMode, "gameMode");
    requireFinite(spawnX, "spawnX");
    requireFinite(spawnY, "spawnY");
    requireFinite(spawnZ, "spawnZ");
    requireFinite(yaw, "yaw");
    requireFinite(pitch, "pitch");
    if (viewDistance < 1 || viewDistance > 32) {
      throw new IllegalArgumentException("viewDistance must be 1..32");
    }
    if (simulationDistance < 1 || simulationDistance > 32) {
      throw new IllegalArgumentException("simulationDistance must be 1..32");
    }
    if (readTimeoutMillis <= 0) {
      throw new IllegalArgumentException("readTimeoutMillis must be positive");
    }
    if (worldTime < 0) {
      throw new IllegalArgumentException("worldTime must be non-negative");
    }
  }

  public static UworldSpec defaults(String name) {
    return new UworldSpec(
        name, Dimension.OVERWORLD,
        0.5, 100.0, 0.5, 0.0f, 0.0f,
        GameMode.SURVIVAL, 4, 4, 30_000, 6_000L
    );
  }

  public UworldSpec withViewDistance(int value) {
    return new UworldSpec(name, dimension, spawnX, spawnY, spawnZ, yaw, pitch,
        gameMode, value, simulationDistance, readTimeoutMillis, worldTime);
  }

  public UworldSpec withSimulationDistance(int value) {
    return new UworldSpec(name, dimension, spawnX, spawnY, spawnZ, yaw, pitch,
        gameMode, viewDistance, value, readTimeoutMillis, worldTime);
  }

  private static void requireFinite(double value, String field) {
    if (!Double.isFinite(value)) {
      throw new IllegalArgumentException(field + " must be finite");
    }
  }
}
```

Create the following one-type-per-file enums and result records:

```java
// UworldPhase.java
package io.github.addxiaoyi.starx.uworld;
public enum UworldPhase { ENTERING, ACTIVE, TRANSFERRING, CLOSED }

// UworldEnterStatus.java
package io.github.addxiaoyi.starx.uworld;
public enum UworldEnterStatus {
  PLAYER_BUSY, WORLD_CLOSED, RUNTIME_STOPPING, SPAWN_REJECTED
}

// UworldOutcomeType.java
package io.github.addxiaoyi.starx.uworld;
public enum UworldOutcomeType {
  TRANSFERRED, FAILED, CANCELLED, DISCONNECTED, TIMED_OUT,
  KICKED, WRONG_TARGET, RUNTIME_STOPPING, WORLD_CLOSED, SPAWN_REJECTED
}
```

```java
// UworldEnterResult.java
package io.github.addxiaoyi.starx.uworld;

import java.util.Objects;
import net.kyori.adventure.text.Component;

public sealed interface UworldEnterResult {
  record Accepted(UworldFlowSession session) implements UworldEnterResult {
    public Accepted {
      Objects.requireNonNull(session, "session");
    }
  }

  record Rejected(UworldEnterStatus status, Component reason) implements UworldEnterResult {
    public Rejected {
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(reason, "reason");
    }
  }
}
```

```java
// UworldOutcome.java
package io.github.addxiaoyi.starx.uworld;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Objects;
import java.util.Optional;
import net.kyori.adventure.text.Component;

public record UworldOutcome(
    UworldOutcomeType type,
    Component reason,
    Optional<RegisteredServer> target
) {
  public UworldOutcome {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(target, "target");
  }
}
```

```java
// UworldFlowOptions.java
package io.github.addxiaoyi.starx.uworld;

import java.time.Duration;
import java.util.Objects;

public record UworldFlowOptions(Duration activeTimeout, Duration transferTimeout) {
  public UworldFlowOptions {
    requirePositive(activeTimeout, "activeTimeout");
    requirePositive(transferTimeout, "transferTimeout");
  }

  public static UworldFlowOptions defaults() {
    return new UworldFlowOptions(Duration.ofMinutes(5), Duration.ofSeconds(15));
  }

  private static void requirePositive(Duration value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(field + " must be positive");
    }
  }
}
```

```java
// UworldCreationException.java
package io.github.addxiaoyi.starx.uworld;

public final class UworldCreationException extends RuntimeException {
  public UworldCreationException(String owner, String world, String message) {
    super("owner=" + owner + ", world=" + world + ": " + message);
  }
}
```

- [ ] **Step 4: Implement the public runtime, world, session, and callback contracts**

Create these exact interfaces. The editor deliberately exposes no raw Limbo factory:

```java
// UworldRuntime.java
package io.github.addxiaoyi.starx.uworld;

import com.velocitypowered.api.proxy.Player;
import java.util.Optional;

public interface UworldRuntime {
  boolean isReady();
  UworldHandle createWorld(String owner, UworldSpec spec, UworldWorldGenerator generator);
  Optional<UworldFlowSession> session(Player player);
}
```

```java
// UworldHandle.java
package io.github.addxiaoyi.starx.uworld;

import com.velocitypowered.api.proxy.Player;
import java.util.concurrent.CompletionStage;
import net.kyori.adventure.text.Component;

public interface UworldHandle extends AutoCloseable {
  String name();
  boolean isOpen();
  UworldEnterResult enter(Player player, UworldFlowOptions options, UworldFlowHandler handler);
  CompletionStage<Void> closeAsync(Component reason);

  @Override
  default void close() {
    closeAsync(Component.text("Uworld closed"));
  }
}
```

```java
// UworldFlowSession.java
package io.github.addxiaoyi.starx.uworld;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.concurrent.CompletionStage;
import net.kyori.adventure.text.Component;

public interface UworldFlowSession {
  Player player();
  UworldHandle world();
  UworldPhase phase();
  boolean complete(RegisteredServer target);
  boolean fail(Component reason);
  boolean cancel(Component reason);
  CompletionStage<UworldOutcome> completion();
  void execute(Runnable action);
}
```

```java
// UworldFlowHandler.java
package io.github.addxiaoyi.starx.uworld;

public interface UworldFlowHandler {
  default void onReady(UworldFlowSession session) {}
  default void onChat(UworldFlowSession session, String message) {}
  default void onMove(UworldFlowSession session, double x, double y, double z) {}
  default void onRotate(UworldFlowSession session, float yaw, float pitch) {}
  default void onGround(UworldFlowSession session, boolean onGround) {}
  default void onTeleport(UworldFlowSession session, int teleportId) {}
  default void onGeneric(UworldFlowSession session, Object packet) {}
  default void onOutcome(UworldFlowSession session, UworldOutcome outcome) {}
}
```

```java
// UworldWorldGenerator.java
package io.github.addxiaoyi.starx.uworld;

@FunctionalInterface
public interface UworldWorldGenerator {
  void generate(UworldWorldEditor editor) throws Exception;
}
```

```java
// UworldWorldEditor.java
package io.github.addxiaoyi.starx.uworld;

import io.github.addxiaoyi.starx.chunk.BuiltInBiome;
import io.github.addxiaoyi.starx.chunk.VirtualBlock;
import io.github.addxiaoyi.starx.file.BuiltInWorldFileType;
import java.io.IOException;
import java.nio.file.Path;

public interface UworldWorldEditor {
  VirtualBlock createBlock(String modernId);
  void setBlock(int x, int y, int z, VirtualBlock block);
  void setBiome(int x, int y, int z, BuiltInBiome biome);
  void fillSkyLight(int level);
  void fillBlockLight(int level);
  void load(BuiltInWorldFileType type, Path path, int offsetX, int offsetY, int offsetZ)
      throws IOException;
}
```

- [ ] **Step 5: Add result and default-handler tests**

Create `UworldEnterResultTest.java` with the complete boundary assertions:

```java
package io.github.addxiaoyi.starx.uworld;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

final class UworldEnterResultTest {

  @Test
  void rejectsNullResultMembers() {
    assertThrows(NullPointerException.class, () -> new UworldEnterResult.Accepted(null));
    assertThrows(NullPointerException.class,
        () -> new UworldEnterResult.Rejected(null, Component.empty()));
    assertThrows(NullPointerException.class,
        () -> new UworldEnterResult.Rejected(UworldEnterStatus.PLAYER_BUSY, null));
  }

  @Test
  void validatesFlowTimeouts() {
    assertThrows(IllegalArgumentException.class,
        () -> new UworldFlowOptions(Duration.ZERO, Duration.ofSeconds(15)));
    assertThrows(IllegalArgumentException.class,
        () -> new UworldFlowOptions(Duration.ofSeconds(1), Duration.ofSeconds(-1)));
  }

  @Test
  void defaultHandlerCallbacksAreNoOps() {
    UworldFlowHandler handler = new UworldFlowHandler() {};
    assertDoesNotThrow(() -> handler.onChat(null, "hello"));
    assertDoesNotThrow(() -> handler.onMove(null, 1.0, 2.0, 3.0));
    assertDoesNotThrow(() -> handler.onGround(null, true));
  }
}
```

- [ ] **Step 6: Run GREEN**

Run: `scripts/invoke-gradle-ascii.ps1 :starx-plugins:starx-limbo-api:test --no-daemon --console=plain`

Expected: all Uworld API tests pass.

- [ ] **Step 7: Commit in a real worktree**

```bash
git add starx-plugins/starx-limbo-api
git commit -m "feat(uworld): define managed runtime API"
```

### Task 3: Embedded Core Naming and Concurrency

**Files:**
- Create: `starx-plugins/starx-standalone-limbo/src/main/java/io/github/addxiaoyi/starx/uworld/StarxUworldFactory.java`
- Create: `starx-plugins/starx-standalone-limbo/src/main/java/io/github/addxiaoyi/starx/limbo/LimboPlayerState.java`
- Modify: `starx-plugins/starx-standalone-limbo/src/main/java/io/github/addxiaoyi/starx/limbo/StarxLimboFactory.java`
- Modify: `starx-plugins/starx-standalone-limbo/src/main/java/io/github/addxiaoyi/starx/limbo/LimboAPI.java`
- Test: `starx-plugins/starx-standalone-limbo/src/test/java/io/github/addxiaoyi/starx/limbo/LimboCoreStateTest.java`

- [ ] **Step 1: Write failing concurrency tests**

Extract package-private core state into `LimboPlayerState<P,Q,C,S>`. Create this complete test class before its implementation:

```java
package io.github.addxiaoyi.starx.limbo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class LimboCoreStateTest {

@Test
void firstJoinIsClaimedOnceUnderConcurrency() throws Exception {
  AtomicInteger joins = new AtomicInteger();
  LimboPlayerState<Object, Object, Object, Object> state = new LimboPlayerState<>();
  Object player = new Object();

  runConcurrently(16, () -> state.join(player, joins::incrementAndGet));

  assertEquals(1, joins.get());
  assertTrue(state.isJoined(player));
}

@Test
void takeNextServerCannotRaceWithRemove() {
  LimboPlayerState<Object, Object, Object, Object> state = new LimboPlayerState<>();
  Object player = new Object();
  Object server = new Object();
  state.setNextServer(player, server);
  assertSame(server, state.takeNextServer(player));
  assertNull(state.takeNextServer(player));
}

@Test
void queuesAndCallbacksAreConsumedOnce() {
  LimboPlayerState<Object, Object, Object, Object> state = new LimboPlayerState<>();
  Object player = new Object();
  Object queue = new Object();
  Object callback = new Object();
  state.setLoginQueue(player, queue);
  state.setKickCallback(player, callback);
  assertSame(queue, state.takeLoginQueue(player));
  assertNull(state.takeLoginQueue(player));
  assertSame(callback, state.takeKickCallback(player));
  assertNull(state.takeKickCallback(player));
}

@Test
void failedFirstJoinRollsBackTheClaim() {
  LimboPlayerState<Object, Object, Object, Object> state = new LimboPlayerState<>();
  Object player = new Object();
  assertThrows(IllegalStateException.class,
      () -> state.join(player, () -> { throw new IllegalStateException("join failed"); }));
  assertFalse(state.isJoined(player));
  assertTrue(state.join(player, () -> {}));
}

private static void runConcurrently(int workers, Runnable action) throws Exception {
  ExecutorService executor = Executors.newFixedThreadPool(workers);
  CountDownLatch start = new CountDownLatch(1);
  List<Future<?>> futures = new ArrayList<>();
  try {
    for (int index = 0; index < workers; index++) {
      futures.add(executor.submit(() -> {
        start.await();
        action.run();
        return null;
      }));
    }
    start.countDown();
    for (Future<?> future : futures) {
      future.get();
    }
  } finally {
    executor.shutdownNow();
  }
}
}
```

- [ ] **Step 2: Run RED**

Run: `scripts/invoke-gradle-ascii.ps1 :starx-plugins:starx-standalone-limbo:test --tests '*LimboCoreStateTest' --no-daemon --console=plain`

Expected: missing state type or failed single-claim assertion.

- [ ] **Step 3: Implement atomic collections and operations**

Create `LimboPlayerState.java` exactly as follows:

```java
package io.github.addxiaoyi.starx.limbo;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class LimboPlayerState<P, Q, C, S> {
  private final Set<P> joined = ConcurrentHashMap.newKeySet();
  private final ConcurrentMap<P, Q> loginQueues = new ConcurrentHashMap<>();
  private final ConcurrentMap<P, C> kickCallbacks = new ConcurrentHashMap<>();
  private final ConcurrentMap<P, S> nextServers = new ConcurrentHashMap<>();

  boolean join(P player, Runnable onFirstJoin) {
    if (!this.joined.add(player)) {
      return false;
    }
    try {
      onFirstJoin.run();
      return true;
    } catch (RuntimeException | Error error) {
      this.joined.remove(player);
      throw error;
    }
  }

  boolean isJoined(P player) { return this.joined.contains(player); }
  void leave(P player) { this.joined.remove(player); }
  boolean hasJoinedPlayers() { return !this.joined.isEmpty(); }
  int joinedCount() { return this.joined.size(); }
  void setLoginQueue(P player, Q queue) { this.loginQueues.put(player, queue); }
  Q getLoginQueue(P player) { return this.loginQueues.get(player); }
  Q takeLoginQueue(P player) { return this.loginQueues.remove(player); }
  void setKickCallback(P player, C callback) { this.kickCallbacks.put(player, callback); }
  C getKickCallback(P player) { return this.kickCallbacks.get(player); }
  C takeKickCallback(P player) { return this.kickCallbacks.remove(player); }
  void setNextServer(P player, S server) { this.nextServers.put(player, server); }
  S getNextServer(P player) { return this.nextServers.get(player); }
  S takeNextServer(P player) { return this.nextServers.remove(player); }

  void clear() {
    this.joined.clear();
    this.loginQueues.clear();
    this.kickCallbacks.clear();
    this.nextServers.clear();
  }
}
```

Replace the four mutable collections in `LimboAPI` with one `LimboPlayerState<Player, LoginTasksQueue, Function<KickedFromServerEvent, Boolean>, RegisteredServer>`. `setLimboJoined` calls `state.join(player, () -> connectedPlayer.getPhase().onFirstJoin(connectedPlayer))`; disconnect paths call `leave`; queue, callback, and next-server consumption use the matching `take` method. `close()` reads `hasJoinedPlayers()` and `joinedCount()`, then calls `clear()`. Do not reset the process-level `coreOwner`; hot reload remains unsupported.

- [ ] **Step 4: Add `StarxUworldFactory` and compatibility alias**

```java
package io.github.addxiaoyi.starx.uworld;

import com.velocitypowered.api.proxy.ProxyServer;
import io.github.addxiaoyi.starx.limbo.LimboAPI;
import java.nio.file.Path;
import org.slf4j.Logger;

public class StarxUworldFactory extends LimboAPI {
  public StarxUworldFactory(Logger logger, ProxyServer server, Path dataDirectory) {
    super(logger, server, dataDirectory);
  }
}
```

Create `StarxLimboFactory.java` as a compatibility entry retained for one complete Uworld major version and removed no earlier than the next Uworld major release:

```java
package io.github.addxiaoyi.starx.limbo;

import com.velocitypowered.api.proxy.ProxyServer;
import io.github.addxiaoyi.starx.uworld.StarxUworldFactory;
import java.nio.file.Path;
import org.slf4j.Logger;

@Deprecated(forRemoval = true)
public final class StarxLimboFactory extends StarxUworldFactory {
  public StarxLimboFactory(Logger logger, ProxyServer server, Path dataDirectory) {
    super(logger, server, dataDirectory);
  }
}
```

- [ ] **Step 5: Run GREEN and mapping regression**

Run: `scripts/invoke-gradle-ascii.ps1 :starx-plugins:starx-standalone-limbo:test --no-daemon --console=plain`

Expected: mapping and concurrency tests pass.

- [ ] **Step 6: Commit in a real worktree**

```bash
git add starx-plugins/starx-standalone-limbo
git commit -m "fix(uworld): make embedded core state concurrent"
```

### Task 4: Managed Uworld Runtime

**Files:**
- Create: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/uworld/UworldSessionState.java`
- Create: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/uworld/UworldRegistry.java`
- Create: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/uworld/ManagedUworldSession.java`
- Create: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/uworld/ManagedUworld.java`
- Create: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/uworld/UworldWorldEditorImpl.java`
- Create: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/uworld/EmbeddedUworldRuntime.java`
- Test: `starx-plugins/starx-velocity/src/test/java/io/github/addxiaoyi/starx/velocity/module/uworld/UworldSessionStateTest.java`
- Test: `starx-plugins/starx-velocity/src/test/java/io/github/addxiaoyi/starx/velocity/module/uworld/UworldRegistryTest.java`
- Test: `starx-plugins/starx-velocity/src/test/java/io/github/addxiaoyi/starx/velocity/module/uworld/UworldWorldEditorImplTest.java`

- [ ] **Step 1: Write failing session-state tests**

```java
package io.github.addxiaoyi.starx.velocity.module.uworld;

import static io.github.addxiaoyi.starx.velocity.module.uworld.UworldSessionState.TargetConnectResult.COMPLETED;
import static io.github.addxiaoyi.starx.velocity.module.uworld.UworldSessionState.TargetConnectResult.WRONG_TARGET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.uworld.UworldOutcomeType;
import io.github.addxiaoyi.starx.uworld.UworldPhase;
import org.junit.jupiter.api.Test;

final class UworldSessionStateTest {

@Test
void transferKeepsSessionUntilExactTargetConnects() {
  UworldSessionState<String> state = new UworldSessionState<>();
  assertTrue(state.activate());
  assertTrue(state.beginTransfer("lobby"));
  assertEquals(COMPLETED, state.onConnected("lobby"));
  assertEquals(UworldPhase.CLOSED, state.phase());
  assertEquals(UworldOutcomeType.TRANSFERRED, state.outcome());
}

@Test
void wrongTargetTerminatesTheSession() {
  UworldSessionState<String> state = new UworldSessionState<>();
  assertTrue(state.activate());
  assertTrue(state.beginTransfer("lobby"));
  assertEquals(WRONG_TARGET, state.onConnected("other"));
  assertEquals(UworldPhase.CLOSED, state.phase());
  assertEquals(UworldOutcomeType.WRONG_TARGET, state.outcome());
}

@Test
void onlyOneTerminalOutcomeWins() {
  UworldSessionState<String> state = new UworldSessionState<>();
  assertTrue(state.close(UworldOutcomeType.TIMED_OUT));
  assertFalse(state.close(UworldOutcomeType.DISCONNECTED));
  assertEquals(UworldOutcomeType.TIMED_OUT, state.outcome());
}
}
```

- [ ] **Step 2: Run RED**

Run: `scripts/invoke-gradle-ascii.ps1 :starx-plugins:starx-velocity:test --tests '*UworldSessionStateTest' --no-daemon --console=plain`

Expected: missing state type.

- [ ] **Step 3: Implement `UworldSessionState` with CAS**

Store phase, expected target, and outcome in one immutable state held by `AtomicReference`:

```java
package io.github.addxiaoyi.starx.velocity.module.uworld;

import io.github.addxiaoyi.starx.uworld.UworldOutcomeType;
import io.github.addxiaoyi.starx.uworld.UworldPhase;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

final class UworldSessionState<T> {
  enum TargetConnectResult { COMPLETED, WRONG_TARGET, IGNORED }

  private final AtomicReference<State<T>> state = new AtomicReference<>(
      new State<>(UworldPhase.ENTERING, null, null));

  boolean activate() {
    State<T> current = this.state.get();
    return current.phase == UworldPhase.ENTERING
        && this.state.compareAndSet(current, new State<>(UworldPhase.ACTIVE, null, null));
  }

  boolean beginTransfer(T target) {
    Objects.requireNonNull(target, "target");
    State<T> current = this.state.get();
    return current.phase == UworldPhase.ACTIVE
        && this.state.compareAndSet(current,
            new State<>(UworldPhase.TRANSFERRING, target, null));
  }

  TargetConnectResult onConnected(T actual) {
    while (true) {
      State<T> current = this.state.get();
      if (current.phase != UworldPhase.TRANSFERRING) {
        return TargetConnectResult.IGNORED;
      }
      boolean matches = current.target == actual;
      UworldOutcomeType outcome = matches
          ? UworldOutcomeType.TRANSFERRED
          : UworldOutcomeType.WRONG_TARGET;
      State<T> closed = new State<>(UworldPhase.CLOSED, current.target, outcome);
      if (this.state.compareAndSet(current, closed)) {
        return matches ? TargetConnectResult.COMPLETED : TargetConnectResult.WRONG_TARGET;
      }
    }
  }

  boolean close(UworldOutcomeType outcome) {
    Objects.requireNonNull(outcome, "outcome");
    while (true) {
      State<T> current = this.state.get();
      if (current.phase == UworldPhase.CLOSED) {
        return false;
      }
      if (this.state.compareAndSet(current,
          new State<>(UworldPhase.CLOSED, current.target, outcome))) {
        return true;
      }
    }
  }

  UworldPhase phase() { return this.state.get().phase; }
  UworldOutcomeType outcome() { return this.state.get().outcome; }
  T target() { return this.state.get().target; }

  private record State<T>(UworldPhase phase, T target, UworldOutcomeType outcome) {}
}
```

- [ ] **Step 4: Write failing registry tests**

Create `UworldRegistryTest.java`:

```java
package io.github.addxiaoyi.starx.velocity.module.uworld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.uworld.UworldCreationException;
import org.junit.jupiter.api.Test;

final class UworldRegistryTest {

  @Test
  void keepsAuthenticationAndDiagnosticsWorldsIndependent() {
    UworldRegistry<Object, Object, Object> registry = new UworldRegistry<>();
    assertTrue(registry.registerWorld("starx.auth", "auth", new Object()));
    assertTrue(registry.registerWorld("starx.diagnostics", "diagnostics", new Object()));
    assertEquals(2, registry.worldCount());
  }

  @Test
  void duplicateWorldReportsBothOwners() {
    UworldRegistry<Object, Object, Object> registry = new UworldRegistry<>();
    registry.registerWorld("starx.auth", "shared", new Object());
    UworldCreationException error = assertThrows(UworldCreationException.class,
        () -> registry.registerWorld("starx.diagnostics", "shared", new Object()));
    assertTrue(error.getMessage().contains("starx.auth"));
    assertTrue(error.getMessage().contains("starx.diagnostics"));
  }

  @Test
  void onePlayerOwnsOnlyOneExactSession() {
    UworldRegistry<Object, Object, Object> registry = new UworldRegistry<>();
    Object player = new Object();
    Object first = new Object();
    Object second = new Object();
    assertEquals(UworldRegistry.ClaimResult.ACCEPTED, registry.claim(player, first));
    assertEquals(UworldRegistry.ClaimResult.PLAYER_BUSY, registry.claim(player, second));
    assertFalse(registry.release(player, second));
    assertTrue(registry.release(player, first));
  }

  @Test
  void stoppingRejectsNewSessions() {
    UworldRegistry<Object, Object, Object> registry = new UworldRegistry<>();
    registry.beginStopping();
    assertEquals(UworldRegistry.ClaimResult.RUNTIME_STOPPING,
        registry.claim(new Object(), new Object()));
  }
}
```

- [ ] **Step 5: Write the failing editor-seal test**

Create `UworldWorldEditorImplTest.java` using Java proxies, so no mocking dependency is needed:

```java
package io.github.addxiaoyi.starx.velocity.module.uworld;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.addxiaoyi.starx.LimboFactory;
import io.github.addxiaoyi.starx.chunk.BuiltInBiome;
import io.github.addxiaoyi.starx.chunk.VirtualBlock;
import io.github.addxiaoyi.starx.chunk.VirtualWorld;
import io.github.addxiaoyi.starx.file.BuiltInWorldFileType;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class UworldWorldEditorImplTest {

  @Test
  void everyMutationFailsAfterPublication() {
    LimboFactory factory = proxy(LimboFactory.class);
    VirtualWorld world = proxy(VirtualWorld.class);
    VirtualBlock block = proxy(VirtualBlock.class);
    UworldWorldEditorImpl editor = new UworldWorldEditorImpl(factory, world);
    editor.seal();

    assertThrows(IllegalStateException.class, () -> editor.createBlock("minecraft:stone"));
    assertThrows(IllegalStateException.class, () -> editor.setBlock(0, 99, 0, block));
    assertThrows(IllegalStateException.class,
        () -> editor.setBiome(0, 100, 0, BuiltInBiome.PLAINS));
    assertThrows(IllegalStateException.class, () -> editor.fillSkyLight(15));
    assertThrows(IllegalStateException.class, () -> editor.fillBlockLight(0));
    assertThrows(IllegalStateException.class,
        () -> editor.load(BuiltInWorldFileType.SCHEMATIC, Path.of("world.schematic"), 0, 0, 0));
  }

  private static <T> T proxy(Class<T> type) {
    return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
        (instance, method, args) -> { throw new AssertionError("sealed editor touched " + method); }));
  }
}
```

- [ ] **Step 6: Implement managed world/session/runtime classes**

`EmbeddedUworldRuntime` owns:

```java
private final ConcurrentMap<String, ManagedUworld> worlds = new ConcurrentHashMap<>();
private final ConcurrentMap<Player, ManagedUworldSession> sessions = new ConcurrentHashMap<>();
private final AtomicBoolean ready = new AtomicBoolean();
private final AtomicBoolean stopping = new AtomicBoolean();
```

Implement the reusable atomic registry as follows and have `EmbeddedUworldRuntime` delegate world/session ownership to it:

```java
package io.github.addxiaoyi.starx.velocity.module.uworld;

import io.github.addxiaoyi.starx.uworld.UworldCreationException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

final class UworldRegistry<P, W, S> {
  enum ClaimResult { ACCEPTED, PLAYER_BUSY, RUNTIME_STOPPING }

  private final ConcurrentMap<String, OwnedWorld<W>> worlds = new ConcurrentHashMap<>();
  private final ConcurrentMap<P, S> sessions = new ConcurrentHashMap<>();
  private final AtomicBoolean stopping = new AtomicBoolean();

  boolean registerWorld(String owner, String name, W world) {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(world, "world");
    OwnedWorld<W> added = new OwnedWorld<>(owner, world);
    OwnedWorld<W> existing = this.worlds.putIfAbsent(name, added);
    if (existing != null) {
      throw new UworldCreationException(owner, name,
          "already owned by " + existing.owner());
    }
    return true;
  }

  ClaimResult claim(P player, S session) {
    Objects.requireNonNull(player, "player");
    Objects.requireNonNull(session, "session");
    if (this.stopping.get()) {
      return ClaimResult.RUNTIME_STOPPING;
    }
    return this.sessions.putIfAbsent(player, session) == null
        ? ClaimResult.ACCEPTED : ClaimResult.PLAYER_BUSY;
  }

  boolean release(P player, S session) {
    return this.sessions.remove(player, session);
  }

  void beginStopping() { this.stopping.set(true); }
  int worldCount() { return this.worlds.size(); }

  private record OwnedWorld<W>(String owner, W world) {}
}
```

Implement `UworldWorldEditorImpl` with `LimboFactory`, `VirtualWorld`, and `AtomicBoolean open`. `createBlock` delegates to `factory.createSimpleBlock`; block and light operations delegate to `VirtualWorld`; biome uses `Biome.of`; file load delegates to `factory.openWorldFile(type, path).toWorld(factory, world, offsets)`. Every public method begins with `requireOpen()`, `seal()` changes `open` from true to false, and a repeated seal is harmless.

World creation performs `generator.generate(editor)` before `factory.createLimbo(world)`. Entry installs the session with `putIfAbsent`, then calls `spawnPlayer`; any synchronous spawn failure removes the exact session and returns `SPAWN_REJECTED`.

`UworldWorldEditorImpl` owns an `AtomicBoolean open`. Every public method calls `requireOpen()` first. `EmbeddedUworldRuntime.createWorld` always calls `editor.seal()` in a `finally` block around `generator.generate(editor)` and never publishes the `ManagedUworld` when generation fails. `ManagedUworld.closeAsync` first atomically closes all sessions whose world token matches, then calls the low-level `Limbo.dispose()` exactly once.

- [ ] **Step 7: Implement target-bound Velocity transitions**

`allowsBackend(player, effectiveServer)` denies ACTIVE/ENTERING sessions and allows TRANSFERRING only when `session.state().target() == effectiveServer`; non-Uworld players remain untouched. `onConnected` handles `WRONG_TARGET` by removing the exact session, completing it with `UworldOutcomeType.WRONG_TARGET`, and disconnecting the player. Exact target completion removes the session with `sessions.remove(player, session)`. Kick, disconnect, active timeout, transfer timeout, and connection-future exceptions all call the same CAS close method; add one focused adapter test for each outcome and assert only one completion callback.

- [ ] **Step 8: Run GREEN**

Run: `scripts/invoke-gradle-ascii.ps1 :starx-plugins:starx-velocity:test --tests '*Uworld*' --no-daemon --console=plain`

Expected: all runtime state/registry tests pass.

- [ ] **Step 9: Commit in a real worktree**

```bash
git add starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/uworld starx-plugins/starx-velocity/src/test/java/io/github/addxiaoyi/starx/velocity/module/uworld
git commit -m "feat(uworld): add managed embedded runtime"
```

### Task 5: Uworld Configuration and Naming Migration

**Files:**
- Create: `starx-plugins/starx-velocity/src/main/resources/default-config.yml`
- Create: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/config/UworldConfig.java`
- Create: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/config/UworldCorePathResolver.java`
- Modify: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/config/ConfigLoader.java`
- Modify: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/config/StarxConfig.java`
- Test: `starx-plugins/starx-velocity/src/test/java/io/github/addxiaoyi/starx/velocity/config/UworldConfigTest.java`
- Test: `starx-plugins/starx-velocity/src/test/java/io/github/addxiaoyi/starx/velocity/config/ConfigLoaderUworldTest.java`
- Test: `starx-plugins/starx-velocity/src/test/java/io/github/addxiaoyi/starx/velocity/config/UworldCorePathResolverTest.java`

- [ ] **Step 1: Write failing migration tests**

Use `@TempDir` and these complete YAML fixtures in `ConfigLoaderUworldTest`:

```java
package io.github.addxiaoyi.starx.velocity.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.velocity.config.StarxConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ConfigLoaderUworldTest {

private static final String NEW_CONFIG = """
    modules:
      starx.uworld:
        enabled: true
    uworld:
      enabled: true
      transfer-timeout-seconds: 19
      auth:
        timeout-seconds: 301
        target-server: "new-hub"
        world:
          dimension: "OVERWORLD"
          spawn-x: 1.5
          spawn-y: 101.0
          spawn-z: 2.5
          spawn-yaw: 10.0
          spawn-pitch: 20.0
          game-mode: "SURVIVAL"
          loader-type: "VOID"
          file-name: "auth_world.schem"
          offset-x: 1
          offset-y: 2
          offset-z: 3
          view-distance: 5
          simulation-distance: 6
          platform-radius: 7
      diagnostics:
        enabled: false
        timeout-seconds: 121
        platform-radius: 8
    """;

private static final String LEGACY_ROOT = """
    limbo:
      enabled: true
      dimension: "OVERWORLD"
      spawn-x: 0.5
      spawn-y: 100.0
      spawn-z: 0.5
      spawn-yaw: 0.0
      spawn-pitch: 0.0
      game-mode: "SURVIVAL"
      world-loader-type: "VOID"
      world-file-name: "legacy_auth.schem"
      world-offset-x: 4
      world-offset-y: 5
      world-offset-z: 6
      auth-timeout-seconds: 302
      hub-server: "legacy-hub"
      view-distance: 7
      simulation-distance: 8
      platform-size: 9
    """;

private static final String LEGACY_CONFIG = """
    modules:
      starx.limbo:
        enabled: true
    """ + LEGACY_ROOT;

private static final String BOTH_CONFIG = NEW_CONFIG + "\n" + LEGACY_ROOT;

@TempDir
Path tempDir;

private StarxConfig load(String yaml, List<String> warnings) throws IOException {
  Path file = this.tempDir.resolve("config.yml");
  Files.writeString(file, yaml, StandardCharsets.UTF_8);
  return ConfigLoader.load(file, warnings::add);
}

@Test
void newRootWinsAndAllFieldsAreParsed() throws Exception {
  StarxConfig config = load(NEW_CONFIG, new ArrayList<>());
  assertEquals("new-hub", config.uworld().auth().targetServer());
  assertEquals(19, config.uworld().transferTimeoutSeconds());
  assertEquals(7, config.uworld().auth().world().platformRadius());
  assertEquals(8, config.uworld().diagnostics().platformRadius());
  assertTrue(config.isModuleEnabled("starx.uworld"));
}

@Test
void legacyRootMapsEveryAuthenticationWorldField() throws Exception {
  StarxConfig config = load(LEGACY_CONFIG, new ArrayList<>());
  assertEquals("legacy-hub", config.uworld().auth().targetServer());
  assertEquals("legacy_auth.schem", config.uworld().auth().world().fileName());
  assertEquals(9, config.uworld().auth().world().platformRadius());
  assertEquals(7, config.uworld().auth().world().viewDistance());
}

@Test
void newRootWinsAndEmitsOneMigrationWarning() throws Exception {
  List<String> warnings = new ArrayList<>();
  StarxConfig config = load(BOTH_CONFIG, warnings);
  assertEquals("new-hub", config.uworld().auth().targetServer());
  assertEquals(1, warnings.size());
}

@Test
void blankTargetNormalizesToLobby() throws Exception {
  String yaml = NEW_CONFIG.replace("new-hub", "   ");
  assertEquals("lobby", load(yaml, new ArrayList<>()).uworld().auth().targetServer());
}
}
```

Create `UworldConfigTest.java` exactly as follows:

```java
package io.github.addxiaoyi.starx.velocity.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

final class UworldConfigTest {

  @Test
  void normalizesSupportedNamesAndBlankTarget() {
    for (String loader : List.of("VOID", "SCHEMATIC", "WORLDEDIT_SCHEM", "STRUCTURE")) {
      UworldConfig.World world = world(
          " overworld ", 0.5, 100.0, 0.5, 0.0f, 0.0f,
          " survival ", " " + loader.toLowerCase() + " ",
          4, 4, 5);
      assertAll(
          () -> assertEquals("OVERWORLD", world.dimension()),
          () -> assertEquals("SURVIVAL", world.gameMode()),
          () -> assertEquals(loader, world.loaderType()),
          () -> assertEquals("auth_world.schem", world.fileName()));
    }

    UworldConfig blankTarget = config(15, 300, "   ", 120, world("VOID"));
    UworldConfig namedTarget = config(15, 300, "  auth-hub  ", 120, world("VOID"));
    assertEquals("lobby", blankTarget.auth().targetServer());
    assertEquals("auth-hub", namedTarget.auth().targetServer());
  }

  @Test
  void rejectsNonPositiveTimeouts() {
    assertAll(
        () -> assertThrows(IllegalArgumentException.class,
            () -> config(0, 300, "lobby", 120, world("VOID"))),
        () -> assertThrows(IllegalArgumentException.class,
            () -> config(-1, 300, "lobby", 120, world("VOID"))),
        () -> assertThrows(IllegalArgumentException.class,
            () -> config(15, 0, "lobby", 120, world("VOID"))),
        () -> assertThrows(IllegalArgumentException.class,
            () -> config(15, -1, "lobby", 120, world("VOID"))),
        () -> assertThrows(IllegalArgumentException.class,
            () -> config(15, 300, "lobby", 0, world("VOID"))),
        () -> assertThrows(IllegalArgumentException.class,
            () -> config(15, 300, "lobby", -1, world("VOID"))));
  }

  @Test
  void enforcesDistanceAndPlatformRadiusBounds() {
    assertAll(
        () -> assertDoesNotThrow(() -> world(
            "OVERWORLD", 0.5, 100.0, 0.5, 0.0f, 0.0f,
            "SURVIVAL", "VOID", 1, 32, 1)),
        () -> assertDoesNotThrow(() -> world(
            "OVERWORLD", 0.5, 100.0, 0.5, 0.0f, 0.0f,
            "SURVIVAL", "VOID", 32, 1, 64)),
        () -> assertThrows(IllegalArgumentException.class, () -> world(
            "OVERWORLD", 0.5, 100.0, 0.5, 0.0f, 0.0f,
            "SURVIVAL", "VOID", 0, 4, 5)),
        () -> assertThrows(IllegalArgumentException.class, () -> world(
            "OVERWORLD", 0.5, 100.0, 0.5, 0.0f, 0.0f,
            "SURVIVAL", "VOID", 33, 4, 5)),
        () -> assertThrows(IllegalArgumentException.class, () -> world(
            "OVERWORLD", 0.5, 100.0, 0.5, 0.0f, 0.0f,
            "SURVIVAL", "VOID", 4, 0, 5)),
        () -> assertThrows(IllegalArgumentException.class, () -> world(
            "OVERWORLD", 0.5, 100.0, 0.5, 0.0f, 0.0f,
            "SURVIVAL", "VOID", 4, 33, 5)),
        () -> assertThrows(IllegalArgumentException.class, () -> world(
            "OVERWORLD", 0.5, 100.0, 0.5, 0.0f, 0.0f,
            "SURVIVAL", "VOID", 4, 4, 0)),
        () -> assertThrows(IllegalArgumentException.class, () -> world(
            "OVERWORLD", 0.5, 100.0, 0.5, 0.0f, 0.0f,
            "SURVIVAL", "VOID", 4, 4, 65)),
        () -> assertThrows(IllegalArgumentException.class,
            () -> new UworldConfig.Diagnostics(false, 120, 0)),
        () -> assertThrows(IllegalArgumentException.class,
            () -> new UworldConfig.Diagnostics(false, 120, 65)));
  }

  @Test
  void rejectsNonFiniteSpawnAndRotationValues() {
    assertAll(
        () -> assertThrows(IllegalArgumentException.class, () -> world(
            "OVERWORLD", Double.NaN, 100.0, 0.5, 0.0f, 0.0f,
            "SURVIVAL", "VOID", 4, 4, 5)),
        () -> assertThrows(IllegalArgumentException.class, () -> world(
            "OVERWORLD", Double.POSITIVE_INFINITY, 100.0, 0.5, 0.0f, 0.0f,
            "SURVIVAL", "VOID", 4, 4, 5)),
        () -> assertThrows(IllegalArgumentException.class, () -> world(
            "OVERWORLD", 0.5, Double.NEGATIVE_INFINITY, 0.5, 0.0f, 0.0f,
            "SURVIVAL", "VOID", 4, 4, 5)),
        () -> assertThrows(IllegalArgumentException.class, () -> world(
            "OVERWORLD", 0.5, 100.0, Double.NaN, 0.0f, 0.0f,
            "SURVIVAL", "VOID", 4, 4, 5)),
        () -> assertThrows(IllegalArgumentException.class, () -> world(
            "OVERWORLD", 0.5, 100.0, 0.5, Float.NaN, 0.0f,
            "SURVIVAL", "VOID", 4, 4, 5)),
        () -> assertThrows(IllegalArgumentException.class, () -> world(
            "OVERWORLD", 0.5, 100.0, 0.5, 0.0f, Float.POSITIVE_INFINITY,
            "SURVIVAL", "VOID", 4, 4, 5)));
  }

  @Test
  void rejectsUnknownEnumLikeNames() {
    assertAll(
        () -> assertThrows(IllegalArgumentException.class, () -> world(
            "MOON", 0.5, 100.0, 0.5, 0.0f, 0.0f,
            "SURVIVAL", "VOID", 4, 4, 5)),
        () -> assertThrows(IllegalArgumentException.class, () -> world(
            "OVERWORLD", 0.5, 100.0, 0.5, 0.0f, 0.0f,
            "BUILDER", "VOID", 4, 4, 5)),
        () -> assertThrows(IllegalArgumentException.class, () -> world(
            "OVERWORLD", 0.5, 100.0, 0.5, 0.0f, 0.0f,
            "SURVIVAL", "ANVIL", 4, 4, 5)));
  }

  private static UworldConfig config(
      int transferTimeout,
      int authTimeout,
      String target,
      int diagnosticTimeout,
      UworldConfig.World world
  ) {
    return new UworldConfig(
        true,
        transferTimeout,
        new UworldConfig.Auth(authTimeout, target, world),
        new UworldConfig.Diagnostics(false, diagnosticTimeout, 5));
  }

  private static UworldConfig.World world(String loader) {
    return world(
        "OVERWORLD", 0.5, 100.0, 0.5, 0.0f, 0.0f,
        "SURVIVAL", loader, 4, 4, 5);
  }

  private static UworldConfig.World world(
      String dimension,
      double spawnX,
      double spawnY,
      double spawnZ,
      float spawnYaw,
      float spawnPitch,
      String gameMode,
      String loader,
      int viewDistance,
      int simulationDistance,
      int platformRadius
  ) {
    return new UworldConfig.World(
        dimension,
        spawnX,
        spawnY,
        spawnZ,
        spawnYaw,
        spawnPitch,
        gameMode,
        loader,
        " auth_world.schem ",
        0,
        0,
        0,
        viewDistance,
        simulationDistance,
        platformRadius);
  }
}
```

- [ ] **Step 2: Run RED**

Run: `scripts/invoke-gradle-ascii.ps1 :starx-plugins:starx-velocity:test --tests '*UworldConfigTest' --tests '*ConfigLoaderUworldTest' --tests '*UworldCorePathResolverTest' --no-daemon --console=plain`

Expected: `UworldConfig` missing or legacy precedence wrong.

- [ ] **Step 3: Implement nested config records**

Implement these exact immutable shapes in `UworldConfig`: `UworldConfig(boolean enabled, int transferTimeoutSeconds, Auth auth, Diagnostics diagnostics)`, `Auth(int timeoutSeconds, String targetServer, World world)`, `World(String dimension, double spawnX, double spawnY, double spawnZ, float spawnYaw, float spawnPitch, String gameMode, String loaderType, String fileName, int offsetX, int offsetY, int offsetZ, int viewDistance, int simulationDistance, int platformRadius)`, and `Diagnostics(boolean enabled, int timeoutSeconds, int platformRadius)`. Constructors perform the bounds from Step 1, trim names, normalize a blank target to `lobby`, and uppercase enum-like values with `Locale.ROOT`. Validate dimension against `Dimension.values()`, game mode against `GameMode.values()`, and loader type against the four accepted values before any world is published.

`ConfigLoader` parses the new nested tree first. When no `uworld` root exists, map every legacy field shown in `LEGACY_CONFIG`; `platform-size` maps to `platformRadius`. When both roots exist, parse only the new root and invoke `warningSink.accept("Both uworld and legacy limbo configuration are present; uworld takes precedence")` exactly once.

- [ ] **Step 4: Move the generated default YAML to a resource**

`ConfigLoader` reads `/default-config.yml` using UTF-8 instead of maintaining a Java string literal. Add `load(Path, Consumer<String> warningSink)` and keep the old overload delegating to a no-op warning sink. Preserve all unrelated current defaults and replace the old module/root entries with this exact block:

```yaml
modules:
  starx.uworld:
    enabled: true

uworld:
  enabled: true
  transfer-timeout-seconds: 15
  auth:
    timeout-seconds: 300
    target-server: "lobby"
    world:
      dimension: "OVERWORLD"
      spawn-x: 0.5
      spawn-y: 100.0
      spawn-z: 0.5
      spawn-yaw: 0.0
      spawn-pitch: 0.0
      game-mode: "SURVIVAL"
      loader-type: "VOID"
      file-name: "auth_world.schem"
      offset-x: 0
      offset-y: 0
      offset-z: 0
      view-distance: 4
      simulation-distance: 4
      platform-radius: 5
  diagnostics:
    enabled: false
    timeout-seconds: 120
    platform-radius: 5
```

Add a resource test that loads a missing config, then asserts the generated file contains each key above exactly once and contains no top-level legacy root.

- [ ] **Step 5: Write and implement `core.yml` path migration**

Create `UworldCorePathResolverTest.java`:

```java
package io.github.addxiaoyi.starx.velocity.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class UworldCorePathResolverTest {

  @TempDir
  Path tempDir;

  @Test
  void neitherFileUsesTheNewDirectoryWithoutCreatingFiles() throws Exception {
    List<String> warnings = new ArrayList<>();

    Path selected = UworldCorePathResolver.resolve(this.tempDir, warnings::add);

    assertEquals(this.tempDir.resolve("uworld"), selected);
    assertEquals(List.of(), warnings);
    assertEquals(false, Files.exists(this.tempDir.resolve("uworld/core.yml")));
    assertEquals(false, Files.exists(this.tempDir.resolve("limbo/core.yml")));
  }

  @Test
  void newFileWinsAndRemainsUnchanged() throws Exception {
    Path core = write("uworld/core.yml", "new-sentinel");
    List<String> warnings = new ArrayList<>();

    Path selected = UworldCorePathResolver.resolve(this.tempDir, warnings::add);

    assertEquals(this.tempDir.resolve("uworld"), selected);
    assertEquals(List.of(), warnings);
    assertEquals("new-sentinel", Files.readString(core, StandardCharsets.UTF_8));
  }

  @Test
  void legacyOnlyUsesLegacyDirectoryAndWarnsExactlyOnce() throws Exception {
    Path core = write("limbo/core.yml", "legacy-sentinel");
    List<String> warnings = new ArrayList<>();

    Path selected = UworldCorePathResolver.resolve(this.tempDir, warnings::add);

    assertEquals(this.tempDir.resolve("limbo"), selected);
    assertEquals(List.of(
        "Using legacy Uworld core path limbo/core.yml; move it to uworld/core.yml during maintenance"),
        warnings);
    assertEquals("legacy-sentinel", Files.readString(core, StandardCharsets.UTF_8));
  }

  @Test
  void bothFilesPreferNewWithoutWarningOrMutation() throws Exception {
    Path modern = write("uworld/core.yml", "new-sentinel");
    Path legacy = write("limbo/core.yml", "legacy-sentinel");
    List<String> warnings = new ArrayList<>();

    Path selected = UworldCorePathResolver.resolve(this.tempDir, warnings::add);

    assertEquals(this.tempDir.resolve("uworld"), selected);
    assertEquals(List.of(), warnings);
    assertEquals("new-sentinel", Files.readString(modern, StandardCharsets.UTF_8));
    assertEquals("legacy-sentinel", Files.readString(legacy, StandardCharsets.UTF_8));
  }

  private Path write(String relative, String content) throws Exception {
    Path file = this.tempDir.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content, StandardCharsets.UTF_8);
    return file;
  }
}
```

Implement `UworldCorePathResolver.java`:

```java
package io.github.addxiaoyi.starx.velocity.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

public final class UworldCorePathResolver {
  private static final String LEGACY_WARNING =
      "Using legacy Uworld core path limbo/core.yml; move it to uworld/core.yml during maintenance";

  private UworldCorePathResolver() {
  }

  public static Path resolve(Path dataDirectory, Consumer<String> warningSink) {
    Objects.requireNonNull(dataDirectory, "dataDirectory");
    Objects.requireNonNull(warningSink, "warningSink");

    Path modern = dataDirectory.resolve("uworld");
    Path legacy = dataDirectory.resolve("limbo");
    boolean hasModern = Files.isRegularFile(modern.resolve("core.yml"));
    boolean hasLegacy = Files.isRegularFile(legacy.resolve("core.yml"));
    if (hasModern || !hasLegacy) {
      return modern;
    }

    warningSink.accept(LEGACY_WARNING);
    return legacy;
  }
}
```

`UworldModule` passes the selected directory to `StarxUworldFactory`. Only `StarxUworldFactory.initialize` may create a missing `core.yml`; the resolver is read-only, so the neither-exists test remains valid before factory initialization.

- [ ] **Step 6: Fix module aliases**

`StarxConfig.isModuleEnabled("starx.uworld")` checks the new id first and reads the legacy id only when the new entry is absent. Remove the incorrect `starx.hub -> starx.limbo` fallback. Add tests for new-only, old-only, both-with-conflicting-values, and `starx.hub`; `HubCommandModule.name()` remains exactly `starx.hub`.

- [ ] **Step 7: Run GREEN**

Run: `scripts/invoke-gradle-ascii.ps1 :starx-plugins:starx-velocity:test --tests '*Config*' --no-daemon --console=plain`

Expected: new config, legacy config, precedence, bounds, and warning tests pass.

- [ ] **Step 8: Commit in a real worktree**

```bash
git add starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/config starx-plugins/starx-velocity/src/main/resources/default-config.yml starx-plugins/starx-velocity/src/test
git commit -m "feat(uworld): migrate configuration and module naming"
```

### Task 6: Authentication Owner and Route State Machine

**Files:**
- Create: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/auth/AuthFlowIndex.java`
- Test: `starx-plugins/starx-velocity/src/test/java/io/github/addxiaoyi/starx/velocity/module/auth/AuthFlowIndexTest.java`
- Test: `starx-plugins/starx-velocity/src/test/java/io/github/addxiaoyi/starx/velocity/module/auth/AuthFlowRouteTest.java`
- Test: `starx-plugins/starx-velocity/src/test/java/io/github/addxiaoyi/starx/velocity/module/auth/AuthFlowConcurrencyTest.java`
- Remove after migration: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/auth/ConnectionStateIndex.java`, `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/auth/AuthAccessGate.java`, and their matching tests after `rg` confirms no consumers.

- [ ] **Step 1: Write failing owner-race tests**

```java
package io.github.addxiaoyi.starx.velocity.module.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class AuthFlowIndexTest {

@Test
void duplicateUuidCannotReplaceTheOwner() {
  UUID id = UUID.randomUUID();
  Object first = new Object();
  Object second = new Object();
  AuthFlowIndex<Object, String, String> flows = new AuthFlowIndex<>();

  assertEquals(AuthFlowIndex.BeginResult.ACCEPTED,
      flows.begin(id, first, "duplicate"));
  assertEquals(AuthFlowIndex.BeginResult.DUPLICATE,
      flows.begin(id, second, "duplicate"));
  assertTrue(flows.requiresAuth(first));
  assertEquals(AuthFlowIndex.Phase.DENIED, flows.phase(second).orElseThrow());
  assertEquals("duplicate", flows.denial(second).orElseThrow());
  assertFalse(flows.close(id, second));
  assertTrue(flows.requiresAuth(first));
}

@Test
void staleCloseCannotReleaseReplacementOwner() {
  UUID id = UUID.randomUUID();
  Object first = new Object();
  Object replacement = new Object();
  AuthFlowIndex<Object, String, String> flows = new AuthFlowIndex<>();
  assertEquals(AuthFlowIndex.BeginResult.ACCEPTED,
      flows.begin(id, first, "duplicate"));
  assertTrue(flows.close(id, first));
  assertEquals(AuthFlowIndex.BeginResult.ACCEPTED,
      flows.begin(id, replacement, "duplicate"));
  assertFalse(flows.close(id, first));
  assertTrue(flows.requiresAuth(replacement));
}
}
```

- [ ] **Step 2: Write failing route tests**

Create `AuthFlowRouteTest.java`:

```java
package io.github.addxiaoyi.starx.velocity.module.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class AuthFlowRouteTest {

@Test
void pendingPhasesDenyBackendsAndTargetPhaseBindsOneServer() {
  UUID id = UUID.randomUUID();
  Object player = new Object();
  AuthFlowIndex<Object, String, String> flows = new AuthFlowIndex<>();
  assertEquals(AuthFlowIndex.BeginResult.ACCEPTED,
      flows.begin(id, player, "duplicate"));
  assertTrue(flows.awaitPassword(player));
  assertFalse(flows.allowsBackend(player, "lobby"));
  assertTrue(flows.awaitTotp(player));
  assertFalse(flows.allowsBackend(player, "lobby"));
  assertTrue(flows.route(player, "lobby"));
  assertTrue(flows.allowsBackend(player, "lobby"));
  assertFalse(flows.allowsBackend(player, "other"));
}

@Test
void wrongConnectedTargetDeniesAndKeepsTheOwnerUntilDisconnect() {
  UUID id = UUID.randomUUID();
  Object player = new Object();
  AuthFlowIndex<Object, String, String> flows = new AuthFlowIndex<>();
  flows.begin(id, player, "duplicate");
  flows.awaitPassword(player);
  flows.route(player, "lobby");
  assertEquals(AuthFlowIndex.ConnectResult.WRONG_TARGET, flows.connected(player, "other"));
  assertTrue(flows.requiresAuth(player));
  assertEquals(AuthFlowIndex.BeginResult.DUPLICATE,
      flows.begin(id, new Object(), "duplicate"));
  assertTrue(flows.close(id, player));
  assertEquals(AuthFlowIndex.BeginResult.ACCEPTED,
      flows.begin(id, new Object(), "duplicate"));
}

@Test
void exactConnectedTargetCompletesButKeepsTheOwnerUntilDisconnect() {
  UUID id = UUID.randomUUID();
  Object player = new Object();
  AuthFlowIndex<Object, String, String> flows = new AuthFlowIndex<>();
  flows.begin(id, player, "duplicate");
  flows.awaitPassword(player);
  flows.route(player, "lobby");
  assertEquals(AuthFlowIndex.ConnectResult.COMPLETED, flows.connected(player, "lobby"));
  assertFalse(flows.requiresAuth(player));
  assertEquals(AuthFlowIndex.BeginResult.DUPLICATE,
      flows.begin(id, new Object(), "duplicate"));
  assertTrue(flows.close(id, player));
  assertEquals(AuthFlowIndex.BeginResult.ACCEPTED,
      flows.begin(id, new Object(), "duplicate"));
}
}
```

- [ ] **Step 3: Run RED**

Run: `scripts/invoke-gradle-ascii.ps1 :starx-plugins:starx-velocity:test --tests '*AuthFlowIndexTest' --no-daemon --console=plain`

Expected: missing state machine.

- [ ] **Step 4: Implement the pure state machine**

Implement `AuthFlowIndex<P, S, D>` with these invariants:

- `begin(UUID, P, D)` claims UUID ownership by exact player identity. An accepted flow receives one immutable `AuthLease`; a duplicate connection gets its own connection-level `DENIED` flow and denial reason, but never receives the owner's lease.
- Password and TOTP input use explicit pending and verifying phases so one connection cannot submit the same phase concurrently.
- `route` saves the exact target object. `allowsBackend` permits only that object while `TARGET_PENDING`; equal names are not interchangeable.
- `connected` changes the connection to `COMPLETE` or `DENIED` but does not remove the flow or UUID owner. `deny` also retains both records and the denial reason.
- Only `close(UUID, P)`, called for the exact `DisconnectEvent` player, removes the flow and releases UUID ownership. A stale or duplicate player cannot release a replacement owner.
- `lease(P)` exposes the immutable lease only for the accepted owner. Downstream auth reads, transitions, cancellation and removal require the same `(UUID, AuthLease)` pair.

Use identity-based player keys, CAS-backed phase state, and a synchronized claim/close boundary so flow publication and UUID ownership cannot diverge.

- [ ] **Step 5: Run GREEN and a concurrency loop**

Create `AuthFlowConcurrencyTest.java`:

```java
package io.github.addxiaoyi.starx.velocity.module.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

final class AuthFlowConcurrencyTest {

  @Test
  void exactlyOneOwnerWinsEveryRace() throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      for (int iteration = 0; iteration < 1_000; iteration++) {
        UUID id = UUID.randomUUID();
        AuthFlowIndex<Object, Object, String> flows = new AuthFlowIndex<>();
        CountDownLatch start = new CountDownLatch(1);
        Future<AuthFlowIndex.BeginResult> first = executor.submit(() -> {
          start.await();
          return flows.begin(id, new Object(), "duplicate");
        });
        Future<AuthFlowIndex.BeginResult> second = executor.submit(() -> {
          start.await();
          return flows.begin(id, new Object(), "duplicate");
        });
        start.countDown();
        int accepted = (first.get() == AuthFlowIndex.BeginResult.ACCEPTED ? 1 : 0)
            + (second.get() == AuthFlowIndex.BeginResult.ACCEPTED ? 1 : 0);
        assertEquals(1, accepted, "iteration " + iteration);
      }
    } finally {
      executor.shutdownNow();
    }
  }
}
```

Then run:

`scripts/invoke-gradle-ascii.ps1 :starx-plugins:starx-velocity:test --tests '*AuthFlowIndexTest' --rerun-tasks --no-daemon --console=plain`

Expected: `AuthFlowIndexTest` passes with zero failures and the concurrency loop completes all 1000 iterations.

- [ ] **Step 6: Commit in a real worktree**

```bash
git add starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/auth starx-plugins/starx-velocity/src/test/java/io/github/addxiaoyi/starx/velocity/module/auth
git commit -m "fix(auth): bind authentication to connection owners"
```

### Task 7: Authentication Integration on Uworld

**Files:**
- Create: `starx-plugins/starx-common/src/main/java/io/github/addxiaoyi/starx/common/auth/AuthLease.java`
- Modify: `starx-plugins/starx-common/src/main/java/io/github/addxiaoyi/starx/common/auth/AuthCommandHandler.java`
- Modify: `starx-plugins/starx-common/src/main/java/io/github/addxiaoyi/starx/common/auth/AuthService.java`
- Modify: `starx-plugins/starx-common/src/main/java/io/github/addxiaoyi/starx/common/auth/SessionManager.java`
- Create: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/auth/AuthLoginBarrier.java`
- Modify: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/auth/AuthModule.java`
- Test: `starx-plugins/starx-common/src/test/java/io/github/addxiaoyi/starx/common/auth/AuthCommandHandlerTest.java`
- Test: `starx-plugins/starx-common/src/test/java/io/github/addxiaoyi/starx/common/auth/SessionManagerTest.java`
- Test: `starx-plugins/starx-velocity/src/test/java/io/github/addxiaoyi/starx/velocity/module/auth/AuthLoginBarrierTest.java`

- [ ] **Step 1: Write failing credential-phase tests**

Introduce immutable `AuthLease` plus package-private `AuthOperations` in `AuthCommandHandler`, backed by `AuthService`, so tests can use a recording fake without a database while proving the lease reaches every mutating operation:

```java
package io.github.addxiaoyi.starx.common.auth;

import java.net.InetAddress;
import java.util.UUID;

interface AuthOperations {
  boolean isUserRegistered(UUID uuid);
  AuthResult register(AuthLease lease, UUID uuid, String username, String password);
  AuthResult login(AuthLease lease, UUID uuid, String username, String password,
                   InetAddress address, String deviceId);
  AuthResult verifyTotp(AuthLease lease, UUID uuid, String code);
  AuthResult verifyRecoveryCode(AuthLease lease, UUID uuid, String code);
}
```

Create `AuthCommandHandlerTest.java`:

```java
package io.github.addxiaoyi.starx.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.InetAddress;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class AuthCommandHandlerTest {

  @Test
  void credentialPhasePassesTheExactConnectionLease() {
    UUID id = UUID.randomUUID();
    AuthLease lease = AuthLease.create();
    RecordingActions actions = new RecordingActions(true);
    AuthCommandHandler handler = new AuthCommandHandler(actions);

    handler.handleCredentials(
        lease, id, "player", "secret", InetAddress.getLoopbackAddress(), "device");

    assertEquals(1, actions.loginCalls);
    assertEquals(0, actions.totpCalls);
    assertEquals(0, actions.recoveryCalls);
    assertEquals(lease, actions.lease);
  }

  @Test
  void unregisteredCredentialsCallRegisterOnly() {
    RecordingActions actions = new RecordingActions(false);
    new AuthCommandHandler(actions).handleCredentials(
        AuthLease.create(), UUID.randomUUID(), "player", "secret", null, "device");
    assertEquals(1, actions.registerCalls);
    assertEquals(0, actions.loginCalls);
  }

  @Test
  void sixDigitsCallTotpOnly() {
    RecordingActions actions = new RecordingActions(true);
    new AuthCommandHandler(actions).handleSecondFactor(
        AuthLease.create(), UUID.randomUUID(), "123456");
    assertEquals(1, actions.totpCalls);
    assertEquals(0, actions.recoveryCalls);
  }

  @Test
  void tenAlphanumericCharactersCallRecoveryOnly() {
    RecordingActions actions = new RecordingActions(true);
    new AuthCommandHandler(actions).handleSecondFactor(
        AuthLease.create(), UUID.randomUUID(), "ABCD123456");
    assertEquals(0, actions.totpCalls);
    assertEquals(1, actions.recoveryCalls);
  }

  private static final class RecordingActions implements AuthOperations {
    private final boolean registered;
    private int registerCalls;
    private int loginCalls;
    private int totpCalls;
    private int recoveryCalls;
    private AuthLease lease;

    private RecordingActions(boolean registered) { this.registered = registered; }
    public boolean isUserRegistered(UUID uuid) { return this.registered; }
    public AuthResult register(
        AuthLease lease, UUID uuid, String username, String password) {
      this.lease = lease;
      this.registerCalls++;
      return AuthResult.failure("register");
    }
    public AuthResult login(AuthLease lease, UUID uuid, String username, String password,
                            InetAddress address, String deviceId) {
      this.lease = lease;
      this.loginCalls++;
      return AuthResult.failure("login");
    }
    public AuthResult verifyTotp(AuthLease lease, UUID uuid, String code) {
      this.lease = lease;
      this.totpCalls++;
      return AuthResult.failure("totp");
    }
    public AuthResult verifyRecoveryCode(AuthLease lease, UUID uuid, String code) {
      this.lease = lease;
      this.recoveryCalls++;
      return AuthResult.failure("recovery");
    }
  }
}
```

- [ ] **Step 2: Implement explicit handler entry points**

Create `AuthCommandHandler` with an `AuthOperations authService` field and these methods:

```java
public AuthResult handleCredentials(AuthLease lease, UUID uuid, String username, String rawInput,
                                    InetAddress address, String deviceId) {
  String input = normalizeCredential(rawInput);
  return authService.isUserRegistered(uuid)
      ? authService.login(lease, uuid, username, input, address, deviceId)
      : authService.register(lease, uuid, username, input);
}

public AuthResult handleSecondFactor(AuthLease lease, UUID uuid, String rawInput) {
  String input = normalizeSecondFactor(rawInput);
  return input.length() == 10 && input.chars().allMatch(Character::isLetterOrDigit)
      ? authService.verifyRecoveryCode(lease, uuid, input)
      : authService.verifyTotp(lease, uuid, input);
}
```

`normalizeCredential` accepts direct input and strips only the compatibility prefixes `/register `, `/reg `, `/login `, and `/l `; any other slash-prefixed input fails with the existing player-readable message. `normalizeSecondFactor` trims input, rejects blank values, and accepts only a six-digit code or a ten-character alphanumeric recovery code.

Add this exact method to `SessionManager`:

```java
public boolean removeIfState(
    UUID uuid, AuthLease lease, AuthSession.State expected) {
  AuthSession current = this.sessions.get(uuid);
  return current != null && current.ownedBy(lease) && current.state() == expected
      && this.sessions.remove(uuid, current);
}
```

Create `SessionManagerTest.java`:

```java
package io.github.addxiaoyi.starx.common.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class SessionManagerTest {
  @Test
  void removesOnlyTheExpectedAuthenticationState() {
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    UUID authenticatedId = UUID.randomUUID();
    UUID pendingId = UUID.randomUUID();
    AuthLease authenticatedLease = AuthLease.create();
    AuthLease pendingLease = AuthLease.create();
    AuthLease staleLease = AuthLease.create();
    sessions.open(authenticatedId, "done", null, authenticatedLease);
    sessions.open(pendingId, "pending", null, pendingLease);
    assertTrue(sessions.transition(
        authenticatedId, authenticatedLease,
        AuthSession.State.GUEST, AuthSession.State.AUTHENTICATED));
    assertTrue(sessions.transition(
        pendingId, pendingLease,
        AuthSession.State.GUEST, AuthSession.State.AUTHENTICATING));

    assertFalse(sessions.removeIfState(
        authenticatedId, authenticatedLease, AuthSession.State.AUTHENTICATING));
    assertFalse(sessions.removeIfState(
        pendingId, staleLease, AuthSession.State.AUTHENTICATING));
    assertTrue(sessions.removeIfState(
        pendingId, pendingLease, AuthSession.State.AUTHENTICATING));
    assertTrue(sessions.get(authenticatedId, authenticatedLease).isPresent());
  }
}
```

`AuthService.cancelAuthentication(UUID, AuthLease)` delegates to `removeIfState(uuid, lease, AuthSession.State.AUTHENTICATING)`. `AuthService.closeConnection(UUID, AuthLease)` removes any auth session owned by that exact lease. `AuthLoginBarrier.enforceAndClose` uses it at `LoginEvent` LAST when the final result is denied, including `GUEST` and `AUTHENTICATED`; this closes connection-scoped auth state without removing the flow or UUID owner. `DisconnectEvent` later calls `AuthFlowIndex.close(uuid, player)` for the exact player and then repeats exact-lease `closeConnection` as idempotent cleanup. Admission denial, transfer completion, wrong target and auth terminal state do not release UUID ownership; only exact disconnect does.

TOTP enablement writes the generated secret and encoded recovery-code hash array with one repository `UPDATE`; only an update affecting the exact user may publish the one-time plaintext credentials. A database failure must leave both columns unchanged, so an account cannot become half-enabled without the secret and recovery codes reaching the player.

Recovery codes are generated as plaintext only for the one-time issuance response. Persist each code as its own BCrypt hash in a JSON array. `verifyRecoveryCode(AuthLease, UUID, String)` must first confirm the exact lease is still `AUTHENTICATING`, match one hash, remove only that hash, and persist the replacement array with repository compare-and-set against the previously read JSON. A CAS conflict retries from a fresh read; a consumed code cannot be accepted again, and a stale lease cannot consume a replacement connection's code.

- [ ] **Step 3: Write failing LoginEvent and target-binding adapter tests**

Create package-private `AuthAdmission.java` so event admission can be tested without constructing Velocity events:

```java
package io.github.addxiaoyi.starx.velocity.module.auth;

import io.github.addxiaoyi.starx.common.auth.AuthResult;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;

final class AuthAdmission<P, S> {
  enum Decision { ENTER_UWORLD, ROUTE_PREMIUM, DENIED }

  @FunctionalInterface
  interface CheckedBooleanSupplier { boolean get() throws Exception; }

  @FunctionalInterface
  interface CheckedAuthAttempt { AuthResult run() throws Exception; }

  record Result(Decision decision, Component reason) {}

  private final AuthFlowIndex<P, S, Component> flows;
  private final Component duplicateOwner;
  private final Component authError;
  private final Component targetUnavailable;

  AuthAdmission(AuthFlowIndex<P, S, Component> flows, Component duplicateOwner,
                Component authError, Component targetUnavailable) {
    this.flows = Objects.requireNonNull(flows, "flows");
    this.duplicateOwner = Objects.requireNonNull(duplicateOwner, "duplicateOwner");
    this.authError = Objects.requireNonNull(authError, "authError");
    this.targetUnavailable = Objects.requireNonNull(targetUnavailable, "targetUnavailable");
  }

  Result begin(UUID id, P player, CheckedBooleanSupplier premium,
               CheckedAuthAttempt autoLogin, Runnable publishStart, S target) {
    if (this.flows.begin(id, player, this.duplicateOwner)
        == AuthFlowIndex.BeginResult.DUPLICATE) {
      return new Result(Decision.DENIED, this.duplicateOwner);
    }
    try {
      if (premium.get()) {
        if (target == null) {
          this.flows.deny(player);
          return new Result(Decision.DENIED, this.targetUnavailable);
        }
        AuthResult auth = autoLogin.run();
        if (!auth.success() || !this.flows.route(player, target)) {
          this.flows.deny(player);
          return new Result(Decision.DENIED, Component.text(auth.message()));
        }
        return new Result(Decision.ROUTE_PREMIUM, Component.empty());
      }
      publishStart.run();
      if (!this.flows.awaitPassword(player)) {
        this.flows.deny(player);
        return new Result(Decision.DENIED, this.authError);
      }
      return new Result(Decision.ENTER_UWORLD, Component.empty());
    } catch (Exception error) {
      this.flows.deny(player);
      return new Result(Decision.DENIED, this.authError);
    }
  }
}
```

Create package-private `AuthLoginBarrier.java` to preserve the first StarX or external denial through `LoginEvent` LAST. It marks a still-allowed connection as denied when an external plugin denied it. On a final denial, `enforceAndClose` removes only the auth session matching the flow's exact lease; it never closes the flow or releases UUID ownership:

```java
package io.github.addxiaoyi.starx.velocity.module.auth;

import io.github.addxiaoyi.starx.common.auth.AuthService;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;

final class AuthLoginBarrier {
  private AuthLoginBarrier() {
  }

  static <P, S> Optional<Component> enforce(
      AuthFlowIndex<P, S, Component> flows,
      P player,
      boolean finalAllowed,
      Optional<Component> externalReason,
      Component fallback
  ) {
    Objects.requireNonNull(flows, "flows");
    Objects.requireNonNull(player, "player");
    Objects.requireNonNull(externalReason, "externalReason");
    Objects.requireNonNull(fallback, "fallback");

    Optional<Component> starxReason = flows.denial(player);
    if (starxReason.isPresent()) {
      return starxReason;
    }
    if (finalAllowed) {
      return Optional.empty();
    }
    Component reason = externalReason.orElse(fallback);
    flows.deny(player, reason);
    return Optional.of(reason);
  }

  static <P, S> Optional<Component> enforceAndClose(
      AuthFlowIndex<P, S, Component> flows,
      P player,
      UUID playerId,
      boolean finalAllowed,
      Optional<Component> externalReason,
      Component fallback,
      AuthService authService
  ) {
    Objects.requireNonNull(playerId, "playerId");
    Objects.requireNonNull(authService, "authService");

    Optional<Component> reason = enforce(
        flows, player, finalAllowed, externalReason, fallback);
    if (reason.isEmpty()) {
      return reason;
    }
    flows.lease(player).ifPresent(lease ->
        authService.closeConnection(playerId, lease));
    return reason;
  }
}
```

Routing remains in `AuthFlowIndex`: `target`, `allowsBackend`, and `connected` use the exact `RegisteredServer` object. None of these methods releases UUID ownership; the exact disconnect path remains the sole release point.

Add this read method to `AuthFlowIndex`; it never removes the route barrier or owner:

```java
Optional<S> target(P player) {
  Flow<S, D> flow = this.flow(player);
  return flow == null
      ? Optional.empty()
      : Optional.ofNullable(flow.state().get().target());
}
```

Create `AuthLoginBarrierTest.java` for both directions of the LAST-order barrier: an earlier StarX denial cannot be overwritten by a later allow, and an external denial is copied into the connection flow. Cover `enforceAndClose` with an exact-lease `GUEST` session, an exact-lease `AUTHENTICATED` session, a `finalAllowed` result that leaves the session open, and a stale flow lease that cannot close a replacement session. Every denied case must retain the flow/UUID owner until `close(uuid, exactPlayer)` runs. Keep exact-target routing tests in `AuthFlowRouteTest`: `COMPLETE` makes `requiresAuth` false, `WRONG_TARGET` keeps it true, and neither result releases the UUID owner before disconnect.

Create `AuthAdmissionTest.java`:

```java
package io.github.addxiaoyi.starx.velocity.module.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.common.auth.AuthResult;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

final class AuthAdmissionTest {
  private static final Component DUPLICATE = Component.text("duplicate");
  private static final Component AUTH_ERROR = Component.text("auth error");
  private static final Component TARGET_MISSING = Component.text("target missing");

  @Test
  void duplicateOwnerCannotReplaceTheFirstFlow() {
    UUID id = UUID.randomUUID();
    Object first = new Object();
    Object second = new Object();
    AuthFlowIndex<Object, Object, Component> flows = new AuthFlowIndex<>();
    AuthAdmission<Object, Object> admission = admission(flows);
    assertEquals(AuthAdmission.Decision.ENTER_UWORLD,
        admission.begin(id, first, () -> false, () -> AuthResult.success("unused"), () -> {}, null).decision());
    AuthAdmission.Result denied = admission.begin(
        id, second, () -> false, () -> AuthResult.success("unused"), () -> {}, null);
    assertEquals(AuthAdmission.Decision.DENIED, denied.decision());
    assertSame(DUPLICATE, denied.reason());
    assertTrue(flows.requiresAuth(first));
    assertTrue(flows.requiresAuth(second));
  }

  @Test
  void premiumResolverFailureRetainsOwnerUntilDisconnect() {
    UUID id = UUID.randomUUID();
    Object player = new Object();
    AuthFlowIndex<Object, Object, Component> flows = new AuthFlowIndex<>();
    AuthAdmission.Result result = admission(flows).begin(
        id, player, () -> { throw new IllegalStateException("resolver"); },
        () -> AuthResult.success("unused"), () -> {}, new Object());
    assertEquals(AuthAdmission.Decision.DENIED, result.decision());
    assertSame(AUTH_ERROR, result.reason());
    assertTrue(flows.requiresAuth(player));
    assertEquals(AuthFlowIndex.BeginResult.DUPLICATE,
        flows.begin(id, new Object(), DUPLICATE));
  }

  @Test
  void eventPublisherFailureRetainsOwnerUntilDisconnect() {
    UUID id = UUID.randomUUID();
    Object player = new Object();
    AuthFlowIndex<Object, Object, Component> flows = new AuthFlowIndex<>();
    AuthAdmission.Result result = admission(flows).begin(
        id, player, () -> false, () -> AuthResult.success("unused"),
        () -> { throw new IllegalStateException("event bus"); }, null);
    assertEquals(AuthAdmission.Decision.DENIED, result.decision());
    assertTrue(flows.requiresAuth(player));
  }

  @Test
  void offlineAdmissionEntersUworldAndDeniesBackends() {
    Object player = new Object();
    AuthFlowIndex<Object, Object, Component> flows = new AuthFlowIndex<>();
    AuthAdmission.Result result = admission(flows).begin(
        UUID.randomUUID(), player, () -> false,
        () -> AuthResult.success("unused"), () -> {}, null);
    assertEquals(AuthAdmission.Decision.ENTER_UWORLD, result.decision());
    assertTrue(flows.requiresInput(player));
    assertFalse(flows.allowsBackend(player, new Object()));
  }

  @Test
  void premiumRouteUsesTheExactTargetObject() {
    Object player = new Object();
    String target = new String("lobby");
    String equalButDifferent = new String("lobby");
    AuthFlowIndex<Object, String, Component> flows = new AuthFlowIndex<>();
    AuthAdmission<Object, String> admission = admission(flows);
    AuthAdmission.Result result = admission.begin(
        UUID.randomUUID(), player, () -> true,
        () -> AuthResult.success("ok"), () -> {}, target);
    assertEquals(AuthAdmission.Decision.ROUTE_PREMIUM, result.decision());
    assertTrue(flows.allowsBackend(player, target));
    assertFalse(flows.allowsBackend(player, equalButDifferent));
  }

  @Test
  void missingPremiumTargetFailsClosed() {
    Object player = new Object();
    AuthFlowIndex<Object, Object, Component> flows = new AuthFlowIndex<>();
    AuthAdmission.Result result = admission(flows).begin(
        UUID.randomUUID(), player, () -> true,
        () -> AuthResult.success("ok"), () -> {}, null);
    assertEquals(AuthAdmission.Decision.DENIED, result.decision());
    assertSame(TARGET_MISSING, result.reason());
    assertTrue(flows.requiresAuth(player));
  }

  private static <P, S> AuthAdmission<P, S> admission(
      AuthFlowIndex<P, S, Component> flows) {
    return new AuthAdmission<>(flows, DUPLICATE, AUTH_ERROR, TARGET_MISSING);
  }
}
```

- [ ] **Step 4: Migrate `AuthModule`**

The module receives `UworldRuntime`, resolves `uworld.auth.target-server` exactly once during enable, and fails enable with target-server context when it is absent. It creates one auth `UworldHandle`, enters it on `PostLoginEvent`, stores that same `RegisteredServer` object in `AuthFlowIndex`, and passes the same object to `UworldFlowSession.complete`. Remove the 500 ms unguarded fallback and all independent Limbo session maps.

- [ ] **Step 5: Implement exact event order**

Implement `LoginEvent(FIRST)`, `LoginEvent(LAST)`, `PostLoginEvent`, `PlayerChooseInitialServerEvent(LAST)`, `ServerPreConnectEvent(LAST)`, `ServerConnectedEvent`, and `DisconnectEvent` in that order. FIRST claims the connection and offloads premium admission; LAST calls `AuthLoginBarrier.enforceAndClose`, reapplies the saved StarX denial or records an external denial, and on denial closes only the auth session matching that flow's lease. It does not release the flow or UUID owner. Both Auth and Uworld gates receive `event.getResult().getServer().orElse(null)` after earlier handlers have run; either may deny, neither substitutes another server. Premium initial-server selection keeps the route barrier. Exact `ServerConnectedEvent` changes the auth phase to `COMPLETE`; wrong target changes it to `DENIED` and disconnects. Both terminal phases retain their flow, lease, and UUID owner until `DisconnectEvent` calls exact `close(uuid, player)` and then idempotent `closeConnection(uuid, lease)` cleanup.

- [ ] **Step 6: Run auth and runtime tests**

Run: `scripts/invoke-gradle-ascii.ps1 :starx-plugins:starx-common:test :starx-plugins:starx-velocity:test --no-daemon --console=plain`

Expected: credential, owner, Uworld, route, timeout, and stale-event tests all pass.

- [ ] **Step 7: Commit in a real worktree**

```bash
git add starx-plugins/starx-common starx-plugins/starx-velocity
git commit -m "feat(auth): run authentication through Uworld"
```

### Task 8: Plugin Lifecycle and Diagnostic Flow

**Files:**
- Create: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/uworld/UworldModule.java`
- Create: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/uworld/UworldDiagnostics.java`
- Create: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/uworld/UworldDiagnosticsState.java`
- Modify: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/StarxVelocityPlugin.java`
- Modify: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/ModuleManager.java`
- Rename: `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/proxytools/LimboHubModule.java` to `starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/module/proxytools/HubCommandModule.java`
- Test: `starx-plugins/starx-velocity/src/test/java/io/github/addxiaoyi/starx/velocity/module/ModuleManagerTest.java`
- Test: `starx-plugins/starx-velocity/src/test/java/io/github/addxiaoyi/starx/velocity/module/uworld/UworldDiagnosticsStateTest.java`
- Test: `starx-plugins/starx-velocity/src/test/java/io/github/addxiaoyi/starx/velocity/module/uworld/UworldRuntimeIndependenceTest.java`

- [ ] **Step 1: Write failing reverse-shutdown test**

Add a package-private `ModuleManager(Predicate<String> enabled)` constructor; the public constructor delegates with `config::isModuleEnabled`. Track only successfully enabled modules. Create `ModuleManagerTest.java`:

```java
package io.github.addxiaoyi.starx.velocity.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ModuleManagerTest {

  @Test
  void enablesForwardAndDisablesInReverse() {
    List<String> events = new ArrayList<>();
    ModuleManager manager = new ModuleManager(name -> true);
    manager.register(new FakeModule("runtime", events, false));
    manager.register(new FakeModule("auth", events, false));
    manager.register(new FakeModule("diagnostics", events, false));
    manager.enableAll();
    manager.disableAll();
    assertEquals(List.of(
        "enable:runtime", "enable:auth", "enable:diagnostics",
        "disable:diagnostics", "disable:auth", "disable:runtime"), events);
  }

  @Test
  void shutdownContinuesAndAggregatesModuleFailures() {
    List<String> events = new ArrayList<>();
    ModuleManager manager = new ModuleManager(name -> true);
    manager.register(new FakeModule("runtime", events, false));
    manager.register(new FakeModule("auth", events, true));
    manager.register(new FakeModule("diagnostics", events, false));
    manager.enableAll();
    IllegalStateException error = assertThrows(IllegalStateException.class, manager::disableAll);
    assertEquals(1, error.getSuppressed().length);
    assertEquals(List.of(
        "enable:runtime", "enable:auth", "enable:diagnostics",
        "disable:diagnostics", "disable:auth", "disable:runtime"), events);
  }

  private record FakeModule(String name, List<String> events, boolean failDisable)
      implements VelocityModule {
    public void onEnable() { this.events.add("enable:" + this.name); }
    public void onDisable() {
      this.events.add("disable:" + this.name);
      if (this.failDisable) {
        throw new IllegalStateException("disable failed: " + this.name);
      }
    }
  }
}
```

Add a plugin-shutdown adapter test asserting `moduleManager.disableAll()` completes or reports its aggregate before `databaseManager.close()` is invoked.

- [ ] **Step 2: Implement reverse shutdown and plugin wiring**

Create/register Uworld before Auth, bind Auth to `UworldRuntime`, register diagnostics after Auth, and expose `public UworldRuntime uworld()` from `StarxVelocityPlugin`. `ModuleManager.disableAll()` copies and clears the successfully-enabled list, reverses the copy, catches each failure, and after all attempts throws one `IllegalStateException("One or more modules failed to stop")` with module-named suppressed exceptions. `UworldModule.onDisable()` rejects new entries, closes every handle and active session, waits for their completion, and finally closes the factory. The proxy shutdown controller logs the aggregate once and closes database/proxy resources afterward.

- [ ] **Step 3: Write failing diagnostics tests**

Implement `UworldDiagnosticsState<P,S>` as a `ConcurrentMap<P,S>` with `remember(P,S)`, `S takePreviousOr(P,S fallback)`, and `remove(P)`; `takePreviousOr` consumes the saved server exactly once. `UworldDiagnosticsStateTest` covers previous-server return, fallback return, and exact-player cleanup.

Add command-adapter tests for lazy world creation, permission denial, disabled `test/leave`, always-available privileged `status`, chat and move callbacks, timeout, wrong target, and unavailable target. `UworldRuntimeIndependenceTest` constructs `EmbeddedUworldRuntime` with a fake low-level factory and no `AuthService`, repository, datasource, or database manager, then creates `auth` and `diagnostics` simultaneously and enters separate fake players.

- [ ] **Step 4: Implement `/uworld` commands**

Register one Brigadier/SimpleCommand root with subcommands `status`, `test`, and `leave`. All three require `starx.uworld.diagnostics`; `status` is always registered, while `test/leave` return a disabled message unless diagnostics are enabled. `test` lazily creates owner `starx.diagnostics`, world `diagnostics`, captures `player.getCurrentServer()` before entry, and calls public runtime APIs only. `leave` calls the current public session's `complete(previousOrHub)` and never references `StarxUworldFactory` or internal session classes.

- [ ] **Step 5: Remove old Limbo transport implementation**

After all references are migrated, delete the three old `module/limbo` files and prove with `rg` that production code has no `LimboModule`, `LimboTransportSession`, or `LimboTransferState` references.

- [ ] **Step 6: Run lifecycle and diagnostics tests**

Run: `scripts/invoke-gradle-ascii.ps1 :starx-plugins:starx-velocity:test --tests '*ModuleManagerTest' --tests '*UworldDiagnostics*' --no-daemon --console=plain`

Expected: reverse lifecycle and diagnostic flow tests pass.

- [ ] **Step 7: Commit in a real worktree**

```bash
git add starx-plugins/starx-velocity
git commit -m "feat(uworld): wire lifecycle and diagnostic flow"
```

### Task 9: Packaging, Licensing, and Documentation

**Files:**
- Modify: `build.gradle.kts`
- Modify: `starx-plugins/starx-limbo-api/build.gradle.kts`
- Modify: `starx-plugins/starx-standalone-limbo/build.gradle.kts`
- Modify: `starx-plugins/starx-velocity/build.gradle.kts`
- Modify: `starx-plugins/starx-velocity/src/main/resources/velocity-plugin.json`
- Modify: `starx-plugins/starx-standalone-limbo/UPSTREAM.md`
- Create: `LICENSES/MIT.txt`, `LICENSES/AGPL-3.0.txt`, `NOTICE`
- Create: `starx-plugins/starx-velocity/README.md`
- Create: `starx-plugins/starx-standalone-limbo/README.md`
- Create: `docs/UWORLD_CONFIGURATION.md`, `docs/UWORLD_DEVELOPMENT.md`, `docs/UWORLD_ACCEPTANCE.md`
- Test: `starx-plugins/starx-velocity/src/test/java/io/github/addxiaoyi/starx/velocity/PluginDescriptorVersionTest.java`

- [ ] **Step 1: Add a failing resource/version test**

Read processed `velocity-plugin.json` in `PluginDescriptorVersionTest` and assert its version equals the test system property `starx.project.version`, plugin id remains `starx`, and description names Uworld without claiming a second plugin. Configure the test task with `systemProperty("starx.project.version", project.version.toString())` so the assertion compares two independent inputs.

- [ ] **Step 2: Make Gradle version authoritative**

Set the source descriptor version to the exact token `${version}` and configure `processResources { filesMatching("velocity-plugin.json") { expand("version" to project.version) } }`. Keep the final archive name `starx-velocity.jar`.

- [ ] **Step 3: Add exact license texts and NOTICE**

Extract the canonical blobs without shell redirection, then verify their Git object IDs:

```powershell
$root = (Resolve-Path '.').Path
$commit = '839773cfd406458cf247fbfd64ed492926f921b7'
New-Item -ItemType Directory -Force -Path (Join-Path $root 'LICENSES') | Out-Null
git -C LimboAPI-source show --output="$root/LICENSES/AGPL-3.0.txt" "$commit`:LICENSE"
git -C LimboAPI-source show --output="$root/LICENSES/MIT.txt" "$commit`:api/LICENSE"
if ((git -C LimboAPI-source rev-parse "$commit`:LICENSE") -ne
    (git hash-object LICENSES/AGPL-3.0.txt)) { throw 'AGPL license blob mismatch' }
if ((git -C LimboAPI-source rev-parse "$commit`:api/LICENSE") -ne
    (git hash-object LICENSES/MIT.txt)) { throw 'MIT license blob mismatch' }
```

`NOTICE` must name `https://github.com/Elytrium/LimboAPI`, commit `839773cfd406458cf247fbfd64ed492926f921b7`, runtime AGPL-3.0, low-level API MIT, vendored artifact SHA-256 `18AC6287D413234C4FC317267A6D5DBF978ADAE8BF3F098A1248966BF2C32CE9`, the five protected overrides, StarX package relocation/modifications, and the AGPL-3.0 Uworld product API boundary.

- [ ] **Step 4: Write operator and developer docs**

Write the documents with these fixed contents:

- `starx-plugins/starx-velocity/README.md`: Java 21 and Velocity build 606 prerequisites; `scripts/invoke-gradle-ascii.ps1 :starx-plugins:starx-velocity:shadowJar`; the sole artifact `starx-plugins/starx-velocity/build/libs/starx-velocity.jar`; backup, external LimboAPI removal, copy, full Velocity restart, success logs, and rollback commands. Include the exact `velocity.toml` `[servers] lobby = "127.0.0.1:25566"` example and state that the backend must be running.
- `starx-plugins/starx-standalone-limbo/README.md`: explicitly say it is an embedded library and cannot be copied into `plugins/`; document one core owner per JVM, no hot reload, handle-before-factory close order, protocol floor 776, and the low-level compatibility boundary.
- `docs/UWORLD_CONFIGURATION.md`: paste the complete default block from Task 5, every range/default, loader/file extension mapping, both core paths, new-over-old precedence, a compatibility window lasting the complete current Uworld major version, and fail-closed behavior.
- `docs/UWORLD_DEVELOPMENT.md`: include one compiling public-API example that creates `auth` and `diagnostics`, generates different blocks, enters two players, handles chat/move, uses `session.execute` after offload, transfers to an exact target, and closes handles in reverse order.
- `docs/UWORLD_ACCEPTANCE.md`: separate automated, cold-start, diagnostics-client, and authentication-client tables with columns `Case`, `Precondition`, `Action`, `Expected`, `Observed`, `Evidence`, `Timestamp`, and `Status`; allowed status values are `PASS`, `FAIL`, and `UNVERIFIED`.

For Linux deployment, use documented variables `VELOCITY_HOME` and `RELEASE_JAR`, stop the service, copy `$VELOCITY_HOME/plugins/starx-velocity.jar` to a timestamped backup, remove any external LimboAPI JAR, install the new artifact, start Velocity, and restore the backup on a failed cold-start gate. Do not hard-code a production service name.

- [ ] **Step 5: Update upstream synchronization docs**

Keep all true LimboAPI names in `starx-plugins/starx-standalone-limbo/UPSTREAM.md`, add the Uworld wrapper context, upstream URL, local license links, `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/sync-starx-limbo.Tests.ps1`, and the five protected overrides.

- [ ] **Step 6: Run documentation/static scans**

In `scripts/tests/verify-uworld.ps1`, construct the unfinished-marker patterns from string fragments so the checker source does not contain a marker it searches for. Scan only production Java and the five delivered Uworld/operator documents; exclude `docs/superpowers`, generated output, vendored mappings, and build directories. Scan the quoted module-id literal rather than Java package names. Allow that literal only in `ConfigLoader.java`, `StarxConfig.java`, and `UworldConfig.java`; fail on every other production occurrence. Allow `StarxLimboFactory` only in its compatibility source, migration documentation, and `UPSTREAM.md`. Separately fail when the standalone README claims it can be installed as a Velocity plugin or when a local Markdown link target is absent.

- [ ] **Step 7: Commit in a real worktree**

```bash
git add build.gradle.kts starx-plugins docs LICENSES NOTICE
git commit -m "docs(uworld): document deployment API and licensing"
```

### Task 10: Verification Script, Fresh Build, and Runtime Smoke

**Files:**
- Create: `scripts/tests/verify-uworld.ps1`
- Create: `scripts/tests/smoke-uworld.ps1`
- Create: `velocity-test/fixtures/uworld/velocity.toml`
- Create: `velocity-test/fixtures/uworld/config-default.yml`
- Create: `velocity-test/fixtures/uworld/config-diagnostics.yml`

- [ ] **Step 1: Write the failing verification script assertions**

The script must fail before the rename because it requires:

```text
UworldModule.class = 1
StarxUworldFactory.class = 1
StarxLimboFactory compatibility class = 1
mapping files = 26
relocated FastPrepare entries = 10
relocated Elytrium Commons entries = 33
Velocity plugin descriptors = 1
plugin id = starx
external net/elytrium/limboapi classes = 0
nested LimboAPI jars = 0
quoted legacy module id outside migration allowlist = 0
arbitrary first-server fallback = 0
unfinished implementation markers in scoped deliverables = 0
missing local documentation links = 0
```

The script allowlists the retained `io.github.addxiaoyi.starx.limbo` packages, `StarxLimboFactory.java`, `UPSTREAM.md`, and quoted legacy module/config keys in `ConfigLoader.java`, `StarxConfig.java`, and `UworldConfig.java`. It must print every non-allowlisted file and line before failing; a global substring count is not acceptable.

- [ ] **Step 2: Run RED**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/verify-uworld.ps1`

Expected: non-zero because Uworld classes do not yet exist.

- [ ] **Step 3: Complete the fresh build**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/sync-starx-limbo.Tests.ps1
scripts/invoke-gradle-ascii.ps1 `
  :starx-plugins:starx-limbo-api:clean `
  :starx-plugins:starx-common:clean `
  :starx-plugins:starx-standalone-limbo:clean `
  :starx-plugins:starx-velocity:clean `
  :starx-plugins:starx-limbo-api:test `
  :starx-plugins:starx-common:test `
  :starx-plugins:starx-standalone-limbo:test `
  :starx-plugins:starx-velocity:test `
  :starx-plugins:starx-velocity:compileJava `
  :starx-plugins:starx-velocity:build `
  :starx-plugins:starx-velocity:shadowJar `
  --rerun-tasks --no-parallel --no-daemon --console=plain
```

Expected: `BUILD SUCCESSFUL`; every requested task executed.

- [ ] **Step 4: Parse JUnit XML structurally**

Parse these exact directories with `[xml]`, not regular expressions:

```text
starx-plugins/starx-limbo-api/build/test-results/test/TEST-*.xml
starx-plugins/starx-common/build/test-results/test/TEST-*.xml
starx-plugins/starx-standalone-limbo/build/test-results/test/TEST-*.xml
starx-plugins/starx-velocity/build/test-results/test/TEST-*.xml
```

Fail when any project has zero suites or when aggregate failures/errors are non-zero. Print `suites`, `tests`, `failures`, `errors`, and `skipped` for each project and the total.

- [ ] **Step 5: Run GREEN verification**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/verify-uworld.ps1`

Expected: `UWORLD_GATE=PASS`, artifact path, size, SHA-256, mapping/relocation counts, and zero forbidden patterns.

- [ ] **Step 6: Cold-start Velocity 3.5 build 606**

Implement `smoke-uworld.ps1` with required parameters `-VelocityJar`, `-PluginJar`, and `-Profile Default|Diagnostics`. It creates a new directory under the system temp folder, copies only the selected tracked fixtures and final JAR, starts Java 21 with redirected stdout/stderr, and polls for at most 45 seconds. `config-default.yml` binds HTTP to `127.0.0.1:8790`, enables Uworld/auth, disables diagnostics, and uses the full Task 5 schema. `velocity.toml` registers `lobby = "127.0.0.1:25566"` without arbitrary `try` fallback.

Run the default profile:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/smoke-uworld.ps1 `
  -VelocityJar velocity-test/velocity-3.5.0-SNAPSHOT-606.jar `
  -PluginJar starx-plugins/starx-velocity/build/libs/starx-velocity.jar `
  -Profile Default
```

Require log lines `Uworld core initialized`, `Uworld runtime ready`, `Generated a 11x11 Uworld authentication platform`, and `Authentication Uworld ready`. Use `System.Net.Http.HttpClient` and require status `404` from `http://127.0.0.1:8790/`. In `finally`, terminate only the captured process, wait for exit, assert ports `25580` and `8790` have no listeners, and remove the temporary directory. Print `UWORLD_SMOKE=PASS profile=Default velocity_build=606` only after every assertion.

- [ ] **Step 7: Exercise the independent diagnostic flow with a real client**

Run `smoke-uworld.ps1` with `-Profile Diagnostics` to validate startup with the tracked diagnostics-enabled config. Cold start requires the same four core/auth startup lines as Default and must not require a diagnostics-world-ready line: the diagnostics handle is created lazily only when an authorized player runs `/uworld test`. Then use a real permitted client to run `/uworld status`, `/uworld test`, send one chat message, move at least one block, and run `/uworld leave`. Record exact previous server, expected return server, outcome, matching log lines, client version, timestamp, and evidence path. Repeat with no previous server, timeout, wrong target, and unavailable target. Restore `config-default.yml` before production deployment.

- [ ] **Step 8: Execute the authentication client matrix**

Use the acceptance table from Task 9. Record offline registration, password login, TOTP, recovery code, premium auto-login, synchronized duplicate-UUID connections, pending backend denial, exact hub routing, kick, connection-future failure, transfer timeout, auth timeout, and shutdown with an active player. Each row includes account type, client version, initial server, expected target, observed outcome, proxy log evidence, timestamp, and status. Every unexecuted row remains `UNVERIFIED`.

- [ ] **Step 9: Synchronize CodeGraph and audit completion**

Run `codegraph sync .`, then `codegraph status .`; require `Pending Changes` to be empty. Add a six-row completion table to `docs/UWORLD_ACCEPTANCE.md`, one row per design completion item, with exact test/build/runtime/document evidence or `UNVERIFIED`.

- [ ] **Step 10: Commit in a real worktree**

```bash
git add scripts/tests/verify-uworld.ps1 scripts/tests/smoke-uworld.ps1 velocity-test/fixtures/uworld docs/UWORLD_ACCEPTANCE.md
git commit -m "test(uworld): add packaging and runtime acceptance gates"
```
