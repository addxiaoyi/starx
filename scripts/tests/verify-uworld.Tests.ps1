[CmdletBinding()]
param(
  [switch] $ServiceIdentityOnly,
  [switch] $RollbackFailFastOnly,
  [switch] $DocumentationContractOnly
)

$SelectedModes = @(
  $ServiceIdentityOnly,
  $RollbackFailFastOnly,
  $DocumentationContractOnly
) | Where-Object { $_ }
if ($SelectedModes.Count -gt 1) {
  throw "ServiceIdentityOnly, RollbackFailFastOnly, and DocumentationContractOnly cannot be combined"
}

$ErrorActionPreference = "Stop"

$Root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$Gate = Join-Path $Root "scripts\verify-uworld.ps1"

if (-not (Test-Path -LiteralPath $Gate -PathType Leaf)) {
  throw "Uworld verification gate is missing: $Gate"
}

$PowerShell = (Get-Process -Id $PID).Path
$Output = & $PowerShell -NoProfile -ExecutionPolicy Bypass -File $Gate -MetadataOnly 2>&1
if ($LASTEXITCODE -ne 0) {
  throw "Uworld metadata gate failed with exit $LASTEXITCODE`n$($Output -join "`n")"
}

$Text = $Output -join "`n"
if ($Text -notmatch "UWORLD_METADATA_GATE=PASS") {
  throw "Uworld metadata gate did not print its PASS marker`n$Text"
}

$TempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("starx-uworld-gate-" + [guid]::NewGuid().ToString("N"))
$TempPrefix = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd("\") + "\"
$Log = Join-Path $TempRoot "calls.log"
$HadLogVariable = Test-Path Env:UWORLD_VERIFY_TEST_LOG
$PreviousLogVariable = $env:UWORLD_VERIFY_TEST_LOG

function Write-AsciiFile([string] $Path, [string] $Content) {
  [System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($Path)) | Out-Null
  [System.IO.File]::WriteAllText($Path, $Content, [System.Text.Encoding]::ASCII)
}

function Set-WindowsDeploymentFence(
  [string] $Text,
  [string] $OpenFence,
  [string] $CloseFence
) {
  $WithOpen = $Text -replace
    '(?m)(<!-- UWORLD_WINDOWS_DEPLOYMENT -->\r?\n)```powershell$',
    ('$1' + $OpenFence)

  return $WithOpen -replace
    '(?m)^```(\r?\n<!-- /UWORLD_WINDOWS_DEPLOYMENT -->)$',
    ($CloseFence + '$1')
}

function Set-WindowsDoctorCalls(
  [string] $Text,
  [string] $Identity,
  [string] $Environment
) {
  return $Text `
    -replace '(?m)^Assert-UworldJarIdentity \$CurrentJar$',
      $Identity `
    -replace '(?m)^Assert-UworldEnvironment \$CandidateJar$',
      $Environment
}

function Set-LinuxDoctorCalls(
  [string] $Text,
  [string] $Identity,
  [string] $Environment
) {
  return $Text `
    -replace '(?m)^assert_uworld_jar_identity "\$current_jar"$',
      $Identity `
    -replace '(?m)^assert_uworld_environment "\$RELEASE_JAR"$',
      $Environment
}

function Write-DocumentationFixture(
  [string] $FixtureRoot,
  [string] $CandidateSha,
  [long] $CandidateSize,
  [string] $InstalledSha
) {
  $Documents = [ordered]@{
    "starx-plugins\README.md" = @'
# StarX Plugins

Uworld is the embedded managed virtual-world runtime in `starx-velocity.jar`.
The only production deployment artifact is `starx-velocity.jar`.

## Product goals

Uworld provides managed virtual worlds, player sessions, timeouts, fail-closed cleanup, and exact-target transfer for isolated StarX flows.

## Product non-goals

Uworld is not a second plugin, does not use external LimboAPI, does not support hot reload or arbitrary backend fallback, and does not make a consumer integrated or real-client verified by itself.

| Consumer | Integration | Real client |
|---|---|---|
| Auth | integrated | UNVERIFIED |
| Diagnostics | integrated, default off | UNVERIFIED |
| Queue | extension only, not integrated | UNVERIFIED |
| Maintenance | extension only, not integrated | UNVERIFIED |
| Tutorial | planned, module absent | UNVERIFIED |
'@
    "starx-plugins\starx-velocity\README.md" = @'
# StarX Velocity

Deploy only `starx-velocity.jar`. See the [environment guide](../../docs/UWORLD_ENVIRONMENT.md).
'@
    "starx-plugins\starx-standalone-limbo\README.md" = @'
# Standalone Uworld library

This library is not a Velocity plugin. The only production deployment is `starx-velocity.jar`.
Protocol 776 is the minimum Velocity `ProtocolVersion.MAXIMUM_VERSION` capability; it is not a Minecraft client minimum protocol.
'@
    "starx-plugins\starx-standalone-limbo\UPSTREAM.md" = "# Upstream`r`n"
    "docs\UWORLD_CONFIGURATION.md" = @'
# Uworld configuration

```yaml
modules:
  starx.auth:
    enabled: true
  starx.uworld:
    enabled: true
uworld:
  enabled: true
```

| Auth module | Uworld module | Uworld runtime | Result |
|---|---|---|---|
| true | true | true | supported Auth Uworld |
| false | true | true | supported diagnostics/API-only Uworld |
| true | false | any | FAIL_CLOSED |
| true | true | false | FAIL_CLOSED |
'@
    "docs\UWORLD_DEVELOPMENT.md" = @'
# Uworld development

StarX business modules consume `UworldRuntime`. `StarxUworldFactory` is an internal lifecycle detail created and closed only by `UworldModule`; it is not a service API for external Velocity plugins. The `io.github.addxiaoyi.starx.limbo` package and raw Limbo types must not be injected, returned, or cached by consumers.
'@
    "docs\UWORLD_ACCEPTANCE.md" = @"
# Uworld acceptance

<!-- UWORLD_CURRENT_CANDIDATE -->
status=UNVERIFIED
sha256=$CandidateSha
size=$CandidateSize
<!-- /UWORLD_CURRENT_CANDIDATE -->

<!-- UWORLD_AUTOMATIC_EVIDENCE -->
status=PASS
starx-limbo-api: 1 tests, 0 failures, 0 errors, 0 skipped
starx-common: 1 tests, 0 failures, 0 errors, 0 skipped
starx-standalone-limbo: 1 tests, 0 failures, 0 errors, 0 skipped
starx-velocity: 1 tests, 0 failures, 0 errors, 0 skipped
aggregate: 4 suites, 4 tests, 0 failures, 0 errors, 0 skipped
ARTIFACT_SIZE=$CandidateSize
ARTIFACT_SHA256=$CandidateSha
<!-- /UWORLD_AUTOMATIC_EVIDENCE -->

<!-- UWORLD_COLD_START_EVIDENCE -->
status=UNVERIFIED
artifact_sha256=$CandidateSha
<!-- /UWORLD_COLD_START_EVIDENCE -->

<!-- UWORLD_LIVE_ENVIRONMENT_EVIDENCE -->
status=FAIL
candidate_hash_check=FAIL
candidate_sha256=$CandidateSha
installed_sha256=$InstalledSha
installed_path=velocity-test/plugins/starx-velocity.jar
doctor_result=UWORLD_ENVIRONMENT=FAIL
timestamp=2026-07-15T18:35:00+08:00
<!-- /UWORLD_LIVE_ENVIRONMENT_EVIDENCE -->

<!-- UWORLD_REAL_CLIENT_MATRIX -->
| Case | Precondition | Action | Expected | Observed | Evidence | Timestamp | Status |
|---|---|---|---|---|---|---|---|
| D01 diagnostics status | ready | run | expected | not-recorded | none | not-recorded | UNVERIFIED |
| D02 diagnostics enter | ready | run | expected | not-recorded | none | not-recorded | UNVERIFIED |
| D03 diagnostics platform | ready | run | expected | not-recorded | none | not-recorded | UNVERIFIED |
| D04 diagnostics callbacks | ready | run | expected | not-recorded | none | not-recorded | UNVERIFIED |
| D05 diagnostics previous | ready | run | expected | not-recorded | none | not-recorded | UNVERIFIED |
| D06 diagnostics fallback | ready | run | expected | not-recorded | none | not-recorded | UNVERIFIED |
| D07 diagnostics timeout | ready | run | expected | not-recorded | none | not-recorded | UNVERIFIED |
| D08 diagnostics target | ready | run | expected | not-recorded | none | not-recorded | UNVERIFIED |
| D09 diagnostics missing | ready | run | expected | not-recorded | none | not-recorded | UNVERIFIED |
| D10 diagnostics offline | ready | run | expected | not-recorded | none | not-recorded | UNVERIFIED |
| D11 diagnostics shutdown | ready | run | expected | not-recorded | none | not-recorded | UNVERIFIED |
| A01 auth registration | ready | run | expected | not-recorded | none | not-recorded | UNVERIFIED |
| A02 auth password | ready | run | expected | not-recorded | none | not-recorded | UNVERIFIED |
| A03 auth totp | ready | run | expected | not-recorded | none | not-recorded | UNVERIFIED |
| A04 auth recovery | ready | run | expected | not-recorded | none | not-recorded | UNVERIFIED |
| A05 auth premium | ready | run | expected | not-recorded | none | not-recorded | UNVERIFIED |
| A06 auth duplicate | ready | run | expected | not-recorded | none | not-recorded | UNVERIFIED |
| A07 auth pending | ready | run | expected | not-recorded | none | not-recorded | UNVERIFIED |
| A08 auth hub | ready | run | expected | not-recorded | none | not-recorded | UNVERIFIED |
| A09 auth identity | ready | run | expected | not-recorded | none | not-recorded | UNVERIFIED |
| A10 auth kick | ready | run | expected | not-recorded | none | not-recorded | UNVERIFIED |
| A11 auth failure | ready | run | expected | not-recorded | none | not-recorded | UNVERIFIED |
| A12 auth transfer timeout | ready | run | expected | not-recorded | none | not-recorded | UNVERIFIED |
| A13 auth timeout | ready | run | expected | not-recorded | none | not-recorded | UNVERIFIED |
| A14 auth shutdown | ready | run | expected | not-recorded | none | not-recorded | UNVERIFIED |
<!-- /UWORLD_REAL_CLIENT_MATRIX -->
"@
    "docs\UWORLD_ENVIRONMENT.md" = @'
# Uworld environment

`starx-velocity.jar` is the only plugin artifact.
Windows Service and Linux systemd are provided, statically validated operational target shapes. Staging and production execution remains UNVERIFIED.

```toml
[servers]
lobby = "127.0.0.1:25566"
player-info-forwarding-mode = "modern"
```

```yaml
modules:
  starx.uworld:
    enabled: true
uworld:
  enabled: true
```

Paper uses `online-mode=false`. Back up `data.db`, `data.db-wal`, and `data.db-shm` while stopped.

<!-- UWORLD_WINDOWS_DEPLOYMENT -->
```powershell
$ErrorActionPreference = 'Stop'
$CurrentJar = Join-Path $PluginDir 'starx-velocity.jar'
$CandidateJar = 'D:\releases\starx-velocity.jar'
$ServiceIdentity = 'NT SERVICE\Velocity'
$RequiredJarChecks = @('plugin_jar_inspection', 'starx_jar_count', 'external_limboapi', 'candidate_hash')
function Invoke-UworldDoctor([string] $Jar, [bool] $RequireBackend) {
  $DoctorArguments = @('-File', $Doctor, '-VelocityHome', $VelocityHome, '-CandidateJar', $Jar, '-ServiceIdentity', $ServiceIdentity)
  if ($RequireBackend) { $DoctorArguments += '-RequireBackend' }
  $Output = @(& $PowerShell @DoctorArguments 2>&1)
  $ExitCode = $LASTEXITCODE
  return [pscustomobject]@{ ExitCode = $ExitCode; Lines = @($Output) }
}
function Assert-UworldJarIdentity([string] $Jar) {
  $Probe = Invoke-UworldDoctor $Jar $false
  $Text = $Probe.Lines -join "`n"
  foreach ($Check in @('plugin_jar_inspection', 'starx_jar_count', 'external_limboapi', 'candidate_hash')) {
    if ($Text -notmatch "CHECK name=$Check status=PASS") { throw 'identity failed' }
  }
}
function Assert-UworldEnvironment([string] $Jar) {
  $Probe = Invoke-UworldDoctor $Jar $true
  if ($Probe.ExitCode -ne 0 -or $Probe.Lines[-1] -cne 'UWORLD_ENVIRONMENT=PASS') {
    throw 'environment failed'
  }
}
function Invoke-Icacls([string[]] $Arguments) {
  & icacls @Arguments | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'icacls failed' }
}
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-uworld-environment.ps1 -VelocityHome $VelocityHome -CandidateJar $CurrentJar -ServiceIdentity $ServiceIdentity
Assert-UworldJarIdentity $CurrentJar
Invoke-Icacls -Arguments @($BackupRoot, '/reset')
Invoke-Icacls -Arguments @($BackupRoot, '/inheritance:r')
Invoke-Icacls -Arguments @($BackupRoot, '/grant:r', '*S-1-5-18:(OI)(CI)F', '*S-1-5-32-544:(OI)(CI)F')
Get-ChildItem -LiteralPath $PluginDir -File | Out-Null
Stop-Service -Name $VelocityService -ErrorAction Stop -WhatIf:$false -Confirm:$false
Copy-Item -LiteralPath $CandidateJar -Destination (Join-Path $PluginDir 'starx-velocity.jar') -ErrorAction Stop -WhatIf:$false -Confirm:$false
$CandidateSha = (Get-FileHash -LiteralPath $CandidateJar -Algorithm SHA256).Hash
Assert-UworldEnvironment $CandidateJar
Start-Service -Name $VelocityService -ErrorAction Stop -WhatIf:$false -Confirm:$false
```
<!-- /UWORLD_WINDOWS_DEPLOYMENT -->

<!-- UWORLD_LINUX_DEPLOYMENT -->
```bash
set -euo pipefail
current_jar="$plugin_dir/starx-velocity.jar"
export RELEASE_JAR=/srv/releases/starx-velocity.jar
export VELOCITY_USER=velocity
run_uworld_doctor() {
  local candidate=$1 require_backend=$2 output code
  local args=(-File "$UWORLD_DOCTOR" -VelocityHome "$VELOCITY_HOME" -CandidateJar "$candidate" -ServiceIdentity "$VELOCITY_USER")
  if [ "$require_backend" -eq 1 ]; then
    args+=(-RequireBackend)
  fi
  if output="$(pwsh "${args[@]}" 2>&1)"; then
    code=0
  else
    code=$?
  fi
  UWORLD_DOCTOR_OUTPUT=$output
  UWORLD_DOCTOR_CODE=$code
}
assert_uworld_jar_identity() {
  local candidate=$1 check
  run_uworld_doctor "$candidate" 0
  for check in plugin_jar_inspection starx_jar_count external_limboapi candidate_hash; do
    printf '%s\n' "$UWORLD_DOCTOR_OUTPUT" | grep -Eq "^CHECK name=$check status=PASS" || return 1
  done
}
assert_uworld_environment() {
  local candidate=$1
  run_uworld_doctor "$candidate" 1
  [ "$UWORLD_DOCTOR_CODE" -eq 0 ] &&
    printf '%s\n' "$UWORLD_DOCTOR_OUTPUT" | grep -qx UWORLD_ENVIRONMENT=PASS || return 1
}
pwsh -NoProfile -File scripts/check-uworld-environment.ps1 -VelocityHome "$VELOCITY_HOME" -CandidateJar "$current_jar" -ServiceIdentity "$VELOCITY_USER"
assert_uworld_jar_identity "$current_jar"
systemctl stop "$VELOCITY_SERVICE"
install "$RELEASE_JAR" "$plugin_dir/starx-velocity.jar"
assert_uworld_environment "$RELEASE_JAR"
systemctl start "$VELOCITY_SERVICE"
```
<!-- /UWORLD_LINUX_DEPLOYMENT -->

<!-- UWORLD_LINUX_ROLLBACK -->
```bash
set -euo pipefail
test "$(id -u)" -eq 0
export VELOCITY_HOME=/srv/velocity
verify_uworld_backup "$backup_root" "$pointer"
systemctl stop "$VELOCITY_SERVICE"
```
<!-- /UWORLD_LINUX_ROLLBACK -->
'@
  }
  foreach ($Entry in $Documents.GetEnumerator()) {
    Write-AsciiFile (Join-Path $FixtureRoot $Entry.Key) $Entry.Value
  }
  Write-AsciiFile (Join-Path $FixtureRoot "scripts\check-uworld-environment.ps1") "exit 0`r`n"
  Write-AsciiFile (Join-Path $FixtureRoot "scripts\tests\check-uworld-environment.Tests.ps1") "exit 0`r`n"
}

function Write-EmptyZip([string] $Path) {
  Add-Type -AssemblyName System.IO.Compression
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  [System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($Path)) | Out-Null
  $Archive = [System.IO.Compression.ZipFile]::Open(
    $Path,
    [System.IO.Compression.ZipArchiveMode]::Create
  )
  $Archive.Dispose()
}

function Write-JunitFixture(
  [string] $FixtureRoot,
  [string] $Project,
  [string] $Suite
) {
  $Path = Join-Path $FixtureRoot (
    "starx-plugins\$Project\build\test-results\test\TEST-$Suite.xml")
  Write-AsciiFile $Path @"
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="$Suite" tests="1" skipped="0" failures="0" errors="0">
  <testcase name="passes" classname="$Suite" time="0.001" />
</testsuite>
"@
}

try {
  [System.IO.Directory]::CreateDirectory((Join-Path $TempRoot "scripts\tests")) | Out-Null
  [System.IO.Directory]::CreateDirectory((Join-Path $TempRoot "starx-plugins\starx-velocity\src\main\resources")) | Out-Null
  Copy-Item -LiteralPath $Gate -Destination (Join-Path $TempRoot "scripts\verify-uworld.ps1")

  Write-AsciiFile (Join-Path $TempRoot "build.gradle.kts") @'
allprojects {
    version = "9.9.9-test"
}
'@
  Write-AsciiFile (Join-Path $TempRoot "starx-plugins\starx-velocity\build.gradle.kts") @'
tasks.test {
    systemProperty("starx.project.version", project.version.toString())
}
tasks.jar {
    enabled = false
}
tasks.processResources {
    filesMatching("velocity-plugin.json") {
        expand("version" to project.version)
    }
}
tasks.shadowJar {
    archiveFileName.set("starx-velocity.jar")
}
'@
  Write-AsciiFile (Join-Path $TempRoot "starx-plugins\starx-velocity\src\main\resources\velocity-plugin.json") @'
{
  "id": "starx",
  "name": "StarX",
  "version": "${version}",
  "description": "StarX Velocity plugin with embedded Uworld runtime",
  "main": "io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin",
  "dependencies": []
}
'@
  Write-AsciiFile (Join-Path $TempRoot "scripts\tests\sync-starx-limbo.Tests.ps1") @'
[System.IO.File]::AppendAllText($env:UWORLD_VERIFY_TEST_LOG, "sync`r`n")
exit 0
'@
  Write-AsciiFile (Join-Path $TempRoot "scripts\tests\velocity-build606.Tests.ps1") @'
[System.IO.File]::AppendAllText($env:UWORLD_VERIFY_TEST_LOG, "pin`r`n")
exit 0
'@
  Write-AsciiFile (Join-Path $TempRoot "scripts\invoke-gradle-ascii.ps1") @'
[System.IO.File]::AppendAllText($env:UWORLD_VERIFY_TEST_LOG, "gradle|" + ($args -join "|") + "`r`n")
exit 0
'@

  $env:UWORLD_VERIFY_TEST_LOG = $Log
  $FixtureGate = Join-Path $TempRoot "scripts\verify-uworld.ps1"

  $FixtureArtifactDirectory = Join-Path $TempRoot "starx-plugins\starx-velocity\build\libs"
  $FixtureArtifact = Join-Path $FixtureArtifactDirectory "starx-velocity.jar"
  Write-EmptyZip $FixtureArtifact
  $FixtureArtifactItem = Get-Item -LiteralPath $FixtureArtifact
  $FixtureArtifactSha = (Get-FileHash -LiteralPath $FixtureArtifact -Algorithm SHA256).Hash
  $FixtureInstalledJar = Join-Path $TempRoot "velocity-test\plugins\starx-velocity.jar"
  Write-AsciiFile $FixtureInstalledJar "legacy-installed-artifact"
  $FixtureInstalledSha = (Get-FileHash -LiteralPath $FixtureInstalledJar -Algorithm SHA256).Hash

  Write-JunitFixture $TempRoot "starx-limbo-api" "fixture.LimboApiTest"
  Write-JunitFixture $TempRoot "starx-common" "fixture.CommonTest"
  Write-JunitFixture $TempRoot "starx-standalone-limbo" "fixture.StandaloneTest"
  Write-JunitFixture $TempRoot "starx-velocity" "fixture.VelocityTest"

  Write-DocumentationFixture `
    $TempRoot `
    $FixtureArtifactSha `
    $FixtureArtifactItem.Length `
    $FixtureInstalledSha
  $DocumentationOutput = & $PowerShell -NoProfile -ExecutionPolicy Bypass `
    -File $FixtureGate -DocumentationOnly 2>&1
  if ($LASTEXITCODE -ne 0 -or
      ($DocumentationOutput -join "`n") -notmatch "UWORLD_DOCUMENTATION_GATE=PASS") {
    throw "Documentation fixture did not pass`n$($DocumentationOutput -join "`n")"
  }

  $ProductDocument = Join-Path $TempRoot "starx-plugins\README.md"
  $StandaloneDocument = Join-Path $TempRoot "starx-plugins\starx-standalone-limbo\README.md"
  $ConfigurationDocument = Join-Path $TempRoot "docs\UWORLD_CONFIGURATION.md"
  $DevelopmentDocument = Join-Path $TempRoot "docs\UWORLD_DEVELOPMENT.md"
  $AcceptanceDocument = Join-Path $TempRoot "docs\UWORLD_ACCEPTANCE.md"
  $EnvironmentDocument = Join-Path $TempRoot "docs\UWORLD_ENVIRONMENT.md"
  $ValidDocumentText = @{
    $ProductDocument = [System.IO.File]::ReadAllText($ProductDocument)
    $StandaloneDocument = [System.IO.File]::ReadAllText($StandaloneDocument)
    $ConfigurationDocument = [System.IO.File]::ReadAllText($ConfigurationDocument)
    $DevelopmentDocument = [System.IO.File]::ReadAllText($DevelopmentDocument)
    $AcceptanceDocument = [System.IO.File]::ReadAllText($AcceptanceDocument)
    $EnvironmentDocument = [System.IO.File]::ReadAllText($EnvironmentDocument)
  }
  $WrongSha = "0" * 64
  if ($WrongSha -ceq $FixtureArtifactSha) {
    $WrongSha = "F" * 64
  }
  $ForgedLivePassText = ($ValidDocumentText[$AcceptanceDocument] -replace
    '(?ms)(<!-- UWORLD_LIVE_ENVIRONMENT_EVIDENCE -->.*?status=)FAIL', '${1}PASS') -replace
    'candidate_hash_check=FAIL', 'candidate_hash_check=PASS'
  $ForgedLivePassText = $ForgedLivePassText.Replace(
    "installed_sha256=$FixtureInstalledSha",
    "installed_sha256=$FixtureArtifactSha")
  $ForgedLivePassText = $ForgedLivePassText.Replace(
    "installed_path=velocity-test/plugins/starx-velocity.jar",
    "installed_path=starx-plugins/starx-velocity/build/libs/starx-velocity.jar")
  $ForgedLivePassText = $ForgedLivePassText.Replace(
    "doctor_result=UWORLD_ENVIRONMENT=FAIL",
    "doctor_result=UWORLD_ENVIRONMENT=PASS")
  $DocumentationContractFixtures = @(
    [pscustomobject]@{
      Name = "product goals omitted"
      Path = $ProductDocument
      Text = $ValidDocumentText[$ProductDocument].Replace(
        "Uworld provides managed virtual worlds, player sessions, timeouts, fail-closed cleanup, and exact-target transfer for isolated StarX flows.",
        "Uworld provides virtual worlds.")
      Expected = "Plugin product README must define Uworld product goals"
    },
    [pscustomobject]@{
      Name = "product non-goals omitted"
      Path = $ProductDocument
      Text = $ValidDocumentText[$ProductDocument].Replace(
        "Uworld is not a second plugin, does not use external LimboAPI, does not support hot reload or arbitrary backend fallback, and does not make a consumer integrated or real-client verified by itself.",
        "Uworld has product boundaries.")
      Expected = "Plugin product README must define Uworld non-goals"
    },
    [pscustomobject]@{
      Name = "future consumer marked integrated"
      Path = $ProductDocument
      Text = $ValidDocumentText[$ProductDocument].Replace(
        "| Queue | extension only, not integrated | UNVERIFIED |",
        "| Queue | integrated | PASS |")
      Expected = "Queue, Maintenance, and Tutorial must remain documented as unintegrated Uworld consumers"
    },
    [pscustomobject]@{
      Name = "unverified consumer promoted to PASS"
      Path = $ProductDocument
      Text = $ValidDocumentText[$ProductDocument].Replace(
        "| Auth | integrated | UNVERIFIED |",
        "| Auth | integrated | PASS |")
      Expected = "Auth and Diagnostics real-client status must remain UNVERIFIED until evidence is recorded"
    },
    [pscustomobject]@{
      Name = "default config omits starx.auth"
      Path = $ConfigurationDocument
      Text = $ValidDocumentText[$ConfigurationDocument] -replace
        '(?ms)^  starx\.auth:\r?\n    enabled: true\r?\n', ''
      Expected = "Uworld configuration must include modules.starx.auth in the default example"
    },
    [pscustomobject]@{
      Name = "invalid Auth combination described as supported"
      Path = $ConfigurationDocument
      Text = $ValidDocumentText[$ConfigurationDocument].Replace(
        "| true | false | any | FAIL_CLOSED |",
        "| true | false | any | supported |")
      Expected = "Uworld configuration must document Auth without a ready Uworld as fail-closed"
    },
    [pscustomobject]@{
      Name = "protocol 776 described as client minimum"
      Path = $StandaloneDocument
      Text = $ValidDocumentText[$StandaloneDocument].Replace(
        'Protocol 776 is the minimum Velocity `ProtocolVersion.MAXIMUM_VERSION` capability; it is not a Minecraft client minimum protocol.',
        'Minecraft client protocol 776 is the minimum supported client version.')
      Expected = "Standalone README must define protocol 776 as the minimum Velocity MAXIMUM_VERSION, not a client minimum"
    },
    [pscustomobject]@{
      Name = "internal API boundary omitted"
      Path = $DevelopmentDocument
      Text = $ValidDocumentText[$DevelopmentDocument].Replace(
        '`StarxUworldFactory` is an internal lifecycle detail created and closed only by `UworldModule`; it is not a service API for external Velocity plugins.',
        '`StarxUworldFactory` creates the runtime.')
      Expected = "StarxUworldFactory must remain an internal UworldModule lifecycle detail"
    },
    [pscustomobject]@{
      Name = "candidate identity hash differs from built artifact"
      Path = $AcceptanceDocument
      Text = $ValidDocumentText[$AcceptanceDocument].Replace(
        "sha256=$FixtureArtifactSha",
        "sha256=$WrongSha")
      Expected = "Uworld acceptance candidate SHA-256 must match the built artifact"
    },
    [pscustomobject]@{
      Name = "automatic evidence uses another candidate hash"
      Path = $AcceptanceDocument
      Text = $ValidDocumentText[$AcceptanceDocument].Replace(
        "ARTIFACT_SHA256=$FixtureArtifactSha",
        "ARTIFACT_SHA256=$WrongSha")
      Expected = "Uworld acceptance evidence must use one candidate SHA-256"
    },
    [pscustomobject]@{
      Name = "JUnit evidence differs from XML"
      Path = $AcceptanceDocument
      Text = $ValidDocumentText[$AcceptanceDocument].Replace(
        "starx-velocity: 1 tests, 0 failures, 0 errors, 0 skipped",
        "starx-velocity: 9 tests, 0 failures, 0 errors, 0 skipped")
      Expected = "Uworld acceptance JUnit evidence must match test-result XML"
    },
    [pscustomobject]@{
      Name = "real-client row removed"
      Path = $AcceptanceDocument
      Text = $ValidDocumentText[$AcceptanceDocument] -replace
        '(?m)^\| D11 diagnostics shutdown \|[^\r\n]*\r?\n', ''
      Expected = "Uworld acceptance real-client matrix must contain exactly 25 rows"
    },
    [pscustomobject]@{
      Name = "real-client row promoted without evidence"
      Path = $AcceptanceDocument
      Text = $ValidDocumentText[$AcceptanceDocument].Replace(
        "| D01 diagnostics status | ready | run | expected | not-recorded | none | not-recorded | UNVERIFIED |",
        "| D01 diagnostics status | ready | run | expected | not-recorded | none | not-recorded | PASS |")
      Expected = "Uworld acceptance PASS rows require observed, evidence, and ISO-8601 timestamp"
    },
    [pscustomobject]@{
      Name = "real-client PASS evidence omits candidate identity"
      Path = $AcceptanceDocument
      Text = $ValidDocumentText[$AcceptanceDocument].Replace(
        "| D02 diagnostics enter | ready | run | expected | not-recorded | none | not-recorded | UNVERIFIED |",
        "| D02 diagnostics enter | ready | run | expected | entered diagnostics world | logs/D02.log | 2026-07-15T18:35:00+08:00 | PASS |")
      Expected = "Uworld acceptance PASS row evidence must bind the current candidate SHA-256"
    },
    [pscustomobject]@{
      Name = "live installed hash falsely equals candidate"
      Path = $AcceptanceDocument
      Text = $ValidDocumentText[$AcceptanceDocument].Replace(
        "installed_sha256=$FixtureInstalledSha",
        "installed_sha256=$FixtureArtifactSha")
      Expected = "Uworld acceptance live candidate hash check must match recorded hashes"
    },
    [pscustomobject]@{
      Name = "live doctor promoted while candidate hash fails"
      Path = $AcceptanceDocument
      Text = ($ValidDocumentText[$AcceptanceDocument] -replace
        '(?ms)(<!-- UWORLD_LIVE_ENVIRONMENT_EVIDENCE -->.*?status=)FAIL', '${1}PASS') -replace
        'doctor_result=UWORLD_ENVIRONMENT=FAIL', 'doctor_result=UWORLD_ENVIRONMENT=PASS'
      Expected = "Uworld acceptance live PASS requires the installed candidate hash"
    },
    [pscustomobject]@{
      Name = "live PASS points at the candidate build artifact"
      Path = $AcceptanceDocument
      Text = $ForgedLivePassText
      Expected = "Uworld acceptance live installed path must equal VelocityHome/plugins/starx-velocity.jar"
    },
    [pscustomobject]@{
      Name = "live PASS omits environment doctor evidence"
      Path = $AcceptanceDocument
      Text = $ForgedLivePassText
      Expected = "Uworld acceptance live PASS requires hashed environment doctor evidence"
    },
    [pscustomobject]@{
      Name = "live installed artifact path is missing"
      Path = $AcceptanceDocument
      Text = $ValidDocumentText[$AcceptanceDocument].Replace(
        "installed_path=velocity-test/plugins/starx-velocity.jar",
        "installed_path=velocity-test/plugins/missing-starx-velocity.jar")
      Expected = "Uworld acceptance live installed artifact must exist"
    },
    [pscustomobject]@{
      Name = "real-client required case is duplicated"
      Path = $AcceptanceDocument
      Text = $ValidDocumentText[$AcceptanceDocument].Replace(
        "| A14 auth shutdown |",
        "| A13 duplicate auth timeout |")
      Expected = "Uworld acceptance real-client matrix must contain each required case ID exactly once"
    },
    [pscustomobject]@{
      Name = "real-client PASS evidence path is missing"
      Path = $AcceptanceDocument
      Text = $ValidDocumentText[$AcceptanceDocument].Replace(
        "| D03 diagnostics platform | ready | run | expected | not-recorded | none | not-recorded | UNVERIFIED |",
        "| D03 diagnostics platform | ready | run | expected | rendered an 11x11 platform | docs/evidence/D03.txt; sha256=$FixtureArtifactSha | 2026-07-15T18:35:00+08:00 | PASS |")
      Expected = "Uworld acceptance PASS row evidence file must exist"
    },
    [pscustomobject]@{
      Name = "platform targets promoted without execution evidence"
      Path = $EnvironmentDocument
      Text = $ValidDocumentText[$EnvironmentDocument].Replace(
        "Windows Service and Linux systemd are provided, statically validated operational target shapes. Staging and production execution remains UNVERIFIED.",
        "Windows Service and Linux systemd are supported production environments.")
      Expected = "Uworld environment must keep Windows and Linux deployment execution UNVERIFIED"
    },
    [pscustomobject]@{
      Name = "platform targets contain a contradictory production claim"
      Path = $EnvironmentDocument
      Text = $ValidDocumentText[$EnvironmentDocument] +
        "`r`nWindows Service and Linux systemd are supported production environments.`r`n"
      Expected = "Uworld environment must not contradict its unverified platform status"
    }
  )
  foreach ($Fixture in $DocumentationContractFixtures) {
    $Original = $ValidDocumentText[$Fixture.Path]
    if ($Fixture.Text -ceq $Original) {
      throw "Documentation contract fixture did not alter its document: $($Fixture.Name)"
    }
    Write-AsciiFile $Fixture.Path $Fixture.Text
    $ContractOutput = & $PowerShell -NoProfile -ExecutionPolicy Bypass `
      -File $FixtureGate -DocumentationOnly 2>&1
    if ($LASTEXITCODE -eq 0 -or
        ($ContractOutput -join "`n") -notmatch [regex]::Escape($Fixture.Expected)) {
      throw "Documentation gate accepted contract fixture: $($Fixture.Name)`n$($ContractOutput -join "`n")"
    }
    Write-AsciiFile $Fixture.Path $Original
  }

  Copy-Item -LiteralPath $FixtureArtifact -Destination $FixtureInstalledJar -Force
  $DoctorEvidencePath = Join-Path $TempRoot "docs\evidence\live-doctor.log"
  Write-AsciiFile $DoctorEvidencePath "UWORLD_ENVIRONMENT=PASS`r`n"
  $DoctorEvidenceSha = (Get-FileHash -LiteralPath $DoctorEvidencePath -Algorithm SHA256).Hash
  $LivePassAcceptance = ($ValidDocumentText[$AcceptanceDocument] -replace
    '(?ms)(<!-- UWORLD_LIVE_ENVIRONMENT_EVIDENCE -->.*?status=)FAIL', '${1}PASS') -replace
    'candidate_hash_check=FAIL', 'candidate_hash_check=PASS'
  $LivePassAcceptance = $LivePassAcceptance.Replace(
    "installed_sha256=$FixtureInstalledSha",
    "installed_sha256=$FixtureArtifactSha")
  $LivePassAcceptance = $LivePassAcceptance.Replace(
    "doctor_result=UWORLD_ENVIRONMENT=FAIL",
    "doctor_result=UWORLD_ENVIRONMENT=PASS`r`ndoctor_evidence=docs/evidence/live-doctor.log; sha256=$DoctorEvidenceSha")
  Write-AsciiFile $AcceptanceDocument $LivePassAcceptance
  $MinimalDoctorOutput = & $PowerShell -NoProfile -ExecutionPolicy Bypass `
    -File $FixtureGate -DocumentationOnly 2>&1
  if ($LASTEXITCODE -eq 0 -or
      ($MinimalDoctorOutput -join "`n") -notmatch
        "Uworld acceptance live PASS requires hashed environment doctor evidence") {
    throw "Documentation gate accepted a marker-only environment doctor log`n$($MinimalDoctorOutput -join "`n")"
  }

  $ExpectedVelocityHome = [System.IO.Path]::GetFullPath((Join-Path $TempRoot "velocity-test"))
  $ExpectedInstalledPath = [System.IO.Path]::GetFullPath($FixtureInstalledJar)
  $DoctorCheckNames = @(
    "velocity_home",
    "java_21",
    "velocity_build_606",
    "plugin_jar_inspection",
    "starx_jar_count",
    "external_limboapi",
    "candidate_hash",
    "starx_config_syntax",
    "uworld_config",
    "velocity_config_syntax",
    "target_registered",
    "backend_reachable",
    "velocity_modern_forwarding",
    "paper_config_syntax",
    "paper_velocity_forwarding",
    "forwarding_secret_match",
    "forwarding_online_mode_match",
    "paper_server_properties_syntax",
    "paper_online_mode",
    "paper_target_port",
    "paper_target_host",
    "paper_server_binding",
    "sqlite_parent_writable"
  )
  $DoctorLines = foreach ($CheckName in $DoctorCheckNames) {
    $Detail = switch ($CheckName) {
      "velocity_home" { "path=$ExpectedVelocityHome exists=true" }
      "starx_jar_count" { "single=true path=$ExpectedInstalledPath" }
      "candidate_hash" {
        "candidate_sha256=$FixtureArtifactSha deployed_sha256=$FixtureArtifactSha match=true"
      }
      default { "verified=true" }
    }
    "CHECK name=$CheckName status=PASS detail=$Detail"
  }
  Write-AsciiFile $DoctorEvidencePath (($DoctorLines + "UWORLD_ENVIRONMENT=PASS") -join "`r`n")
  $DoctorEvidenceSha = (Get-FileHash -LiteralPath $DoctorEvidencePath -Algorithm SHA256).Hash
  $LivePassAcceptance = $LivePassAcceptance -replace
    'doctor_evidence=docs/evidence/live-doctor\.log; sha256=[A-Fa-f0-9]{64}',
    "doctor_evidence=docs/evidence/live-doctor.log; sha256=$DoctorEvidenceSha"
  Write-AsciiFile $AcceptanceDocument $LivePassAcceptance
  $CompleteDoctorOutput = & $PowerShell -NoProfile -ExecutionPolicy Bypass `
    -File $FixtureGate -DocumentationOnly 2>&1
  if ($LASTEXITCODE -ne 0 -or
      ($CompleteDoctorOutput -join "`n") -notmatch "UWORLD_DOCUMENTATION_GATE=PASS") {
    throw "Documentation gate rejected complete environment doctor evidence`n$($CompleteDoctorOutput -join "`n")"
  }
  Write-AsciiFile $AcceptanceDocument $ValidDocumentText[$AcceptanceDocument]
  Write-AsciiFile $FixtureInstalledJar "legacy-installed-artifact"
  Remove-Item -LiteralPath $DoctorEvidencePath -Force

  $EvidencePath = Join-Path $TempRoot "docs\evidence\D01.txt"
  $EvidenceTimestamp = "2026-07-15T18:35:00+08:00"
  Write-AsciiFile $EvidencePath @"
artifact_sha256=$FixtureArtifactSha
case_id=D01
timestamp=$EvidenceTimestamp
status=PASS
"@
  $EvidenceRow = "| D01 diagnostics status | ready | run | expected | runtime ready with zero sessions | docs/evidence/D01.txt; sha256=$FixtureArtifactSha | $EvidenceTimestamp | PASS |"
  $EvidenceAcceptance = $ValidDocumentText[$AcceptanceDocument].Replace(
    "| D01 diagnostics status | ready | run | expected | not-recorded | none | not-recorded | UNVERIFIED |",
    $EvidenceRow)
  Write-AsciiFile $AcceptanceDocument $EvidenceAcceptance
  $MinimalEvidenceOutput = & $PowerShell -NoProfile -ExecutionPolicy Bypass `
    -File $FixtureGate -DocumentationOnly 2>&1
  if ($LASTEXITCODE -eq 0 -or
      ($MinimalEvidenceOutput -join "`n") -notmatch
        "Uworld acceptance PASS row evidence must bind client, route, outcome, and proxy log") {
    throw "Documentation gate accepted a four-line real-client evidence shell`n$($MinimalEvidenceOutput -join "`n")"
  }

  $ProxyLogPath = Join-Path $TempRoot "docs\evidence\D01-proxy.log"
  Write-AsciiFile $ProxyLogPath @"
artifact_sha256=$FixtureArtifactSha
case_id=D01
timestamp=$EvidenceTimestamp
observed_outcome=runtime ready with zero sessions
event=D01 diagnostics status returned runtime ready with zero sessions
"@
  $ProxyLogSha = (Get-FileHash -LiteralPath $ProxyLogPath -Algorithm SHA256).Hash
  $EvidenceWithoutRuntimeText = @"
artifact_sha256=$FixtureArtifactSha
case_id=D01
timestamp=$EvidenceTimestamp
status=PASS
client_version=1.21.11
account_type=offline
initial_server=paper-fixture
expected_target=diagnostics
observed_outcome=runtime ready with zero sessions
proxy_log=docs/evidence/D01-proxy.log
proxy_log_sha256=$ProxyLogSha
"@
  Write-AsciiFile $EvidencePath $EvidenceWithoutRuntimeText
  $MissingRuntimeOutput = & $PowerShell -NoProfile -ExecutionPolicy Bypass `
    -File $FixtureGate -DocumentationOnly 2>&1
  if ($LASTEXITCODE -eq 0 -or
      ($MissingRuntimeOutput -join "`n") -notmatch
        "Uworld acceptance PASS row evidence must bind Velocity build and Java version") {
    throw "Documentation gate accepted real-client evidence without runtime identity`n$($MissingRuntimeOutput -join "`n")"
  }

  $CompleteEvidenceText = $EvidenceWithoutRuntimeText.Replace(
    "status=PASS",
    "status=PASS`r`nvelocity_build=606`r`njava_version=21.0.8")
  $UnboundProxyLogPath = Join-Path $TempRoot "docs\evidence\D01-unbound-proxy.log"
  Write-AsciiFile $UnboundProxyLogPath "diagnostics command completed`r`n"
  $UnboundProxyLogSha = (Get-FileHash -LiteralPath $UnboundProxyLogPath -Algorithm SHA256).Hash
  $MetadataOnlyProxyLogPath = Join-Path $TempRoot "docs\evidence\D01-metadata-only-proxy.log"
  Write-AsciiFile $MetadataOnlyProxyLogPath @"
artifact_sha256=$FixtureArtifactSha
case_id=D01
timestamp=$EvidenceTimestamp
observed_outcome=runtime ready with zero sessions
"@
  $MetadataOnlyProxyLogSha = (Get-FileHash -LiteralPath $MetadataOnlyProxyLogPath -Algorithm SHA256).Hash
  $UnrelatedDocumentSha = (Get-FileHash -LiteralPath $ProductDocument -Algorithm SHA256).Hash
  $UnrelatedProxyEvidenceText = $CompleteEvidenceText.Replace(
    "proxy_log=docs/evidence/D01-proxy.log",
    "proxy_log=starx-plugins/README.md")
  $UnrelatedProxyEvidenceText = $UnrelatedProxyEvidenceText.Replace(
    "proxy_log_sha256=$ProxyLogSha",
    "proxy_log_sha256=$UnrelatedDocumentSha")
  $UnboundProxyEvidenceText = $CompleteEvidenceText.Replace(
    "proxy_log=docs/evidence/D01-proxy.log",
    "proxy_log=docs/evidence/D01-unbound-proxy.log")
  $UnboundProxyEvidenceText = $UnboundProxyEvidenceText.Replace(
    "proxy_log_sha256=$ProxyLogSha",
    "proxy_log_sha256=$UnboundProxyLogSha")
  $MetadataOnlyProxyEvidenceText = $CompleteEvidenceText.Replace(
    "proxy_log=docs/evidence/D01-proxy.log",
    "proxy_log=docs/evidence/D01-metadata-only-proxy.log")
  $MetadataOnlyProxyEvidenceText = $MetadataOnlyProxyEvidenceText.Replace(
    "proxy_log_sha256=$ProxyLogSha",
    "proxy_log_sha256=$MetadataOnlyProxyLogSha")
  $InvalidExecutionEvidenceFixtures = [ordered]@{
    "generic client version" = $CompleteEvidenceText.Replace(
      "client_version=1.21.11",
      "client_version=ok")
    "generic account type" = $CompleteEvidenceText.Replace(
      "account_type=offline",
      "account_type=pass")
    "empty expected target" = $CompleteEvidenceText.Replace(
      "expected_target=diagnostics",
      "expected_target=")
    "proxy log hash differs" = $CompleteEvidenceText.Replace(
      "proxy_log_sha256=$ProxyLogSha",
      "proxy_log_sha256=$WrongSha")
    "proxy log path is missing" = $CompleteEvidenceText.Replace(
      "proxy_log=docs/evidence/D01-proxy.log",
      "proxy_log=docs/evidence/missing-D01-proxy.log")
    "proxy log points at an unrelated repository document" = $UnrelatedProxyEvidenceText
    "proxy log omits execution binding" = $UnboundProxyEvidenceText
    "proxy log omits an actual event" = $MetadataOnlyProxyEvidenceText
  }
  foreach ($Fixture in $InvalidExecutionEvidenceFixtures.GetEnumerator()) {
    Write-AsciiFile $EvidencePath $Fixture.Value
    $InvalidEvidenceOutput = & $PowerShell -NoProfile -ExecutionPolicy Bypass `
      -File $FixtureGate -DocumentationOnly 2>&1
    if ($LASTEXITCODE -eq 0 -or
        ($InvalidEvidenceOutput -join "`n") -notmatch
          "Uworld acceptance PASS row evidence must bind client, route, outcome, and proxy log") {
      throw "Documentation gate accepted invalid real-client execution evidence: $($Fixture.Key)`n$($InvalidEvidenceOutput -join "`n")"
    }
  }

  Write-AsciiFile $EvidencePath $CompleteEvidenceText
  $EvidenceOutput = & $PowerShell -NoProfile -ExecutionPolicy Bypass `
    -File $FixtureGate -DocumentationOnly 2>&1
  if ($LASTEXITCODE -ne 0 -or
      ($EvidenceOutput -join "`n") -notmatch "UWORLD_DOCUMENTATION_GATE=PASS") {
    throw "Documentation gate rejected a complete real-client evidence row`n$($EvidenceOutput -join "`n")"
  }
  Write-AsciiFile $AcceptanceDocument $ValidDocumentText[$AcceptanceDocument]
  Remove-Item -LiteralPath $MetadataOnlyProxyLogPath -Force
  Remove-Item -LiteralPath $UnboundProxyLogPath -Force
  Remove-Item -LiteralPath $ProxyLogPath -Force
  Remove-Item -LiteralPath $EvidencePath -Force
  if ($DocumentationContractOnly) {
    Write-Output "UWORLD_DOCUMENTATION_CONTRACT_FIXTURES=PASS"
    return
  }

  $ValidEnvironmentText = [System.IO.File]::ReadAllText($EnvironmentDocument)

  $MissingServiceIdentityFixtures = [ordered]@{
    "ordinary PowerShell doctor call" = $ValidEnvironmentText.Replace(
      'powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-uworld-environment.ps1 -VelocityHome $VelocityHome -CandidateJar $CurrentJar -ServiceIdentity $ServiceIdentity',
      'powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-uworld-environment.ps1 -VelocityHome $VelocityHome -CandidateJar $CurrentJar')
    "Windows doctor runner" = $ValidEnvironmentText.Replace(
      "'-CandidateJar', `$Jar, '-ServiceIdentity', `$ServiceIdentity",
      "'-CandidateJar', `$Jar")
    "ordinary Linux doctor call" = $ValidEnvironmentText.Replace(
      'pwsh -NoProfile -File scripts/check-uworld-environment.ps1 -VelocityHome "$VELOCITY_HOME" -CandidateJar "$current_jar" -ServiceIdentity "$VELOCITY_USER"',
      'pwsh -NoProfile -File scripts/check-uworld-environment.ps1 -VelocityHome "$VELOCITY_HOME" -CandidateJar "$current_jar"')
    "Linux doctor runner" = $ValidEnvironmentText.Replace(
      'local args=(-File "$UWORLD_DOCTOR" -VelocityHome "$VELOCITY_HOME" -CandidateJar "$candidate" -ServiceIdentity "$VELOCITY_USER")',
      'local args=(-File "$UWORLD_DOCTOR" -VelocityHome "$VELOCITY_HOME" -CandidateJar "$candidate")')
  }
  foreach ($Fixture in $MissingServiceIdentityFixtures.GetEnumerator()) {
    if ($Fixture.Value -ceq $ValidEnvironmentText) {
      throw "ServiceIdentity fixture did not alter the document: $($Fixture.Key)"
    }
    Write-AsciiFile $EnvironmentDocument $Fixture.Value
    $IdentityOutput = & $PowerShell -NoProfile -ExecutionPolicy Bypass `
      -File $FixtureGate -DocumentationOnly 2>&1
    if ($LASTEXITCODE -eq 0 -or
        ($IdentityOutput -join "`n") -notmatch "ServiceIdentity") {
      throw "Documentation gate accepted $($Fixture.Key) without ServiceIdentity`n$($IdentityOutput -join "`n")"
    }
  }
  Write-AsciiFile $EnvironmentDocument $ValidEnvironmentText
  if ($ServiceIdentityOnly) {
    Write-Output "UWORLD_SERVICE_IDENTITY_FIXTURES=PASS"
    return
  }

  Remove-Item -LiteralPath $EnvironmentDocument -Force
  $MissingDocumentOutput = & $PowerShell -NoProfile -ExecutionPolicy Bypass `
    -File $FixtureGate -DocumentationOnly 2>&1
  if ($LASTEXITCODE -eq 0 -or
      ($MissingDocumentOutput -join "`n") -notmatch "Missing Uworld documentation") {
    throw "Documentation gate accepted a missing environment guide`n$($MissingDocumentOutput -join "`n")"
  }

  Write-AsciiFile $EnvironmentDocument ($ValidEnvironmentText + "`r`n[broken](missing.md)`r`n")
  $BrokenLinkOutput = & $PowerShell -NoProfile -ExecutionPolicy Bypass `
    -File $FixtureGate -DocumentationOnly 2>&1
  if ($LASTEXITCODE -eq 0 -or
      ($BrokenLinkOutput -join "`n") -notmatch "Missing local Markdown link target") {
    throw "Documentation gate accepted a broken local link`n$($BrokenLinkOutput -join "`n")"
  }

  $LegacyEnvironmentText = $ValidEnvironmentText `
    -replace '\[servers\]', '[[server]]' `
    -replace 'starx\.uworld', 'starx.limbo' `
    -replace '(?m)^uworld:', 'limbo:'
  Write-AsciiFile $EnvironmentDocument $LegacyEnvironmentText
  $LegacyOutput = & $PowerShell -NoProfile -ExecutionPolicy Bypass `
    -File $FixtureGate -DocumentationOnly 2>&1
  if ($LASTEXITCODE -eq 0 -or
      ($LegacyOutput -join "`n") -notmatch "legacy-only|canonical \[servers\]") {
    throw "Documentation gate accepted legacy-only environment examples`n$($LegacyOutput -join "`n")"
  }

  $NoDoctorCommand = $ValidEnvironmentText -replace '(?im)^.*check-uworld-environment\.ps1.*\r?\n', ''
  Write-AsciiFile $EnvironmentDocument $NoDoctorCommand
  $NoDoctorOutput = & $PowerShell -NoProfile -ExecutionPolicy Bypass `
    -File $FixtureGate -DocumentationOnly 2>&1
  if ($LASTEXITCODE -eq 0 -or
      ($NoDoctorOutput -join "`n") -notmatch "environment doctor command") {
    throw "Documentation gate accepted a guide without the doctor command`n$($NoDoctorOutput -join "`n")"
  }

  $UnsafeFixtures = [ordered]@{
    "missing fail-fast PowerShell error preference" =
      $ValidEnvironmentText -replace
        '(?m)^\$ErrorActionPreference = ''Stop''\r?\n', ''
    "scoped PowerShell error preference overrides fail-fast" =
      $ValidEnvironmentText -replace
        '(?m)^\$ErrorActionPreference = ''Stop''$',
        "`$ErrorActionPreference = 'Stop'`r`n`$script:ErrorActionPreference = 'Continue'"
    "PowerShell trap swallows terminating errors" =
      $ValidEnvironmentText -replace
        '(?m)^\$ErrorActionPreference = ''Stop''$',
        "`$ErrorActionPreference = 'Stop'`r`ntrap { continue }"
    "PowerShell defaults simulate mutating commands" =
      $ValidEnvironmentText -replace
        '(?m)^\$ErrorActionPreference = ''Stop''$',
        "`$ErrorActionPreference = 'Stop'`r`n`$PSDefaultParameterValues['*:WhatIf'] = `$true"
    "PowerShell alias disables fail-fast preference" =
      $ValidEnvironmentText -replace
        '(?m)^\$ErrorActionPreference = ''Stop''$',
        "`$ErrorActionPreference = 'Stop'`r`nsv ErrorActionPreference Continue"
    "nested PowerShell scope disables fail-fast preference" =
      $ValidEnvironmentText -replace
        '(?m)^\$ErrorActionPreference = ''Stop''$',
        (@(
          "`$ErrorActionPreference = 'Stop'"
          '& { Set-Variable -Scope 1 -Name ErrorActionPreference -Value Continue }'
        ) -join "`r`n")
    "dynamic PowerShell preference name disables fail-fast" =
      $ValidEnvironmentText -replace
        '(?m)^\$ErrorActionPreference = ''Stop''$',
        (@(
          "`$ErrorActionPreference = 'Stop'"
          "sv -Name ('ErrorAction'+'Preference') -Value Continue"
        ) -join "`r`n")
    "function scope disables script fail-fast preference" =
      $ValidEnvironmentText -replace
        '(?m)^Assert-UworldJarIdentity \$CurrentJar$',
        (@(
          'function Disable-FailFast { $script:ErrorActionPreference = ''Continue'' }'
          'Disable-FailFast'
          'Assert-UworldJarIdentity $CurrentJar'
        ) -join "`r`n")
    "missing fail-fast Bash options" = $ValidEnvironmentText -replace
      '(?m)^set -euo pipefail\r?\n', ''
    "Bash errexit is disabled before deployment" =
      $ValidEnvironmentText -replace
        '(?m)^set -euo pipefail$',
        "set -euo pipefail`r`nset +e"
    "Bash builtin wrapper disables errexit" =
      $ValidEnvironmentText -replace
        '(?m)^set -euo pipefail$',
        "set -euo pipefail`r`nbuiltin set +e"
    "Bash command wrapper disables errexit" =
      $ValidEnvironmentText -replace
        '(?m)^set -euo pipefail$',
        "set -euo pipefail`r`ncommand set +e"
    "Bash eval disables errexit" =
      $ValidEnvironmentText -replace
        '(?m)^set -euo pipefail$',
        "set -euo pipefail`r`neval 'set +e'"
    "Bash builtin eval disables errexit" =
      $ValidEnvironmentText -replace
        '(?m)^set -euo pipefail$',
        "set -euo pipefail`r`nbuiltin eval 'set +e'"
    "Bash ANSI-C eval disables errexit" =
      $ValidEnvironmentText -replace
        '(?m)^set -euo pipefail$',
        "set -euo pipefail`r`n`$'eval' 'set +e'"
    "Bash command eval disables errexit" =
      $ValidEnvironmentText -replace
        '(?m)^set -euo pipefail$',
        "set -euo pipefail`r`ncommand -- eval 'set +e'"
    "Bash set function shadows fail-fast builtin" =
      $ValidEnvironmentText -replace
        '(?m)^set -euo pipefail$',
        "set() { :; }`r`nset -euo pipefail"
    "Bash enable disables fail-fast builtin" =
      $ValidEnvironmentText -replace
        '(?m)^set -euo pipefail$',
        "enable -n set`r`nset -euo pipefail"
    "Bash source can mutate deployment state" =
      $ValidEnvironmentText -replace
        '(?m)^set -euo pipefail$',
        "set -euo pipefail`r`nsource /srv/releases/uworld-env.sh"
    "Bash command fails before fail-fast is enabled" =
      $ValidEnvironmentText -replace
        '(?m)^set -euo pipefail$',
        ('test "$(id -u)" -eq 0' + "`r`nset -euo pipefail")
    "Linux rollback command fails before fail-fast is enabled" =
      $ValidEnvironmentText -replace
        '(?m)^(<!-- UWORLD_LINUX_ROLLBACK -->\r?\n```bash\r?\n)set -euo pipefail\r?\ntest "\$\(id -u\)" -eq 0$',
        ('${1}test "$(id -u)" -eq 0' + "`nset -euo pipefail")
    "missing pre-stop JAR identity check" = $ValidEnvironmentText -replace
      '(?m)^Assert-UworldJarIdentity \$CurrentJar\r?\n', ''
    "missing pre-start environment check" = $ValidEnvironmentText -replace
      '(?m)^Assert-UworldEnvironment \$CandidateJar\r?\n', ''
    "PowerShell scriptblocks spoof doctor calls" = Set-WindowsDoctorCalls `
      $ValidEnvironmentText `
      '$Identity = { Assert-UworldJarIdentity $CurrentJar }' `
      '$Doctor = { Assert-UworldEnvironment $CandidateJar }'
    "PowerShell false branches spoof doctor calls" = Set-WindowsDoctorCalls `
      $ValidEnvironmentText `
      'if ($false) { Assert-UworldJarIdentity $CurrentJar }' `
      'if ($false) { Assert-UworldEnvironment $CandidateJar }'
    "Windows identity checks the wrong JAR" = $ValidEnvironmentText -replace
      '(?m)^Assert-UworldJarIdentity \$CurrentJar$',
      'Assert-UworldJarIdentity $CandidateJar'
    "Windows environment checks the wrong JAR" = $ValidEnvironmentText -replace
      '(?m)^Assert-UworldEnvironment \$CandidateJar$',
      'Assert-UworldEnvironment $CurrentJar'
    "Windows service command targets the wrong service" =
      $ValidEnvironmentText -replace
        '(?m)^Stop-Service -Name \$VelocityService -ErrorAction Stop -WhatIf:\$false -Confirm:\$false$',
        'Stop-Service -Name $OtherService'
    "PowerShell function overrides service stop" =
      $ValidEnvironmentText.Replace(
        '$RequiredJarChecks = @(''plugin_jar_inspection'', ''starx_jar_count'', ''external_limboapi'', ''candidate_hash'')',
        @'
$RequiredJarChecks = @('plugin_jar_inspection', 'starx_jar_count', 'external_limboapi', 'candidate_hash')
function Stop-Service { param($Name, $ErrorAction, $WhatIf, $Confirm) }
'@)
    "scoped PowerShell function overrides service stop" =
      $ValidEnvironmentText.Replace(
        '$RequiredJarChecks = @(''plugin_jar_inspection'', ''starx_jar_count'', ''external_limboapi'', ''candidate_hash'')',
        @'
$RequiredJarChecks = @('plugin_jar_inspection', 'starx_jar_count', 'external_limboapi', 'candidate_hash')
function script:Stop-Service { param($Name, $ErrorAction, $WhatIf, $Confirm) }
'@)
    "PowerShell alias overrides service stop" =
      $ValidEnvironmentText -replace
        '(?m)^Assert-UworldJarIdentity \$CurrentJar$',
        (@(
          'function Invoke-FakeStop {'
          '  [CmdletBinding(SupportsShouldProcess = $true)]'
          '  param([string] $Name)'
          '}'
          'Set-Alias -Name Stop-Service -Value Invoke-FakeStop'
          'Assert-UworldJarIdentity $CurrentJar'
        ) -join "`r`n")
    "executable path spoofs Windows service command" =
      $ValidEnvironmentText -replace
        '(?m)^Stop-Service -Name \$VelocityService -ErrorAction Stop -WhatIf:\$false -Confirm:\$false$',
        "& 'C:\tools\Stop-Service.exe' -Name `$VelocityService"
    "extensionless executable path spoofs Windows service command" =
      $ValidEnvironmentText -replace
        '(?m)^Stop-Service -Name \$VelocityService -ErrorAction Stop -WhatIf:\$false -Confirm:\$false$',
        "& 'C:\tools\Stop-Service' -Name `$VelocityService"
    "untrusted module spoofs Windows service command" =
      $ValidEnvironmentText -replace
        '(?m)^Stop-Service -Name \$VelocityService -ErrorAction Stop -WhatIf:\$false -Confirm:\$false$',
        'Contoso.Tools\Stop-Service -Name $VelocityService'
    "Windows service stop is only simulated" =
      $ValidEnvironmentText -replace
        '(?m)^Stop-Service -Name \$VelocityService -ErrorAction Stop -WhatIf:\$false -Confirm:\$false$',
        'Stop-Service -Name $VelocityService -ErrorAction Stop -WhatIf -Confirm:$false'
    "Windows service stop swallows failures" =
      $ValidEnvironmentText -replace
        '(?m)^Stop-Service -Name \$VelocityService -ErrorAction Stop -WhatIf:\$false -Confirm:\$false$',
        'Stop-Service -Name $VelocityService -ErrorAction SilentlyContinue'
    "Windows service stop omits explicit execution controls" =
      $ValidEnvironmentText -replace
        '(?m)^Stop-Service -Name \$VelocityService -ErrorAction Stop -WhatIf:\$false -Confirm:\$false$',
        'Stop-Service -Name $VelocityService'
    "Windows candidate copy is only simulated" =
      $ValidEnvironmentText -replace
        '(?m)^Copy-Item -LiteralPath \$CandidateJar -Destination \(Join-Path \$PluginDir ''starx-velocity\.jar''\) -ErrorAction Stop -WhatIf:\$false -Confirm:\$false$',
        "Copy-Item -LiteralPath `$CandidateJar -Destination (Join-Path `$PluginDir 'starx-velocity.jar') -ErrorAction Stop -WhatIf -Confirm:`$false"
    "Windows candidate copy omits explicit execution controls" =
      $ValidEnvironmentText -replace
        '(?m)^Copy-Item -LiteralPath \$CandidateJar -Destination \(Join-Path \$PluginDir ''starx-velocity\.jar''\) -ErrorAction Stop -WhatIf:\$false -Confirm:\$false$',
        "Copy-Item -LiteralPath `$CandidateJar -Destination (Join-Path `$PluginDir 'starx-velocity.jar')"
    "Windows service start is only simulated" =
      $ValidEnvironmentText -replace
        '(?m)^Start-Service -Name \$VelocityService -ErrorAction Stop -WhatIf:\$false -Confirm:\$false$',
        'Start-Service -Name $VelocityService -ErrorAction Stop -WhatIf -Confirm:$false'
    "Windows service start omits explicit execution controls" =
      $ValidEnvironmentText -replace
        '(?m)^Start-Service -Name \$VelocityService -ErrorAction Stop -WhatIf:\$false -Confirm:\$false$',
        'Start-Service -Name $VelocityService'
    "pre-stop identity check after service stop" = $ValidEnvironmentText -replace
      '(?m)^Assert-UworldJarIdentity \$CurrentJar\r?\nInvoke-Icacls -Arguments @\(\$BackupRoot, ''/reset''\)\r?\nInvoke-Icacls -Arguments @\(\$BackupRoot, ''/inheritance:r''\)\r?\nInvoke-Icacls -Arguments @\(\$BackupRoot, ''/grant:r'', ''\*S-1-5-18:\(OI\)\(CI\)F'', ''\*S-1-5-32-544:\(OI\)\(CI\)F''\)\r?\nGet-ChildItem -LiteralPath \$PluginDir -File \| Out-Null\r?\nStop-Service -Name \$VelocityService -ErrorAction Stop -WhatIf:\$false -Confirm:\$false',
      "Invoke-Icacls -Arguments @(`$BackupRoot, '/reset')`r`nInvoke-Icacls -Arguments @(`$BackupRoot, '/inheritance:r')`r`nInvoke-Icacls -Arguments @(`$BackupRoot, '/grant:r', '*S-1-5-18:(OI)(CI)F', '*S-1-5-32-544:(OI)(CI)F')`r`nGet-ChildItem -LiteralPath `$PluginDir -File | Out-Null`r`nStop-Service -Name `$VelocityService -ErrorAction Stop -WhatIf:`$false -Confirm:`$false`r`nAssert-UworldJarIdentity `$CurrentJar"
    "candidate installed before Windows service stop" =
      $ValidEnvironmentText -replace
        '(?m)^Stop-Service -Name \$VelocityService -ErrorAction Stop -WhatIf:\$false -Confirm:\$false\r?\nCopy-Item -LiteralPath \$CandidateJar -Destination \(Join-Path \$PluginDir ''starx-velocity\.jar''\) -ErrorAction Stop -WhatIf:\$false -Confirm:\$false',
        "Copy-Item -LiteralPath `$CandidateJar -Destination (Join-Path `$PluginDir 'starx-velocity.jar') -ErrorAction Stop -WhatIf:`$false -Confirm:`$false`r`nStop-Service -Name `$VelocityService -ErrorAction Stop -WhatIf:`$false -Confirm:`$false"
    "extra Windows candidate copy runs before identity" =
      $ValidEnvironmentText -replace
        '(?m)^Assert-UworldJarIdentity \$CurrentJar$',
        (@(
          'Copy-Item -LiteralPath $CandidateJar -Destination $CurrentJar -ErrorAction Stop -WhatIf:$false -Confirm:$false'
          'Assert-UworldJarIdentity $CurrentJar'
        ) -join "`r`n")
    "filename-based JAR selection" = $ValidEnvironmentText + @'

```powershell
Get-ChildItem $PluginDir -File | Where-Object {
  $_.Name -match '(?i)^(starx.*|.*limboapi.*)\.jar$'
}
```

```bash
find "$plugin_dir" -maxdepth 1 -type f -iname 'starx*.jar'
```
'@
    "unchecked icacls failure" = $ValidEnvironmentText -replace
      '(?m)^\s*if \(\$LASTEXITCODE -ne 0\) \{ throw ''icacls failed'' \}\r?\n', ''
    "icacls helper ignores supplied arguments" = $ValidEnvironmentText -replace
      '& icacls @Arguments', '& icacls /?'
    "icacls helper has no argument parameter" = $ValidEnvironmentText -replace
      'function Invoke-Icacls\(\[string\[\]\] \$Arguments\)',
      'function Invoke-Icacls'
    "icacls helper returns before invocation" =
      $ValidEnvironmentText -replace
        '(?m)^  & icacls @Arguments \| Out-Null$',
        "  return`r`n  & icacls @Arguments | Out-Null"
    "nonterminating icacls failure branch" = $ValidEnvironmentText -replace
      'if \(\$LASTEXITCODE -ne 0\) \{ throw ''icacls failed'' \}',
      "if (`$LASTEXITCODE -ne 0) { Write-Warning 'icacls failed' }"
    "unreachable icacls failure termination" = $ValidEnvironmentText -replace
      'if \(\$LASTEXITCODE -ne 0\) \{ throw ''icacls failed'' \}',
      "if (`$LASTEXITCODE -ne 0) { return; throw 'unreachable' }"
    "successful exit precedes icacls failure termination" =
      $ValidEnvironmentText -replace
        'if \(\$LASTEXITCODE -ne 0\) \{ throw ''icacls failed'' \}',
        "if (`$LASTEXITCODE -ne 0) { exit 0; throw 'unreachable' }"
    "continue precedes icacls failure termination" =
      $ValidEnvironmentText -replace
        'if \(\$LASTEXITCODE -ne 0\) \{ throw ''icacls failed'' \}',
        "if (`$LASTEXITCODE -ne 0) { continue; throw 'unreachable' }"
    "break precedes icacls failure termination" =
      $ValidEnvironmentText -replace
        'if \(\$LASTEXITCODE -ne 0\) \{ throw ''icacls failed'' \}',
        "if (`$LASTEXITCODE -ne 0) { break; throw 'unreachable' }"
    "icacls and failure guard are never executed" =
      $ValidEnvironmentText -replace
        '(?ms)^function Invoke-Icacls\(\[string\[\]\] \$Arguments\) \{\r?\n  & icacls @Arguments \| Out-Null\r?\n  if \(\$LASTEXITCODE -ne 0\) \{ throw ''icacls failed'' \}\r?\n\}',
        @'
function Invoke-Icacls([string[]] $Arguments) {
  $NeverRun = { & icacls @Arguments }
  if ($false -and ($LASTEXITCODE -ne 0)) { throw 'icacls failed' }
}
'@
    "external command overwrites icacls exit code" =
      $ValidEnvironmentText -replace
        '(?ms)^function Invoke-Icacls\(\[string\[\]\] \$Arguments\) \{\r?\n  & icacls @Arguments \| Out-Null\r?\n  if \(\$LASTEXITCODE -ne 0\) \{ throw ''icacls failed'' \}\r?\n\}',
        @'
function Invoke-Icacls([string[]] $Arguments) {
  & icacls @Arguments | Out-Null
  & cmd.exe /c exit 0
  if ($LASTEXITCODE -ne 0) { throw 'icacls failed' }
}
'@
    "wildcard-sensitive PowerShell path" = $ValidEnvironmentText -replace
      'Get-FileHash -LiteralPath \$CandidateJar', 'Get-FileHash $CandidateJar'
    "arbitrary pipeline does not bypass file hash path checks" =
      $ValidEnvironmentText -replace
        'Get-FileHash -LiteralPath \$CandidateJar -Algorithm SHA256',
        '$CandidateJar | Get-FileHash -Algorithm SHA256'
    "Resolve-Path alias bypasses literal path" = $ValidEnvironmentText + @'

```powershell
rvpa $CandidateJar | Out-Null
```
'@
    "module-qualified filesystem command bypasses literal path" = $ValidEnvironmentText -replace
      'Get-ChildItem -LiteralPath \$PluginDir -File',
      'Microsoft.PowerShell.Management\Get-ChildItem $PluginDir -File'
    "decoy deployment block masks unsafe order" = @'
```powershell
$CurrentJar = Join-Path $PluginDir 'starx-velocity.jar'
$CandidateJar = 'D:\releases\starx-velocity.jar'
Assert-UworldJarIdentity $CurrentJar
Stop-Service -Name $VelocityService
Copy-Item -LiteralPath $CandidateJar -Destination (Join-Path $PluginDir 'starx-velocity.jar')
Assert-UworldEnvironment $CandidateJar
Start-Service -Name $VelocityService
```

'@ + ($ValidEnvironmentText -replace
      '(?m)^Assert-UworldJarIdentity \$CurrentJar\r?\nInvoke-Icacls -Arguments @\(\$BackupRoot, ''/reset''\)\r?\nInvoke-Icacls -Arguments @\(\$BackupRoot, ''/inheritance:r''\)\r?\nInvoke-Icacls -Arguments @\(\$BackupRoot, ''/grant:r'', ''\*S-1-5-18:\(OI\)\(CI\)F'', ''\*S-1-5-32-544:\(OI\)\(CI\)F''\)\r?\nGet-ChildItem -LiteralPath \$PluginDir -File \| Out-Null\r?\nStop-Service -Name \$VelocityService -ErrorAction Stop -WhatIf:\$false -Confirm:\$false',
      "Invoke-Icacls -Arguments @(`$BackupRoot, '/reset')`r`nInvoke-Icacls -Arguments @(`$BackupRoot, '/inheritance:r')`r`nInvoke-Icacls -Arguments @(`$BackupRoot, '/grant:r', '*S-1-5-18:(OI)(CI)F', '*S-1-5-32-544:(OI)(CI)F')`r`nGet-ChildItem -LiteralPath `$PluginDir -File | Out-Null`r`nStop-Service -Name `$VelocityService -ErrorAction Stop -WhatIf:`$false -Confirm:`$false`r`nAssert-UworldJarIdentity `$CurrentJar")
    "raw icacls outside checked helper" = $ValidEnvironmentText -replace
      '(?m)^Invoke-Icacls -Arguments @\(\$BackupRoot, ''/reset''\)$',
      "& icacls `$BackupRoot /reset`r`nInvoke-Icacls -Arguments @(`$BackupRoot, '/reset')"
    "raw icacls executable outside checked helper" = $ValidEnvironmentText -replace
      '(?m)^Invoke-Icacls -Arguments @\(\$BackupRoot, ''/reset''\)$',
      "& icacls.exe `$BackupRoot /reset`r`nInvoke-Icacls -Arguments @(`$BackupRoot, '/reset')"
    "path-qualified icacls outside checked helper" =
      $ValidEnvironmentText -replace
        '(?m)^Invoke-Icacls -Arguments @\(\$BackupRoot, ''/reset''\)$',
        "& 'C:\Windows\System32\icacls.exe' `$BackupRoot /reset`r`nInvoke-Icacls -Arguments @(`$BackupRoot, '/reset')"
    "dynamic icacls outside checked helper" =
      $ValidEnvironmentText -replace
        '(?m)^Invoke-Icacls -Arguments @\(\$BackupRoot, ''/reset''\)$',
        "& (Join-Path `$env:SystemRoot 'System32\icacls.exe') `$BackupRoot /reset`r`nInvoke-Icacls -Arguments @(`$BackupRoot, '/reset')"
    "PowerShell function overrides icacls" =
      $ValidEnvironmentText -replace
        '(?m)^function Invoke-Icacls',
        "function icacls { `$global:LASTEXITCODE = 0 }`r`nfunction Invoke-Icacls"
    "PowerShell function provider overrides icacls" =
      $ValidEnvironmentText -replace
        '(?m)^function Invoke-Icacls',
        "Set-Item -LiteralPath Function:\icacls -Value { `$global:LASTEXITCODE = 0 }`r`nfunction Invoke-Icacls"
    "icacls invocation has no ACL mutation" =
      $ValidEnvironmentText -replace
        '(?m)^Invoke-Icacls -Arguments @\(\$BackupRoot, ''/reset''\)$',
        "Invoke-Icacls -Arguments @('/?')"
    "icacls grants broad world access" =
      $ValidEnvironmentText -replace
        '(?m)^Invoke-Icacls -Arguments @\(\$BackupRoot, ''/reset''\)$',
        "Invoke-Icacls -Arguments @(`$BackupRoot, '/grant:r', '*S-1-1-0:(OI)(CI)F')"
    "top-level return makes deployment unreachable" =
      $ValidEnvironmentText -replace
        '(?m)^Assert-UworldJarIdentity \$CurrentJar$',
        "return`r`nAssert-UworldJarIdentity `$CurrentJar"
    "conditional return makes deployment unreachable" =
      $ValidEnvironmentText -replace
        '(?m)^Assert-UworldJarIdentity \$CurrentJar$',
        "if (`$true) { return }`r`nAssert-UworldJarIdentity `$CurrentJar"
    "PowerShell doctor wrappers are no-op" =
      ($ValidEnvironmentText `
        -replace '(?ms)^function Assert-UworldJarIdentity\(\[string\] \$Jar\) \{.*?^\}',
          'function Assert-UworldJarIdentity([string] $Jar) { }') `
        -replace '(?ms)^function Assert-UworldEnvironment\(\[string\] \$Jar\) \{.*?^\}',
          'function Assert-UworldEnvironment([string] $Jar) { }'
    "PowerShell doctor runner returns forged PASS early" =
      $ValidEnvironmentText -replace
        '(?m)^function Invoke-UworldDoctor\(\[string\] \$Jar, \[bool\] \$RequireBackend\) \{$',
        (@(
          'function Invoke-UworldDoctor([string] $Jar, [bool] $RequireBackend) {'
          "  return [pscustomobject]@{ ExitCode = 0; Lines = @('UWORLD_ENVIRONMENT=PASS') }"
        ) -join "`r`n")
    "PowerShell doctor runner hides call in false branch" =
      $ValidEnvironmentText -replace
        '(?m)^  \$Output = @\(& \$PowerShell @DoctorArguments 2>&1\)$',
        "  if (`$false) { `$Output = @(& `$PowerShell @DoctorArguments 2>&1) }`r`n  `$Output = @('UWORLD_ENVIRONMENT=PASS')"
    "extra Windows candidate copy hides in helper" =
      $ValidEnvironmentText -replace
        '(?m)^Assert-UworldJarIdentity \$CurrentJar$',
        (@(
          'function Copy-CandidateEarly {'
          '  Copy-Item -LiteralPath $CandidateJar -Destination $CurrentJar -ErrorAction Stop -WhatIf:$false -Confirm:$false'
          '}'
          'Copy-CandidateEarly'
          'Assert-UworldJarIdentity $CurrentJar'
        ) -join "`r`n")
    "untrusted module spoofs icacls helper" = $ValidEnvironmentText -replace
      '& icacls @Arguments', 'Contoso.Tools\icacls @Arguments'
    "missing top-level icacls invocation" = $ValidEnvironmentText -replace
      '(?m)^Invoke-Icacls -Arguments @\(\$BackupRoot, ''/reset''\)\r?\n', ''
    "filesystem alias bypasses literal path" = $ValidEnvironmentText -replace
      'Get-ChildItem -LiteralPath \$PluginDir -File', 'gci $PluginDir -File'
    "commented doctor calls" = $ValidEnvironmentText `
      -replace '(?m)^Assert-UworldJarIdentity \$CurrentJar$',
        "<#`r`nAssert-UworldJarIdentity `$CurrentJar`r`n#>" `
      -replace '(?m)^Assert-UworldEnvironment \$CandidateJar$',
        "<#`r`nAssert-UworldEnvironment `$CandidateJar`r`n#>"
    "alternate fence hides unsafe deployment" = @'
```powershell
$CurrentJar = Join-Path $PluginDir 'starx-velocity.jar'
$CandidateJar = 'D:\releases\starx-velocity.jar'
Assert-UworldJarIdentity $CurrentJar
Invoke-Icacls -Arguments @($BackupRoot, '/reset')
Stop-Service -Name $VelocityService
Copy-Item -LiteralPath $CandidateJar -Destination (Join-Path $PluginDir 'starx-velocity.jar')
Assert-UworldEnvironment $CandidateJar
Start-Service -Name $VelocityService
```

'@ + (($ValidEnvironmentText -replace
      '(?m)^Assert-UworldJarIdentity \$CurrentJar\r?\nInvoke-Icacls -Arguments @\(\$BackupRoot, ''/reset''\)\r?\nInvoke-Icacls -Arguments @\(\$BackupRoot, ''/inheritance:r''\)\r?\nInvoke-Icacls -Arguments @\(\$BackupRoot, ''/grant:r'', ''\*S-1-5-18:\(OI\)\(CI\)F'', ''\*S-1-5-32-544:\(OI\)\(CI\)F''\)\r?\nGet-ChildItem -LiteralPath \$PluginDir -File \| Out-Null\r?\nStop-Service -Name \$VelocityService -ErrorAction Stop -WhatIf:\$false -Confirm:\$false',
      "Invoke-Icacls -Arguments @(`$BackupRoot, '/reset')`r`nInvoke-Icacls -Arguments @(`$BackupRoot, '/inheritance:r')`r`nInvoke-Icacls -Arguments @(`$BackupRoot, '/grant:r', '*S-1-5-18:(OI)(CI)F', '*S-1-5-32-544:(OI)(CI)F')`r`nGet-ChildItem -LiteralPath `$PluginDir -File | Out-Null`r`nStop-Service -Name `$VelocityService -ErrorAction Stop -WhatIf:`$false -Confirm:`$false`r`nAssert-UworldJarIdentity `$CurrentJar") `
      -replace '(?m)(<!-- UWORLD_WINDOWS_DEPLOYMENT -->\r?\n)```powershell',
        '$1````PowerShell' `
      -replace '(?m)(Start-Service -Name \$VelocityService -ErrorAction Stop -WhatIf:\$false -Confirm:\$false\r?\n)```(\r?\n<!-- /UWORLD_WINDOWS_DEPLOYMENT -->)',
        '$1````$2')
    "marked block does not mask unsafe unmarked deployment" =
      $ValidEnvironmentText + @'

```powershell
$CandidateJar = 'D:\releases\starx-velocity.jar'
Stop-Service -Name $VelocityService
Assert-UworldJarIdentity $CurrentJar
Copy-Item -LiteralPath $CandidateJar -Destination (Join-Path $PluginDir 'starx-velocity.jar')
Start-Service -Name $VelocityService
Assert-UworldEnvironment $CandidateJar
```
'@
    "candidate install split across unmarked fences" =
      $ValidEnvironmentText + @'

```powershell
Stop-Service -Name $VelocityService
```

```powershell
Copy-Item -LiteralPath $CandidateJar -Destination (Join-Path $PluginDir 'starx-velocity.jar')
```

```powershell
Start-Service -Name $VelocityService
```
'@
    "unknown info string hides candidate install" =
      $ValidEnvironmentText + @'

```text uworld-deployment
Copy-Item -LiteralPath $CandidateJar -Destination (Join-Path $PluginDir 'starx-velocity.jar')
```
'@
    "unknown info string hides multiline Linux install" =
      $ValidEnvironmentText + @'

```text deployment-example
systemctl stop "$VELOCITY_SERVICE"
inst\
all "$RELEASE_JAR" "$plugin_dir/starx-velocity.jar"
systemctl start "$VELOCITY_SERVICE"
```
'@
    "marked Linux block does not mask unsafe unmarked deployment" =
      $ValidEnvironmentText + @'

```bash
export RELEASE_JAR=/srv/releases/starx-velocity.jar
systemctl stop "$VELOCITY_SERVICE"
assert_uworld_jar_identity "$current_jar"
install "$RELEASE_JAR" "$plugin_dir/starx-velocity.jar"
systemctl start "$VELOCITY_SERVICE"
assert_uworld_environment "$RELEASE_JAR"
```
'@
    "linux multiline string spoofs doctor calls" = Set-LinuxDoctorCalls `
      $ValidEnvironmentText `
      (@(
        "ignored='"
        'assert_uworld_jar_identity "$current_jar"'
        "'"
      ) -join "`r`n") `
      (@(
        "ignored='"
        'assert_uworld_environment "$RELEASE_JAR"'
        "'"
      ) -join "`r`n")
    "linux heredoc spoofs doctor calls" = Set-LinuxDoctorCalls `
      $ValidEnvironmentText `
      (@(
        ": <<'UWORLD_IDENTITY'"
        'assert_uworld_jar_identity "$current_jar"'
        'UWORLD_IDENTITY'
      ) -join "`r`n") `
      (@(
        ": <<'UWORLD_ENVIRONMENT'"
        'assert_uworld_environment "$RELEASE_JAR"'
        'UWORLD_ENVIRONMENT'
      ) -join "`r`n")
    "linux function bodies spoof doctor calls" = Set-LinuxDoctorCalls `
      $ValidEnvironmentText `
      (@(
        'identity_decoy() {'
        '  assert_uworld_jar_identity "$current_jar"'
        '}'
      ) -join "`r`n") `
      (@(
        'environment_decoy() {'
        '  assert_uworld_environment "$RELEASE_JAR"'
        '}'
      ) -join "`r`n")
    "linux hyphenated function bodies spoof doctor calls" = Set-LinuxDoctorCalls `
      $ValidEnvironmentText `
      (@(
        'identity-decoy() {'
        '  assert_uworld_jar_identity "$current_jar"'
        '}'
      ) -join "`r`n") `
      (@(
        'environment-decoy() {'
        '  assert_uworld_environment "$RELEASE_JAR"'
        '}'
      ) -join "`r`n")
    "linux pending function with comments spoofs doctor calls" =
      Set-LinuxDoctorCalls `
        $ValidEnvironmentText `
        (@(
          'identity-decoy()'
          '# legal separation before the body'
          ''
          '{'
          '  assert_uworld_jar_identity "$current_jar"'
          '}'
        ) -join "`r`n") `
        (@(
          'environment-decoy()'
          '# legal separation before the body'
          ''
          '{'
          '  assert_uworld_environment "$RELEASE_JAR"'
          '}'
        ) -join "`r`n")
    "linux function compound brace spoofs identity check" =
      Set-LinuxDoctorCalls `
        $ValidEnvironmentText `
        (@(
          'identity_decoy() {'
          '  if true; then { :; }'
          '  fi'
          '  assert_uworld_jar_identity "$current_jar"'
          '}'
        ) -join "`r`n") `
        'assert_uworld_environment "$RELEASE_JAR"'
    "linux subshell exit spoofs identity check" = Set-LinuxDoctorCalls `
      $ValidEnvironmentText `
      (@(
        '(exit 0'
        'assert_uworld_jar_identity "$current_jar"'
        ')'
      ) -join "`r`n") `
      'assert_uworld_environment "$RELEASE_JAR"'
    "linux coprocess spoofs identity check" = Set-LinuxDoctorCalls `
      $ValidEnvironmentText `
      (@(
        'coproc identity_decoy {'
        '  assert_uworld_jar_identity "$current_jar"'
        '}'
      ) -join "`r`n") `
      'assert_uworld_environment "$RELEASE_JAR"'
    "linux negated group swallows environment failure" =
      Set-LinuxDoctorCalls `
        $ValidEnvironmentText `
        'assert_uworld_jar_identity "$current_jar"' `
        (@(
          '! {'
          '  assert_uworld_environment "$RELEASE_JAR"'
          '}'
        ) -join "`r`n")
    "linux false branches spoof doctor calls" = Set-LinuxDoctorCalls `
      $ValidEnvironmentText `
      (@(
        'if false; then'
        '  assert_uworld_jar_identity "$current_jar"'
        'fi'
      ) -join "`r`n") `
      (@(
        'if false; then'
        '  assert_uworld_environment "$RELEASE_JAR"'
        'fi'
      ) -join "`r`n")
    "linux while body spoofs identity check" = Set-LinuxDoctorCalls `
      $ValidEnvironmentText `
      (@(
        'while false; do'
        '  assert_uworld_jar_identity "$current_jar"'
        'done'
      ) -join "`r`n") `
      'assert_uworld_environment "$RELEASE_JAR"'
    "linux unmatched case arm spoofs identity check" = Set-LinuxDoctorCalls `
      $ValidEnvironmentText `
      (@(
        'case no in'
        '  yes)'
        '    assert_uworld_jar_identity "$current_jar"'
        '    ;;'
        'esac'
      ) -join "`r`n") `
      'assert_uworld_environment "$RELEASE_JAR"'
    "linux case arm group spoofs identity check" = Set-LinuxDoctorCalls `
      $ValidEnvironmentText `
      (@(
        'case no in'
        '  yes) {'
        '    :'
        '  }'
        '  assert_uworld_jar_identity "$current_jar"'
        '  ;;'
        'esac'
      ) -join "`r`n") `
      'assert_uworld_environment "$RELEASE_JAR"'
    "linux function case arm group spoofs identity check" =
      Set-LinuxDoctorCalls `
        $ValidEnvironmentText `
        (@(
          'identity_decoy() {'
          '  case x in'
          '    x) {'
          '      :'
          '    }'
          '    ;;'
          '  esac'
          '  assert_uworld_jar_identity "$current_jar"'
          '}'
        ) -join "`r`n") `
        'assert_uworld_environment "$RELEASE_JAR"'
    "linux install function overrides deployment command" =
      $ValidEnvironmentText.Replace(
        'assert_uworld_jar_identity "$current_jar"',
        @'
install() { :; }
assert_uworld_jar_identity "$current_jar"
'@)
    "linux systemctl function overrides deployment command" =
      $ValidEnvironmentText.Replace(
        'assert_uworld_jar_identity "$current_jar"',
        @'
systemctl() { :; }
assert_uworld_jar_identity "$current_jar"
'@)
    "linux install alias overrides deployment command" =
      $ValidEnvironmentText.Replace(
        'assert_uworld_jar_identity "$current_jar"',
        @'
shopt -s expand_aliases
alias install=:
assert_uworld_jar_identity "$current_jar"
'@)
    "extra Linux candidate install runs before identity" =
      $ValidEnvironmentText.Replace(
        'assert_uworld_jar_identity "$current_jar"',
        @'
install "$RELEASE_JAR" "$current_jar"
assert_uworld_jar_identity "$current_jar"
'@)
    "linux systemctl alias overrides deployment command" =
      $ValidEnvironmentText.Replace(
        'assert_uworld_jar_identity "$current_jar"',
        @'
shopt -s expand_aliases
alias systemctl=:
assert_uworld_jar_identity "$current_jar"
'@)
    "linux doctor wrappers are no-op" =
      ($ValidEnvironmentText `
        -replace '(?ms)^assert_uworld_jar_identity\(\) \{.*?^\}',
          'assert_uworld_jar_identity() { :; }') `
        -replace '(?ms)^assert_uworld_environment\(\) \{.*?^\}',
          'assert_uworld_environment() { :; }'
    "linux doctor wrappers hide requirements in comments" =
      ($ValidEnvironmentText `
        -replace '(?ms)^assert_uworld_jar_identity\(\) \{.*?^\}',
          (@'
assert_uworld_jar_identity() {
  return 0
  # run_uworld_doctor "$candidate" 0
  # plugin_jar_inspection starx_jar_count external_limboapi candidate_hash status=PASS return 1
}
'@).TrimEnd()) `
        -replace '(?ms)^assert_uworld_environment\(\) \{.*?^\}',
          (@'
assert_uworld_environment() {
  return 0
  # run_uworld_doctor "$candidate" 1 UWORLD_DOCTOR_CODE UWORLD_ENVIRONMENT=PASS return 1
}
'@).TrimEnd()
    "extra Linux candidate install uses command wrapper" =
      $ValidEnvironmentText.Replace(
        'assert_uworld_jar_identity "$current_jar"',
        @'
command install "$RELEASE_JAR" "$current_jar"
assert_uworld_jar_identity "$current_jar"
'@)
    "extra Linux candidate install hides in helper" =
      $ValidEnvironmentText.Replace(
        'assert_uworld_jar_identity "$current_jar"',
        @'
copy_candidate_early() {
  install "$RELEASE_JAR" "$current_jar"
}
copy_candidate_early
assert_uworld_jar_identity "$current_jar"
'@)
    "linux conditional identity check" = Set-LinuxDoctorCalls `
      $ValidEnvironmentText `
      'false && assert_uworld_jar_identity "$current_jar"' `
      'assert_uworld_environment "$RELEASE_JAR"'
    "linux multiline conditional identity check" = Set-LinuxDoctorCalls `
      $ValidEnvironmentText `
      (@(
        'false &&'
        'assert_uworld_jar_identity "$current_jar"'
      ) -join "`r`n") `
      'assert_uworld_environment "$RELEASE_JAR"'
    "linux swallowed environment failure" = Set-LinuxDoctorCalls `
      $ValidEnvironmentText `
      'assert_uworld_jar_identity "$current_jar"' `
      'assert_uworld_environment "$RELEASE_JAR" || true'
    "linux double-quoted heredoc spoofs identity check" =
      Set-LinuxDoctorCalls `
        $ValidEnvironmentText `
        (@(
          ': <<"E\OF"'
          'EOF'
          'assert_uworld_jar_identity "$current_jar"'
          'E\OF'
        ) -join "`r`n") `
        'assert_uworld_environment "$RELEASE_JAR"'
    "linux conditional service stop" = $ValidEnvironmentText -replace
      '(?m)^systemctl stop "\$VELOCITY_SERVICE"$',
      'false && systemctl stop "$VELOCITY_SERVICE"'
    "linux conditional candidate install" = $ValidEnvironmentText -replace
      '(?m)^install "\$RELEASE_JAR" "\$plugin_dir/starx-velocity\.jar"$',
      'false && install "$RELEASE_JAR" "$plugin_dir/starx-velocity.jar"'
    "linux exits after stop before candidate install" =
      $ValidEnvironmentText -replace
        '(?m)^install "\$RELEASE_JAR" "\$plugin_dir/starx-velocity\.jar"$',
        ('exit 0' + "`r`n" +
          'install "$RELEASE_JAR" "$plugin_dir/starx-velocity.jar"')
    "linux ANSI-C exit stops after service stop" =
      $ValidEnvironmentText -replace
        '(?m)^install "\$RELEASE_JAR" "\$plugin_dir/starx-velocity\.jar"$',
        ("`$'exit' 0`r`n" +
          'install "$RELEASE_JAR" "$plugin_dir/starx-velocity.jar"')
    "linux installs candidate to wrong destination" =
      $ValidEnvironmentText -replace
        '(?m)^install "\$RELEASE_JAR" "\$plugin_dir/starx-velocity\.jar"$',
        'install "$RELEASE_JAR" /tmp/starx-velocity.jar'
    "linux echo spoofs candidate install" = $ValidEnvironmentText -replace
      '(?m)^install "\$RELEASE_JAR" "\$plugin_dir/starx-velocity\.jar"$',
      'echo install "$RELEASE_JAR" "$plugin_dir/starx-velocity.jar"'
    "linux conditional service start" = $ValidEnvironmentText -replace
      '(?m)^systemctl start "\$VELOCITY_SERVICE"$',
      'false && systemctl start "$VELOCITY_SERVICE"'
    "linux semicolon starts service before doctor" =
      $ValidEnvironmentText -replace
        '(?m)^install "\$RELEASE_JAR" "\$plugin_dir/starx-velocity\.jar"$',
        'install "$RELEASE_JAR" "$plugin_dir/starx-velocity.jar"; systemctl start "$VELOCITY_SERVICE"'
    "escaped heredoc hides unmarked Linux deployment" =
      $ValidEnvironmentText + @'

```bash
: <<\UWORLD_IGNORE
ignored
UWORLD_IGNORE
systemctl stop "$VELOCITY_SERVICE"
install "$RELEASE_JAR" "$plugin_dir/starx-velocity.jar"
systemctl start "$VELOCITY_SERVICE"
```
'@
    "ANSI-C heredoc hides unmarked Linux deployment" =
      $ValidEnvironmentText + @'

```bash
: <<$'UWORLD_IGNORE'
ignored
UWORLD_IGNORE
systemctl stop "$VELOCITY_SERVICE"
:; install "$RELEASE_JAR" "$plugin_dir/starx-velocity.jar"
systemctl start "$VELOCITY_SERVICE"
```
'@
    "ANSI-C escaped heredoc delimiter fails closed" =
      $ValidEnvironmentText + @'

```bash
: <<$'UWORLD\x5fIGNORE'
ignored
UWORLD_IGNORE
systemctl stop "$VELOCITY_SERVICE"
:; install "$RELEASE_JAR" "$plugin_dir/starx-velocity.jar"
systemctl start "$VELOCITY_SERVICE"
```
'@
    "locale translated heredoc delimiter fails closed" =
      $ValidEnvironmentText + @'

```bash
: <<$"UWORLD_IGNORE"
ignored
UWORLD_IGNORE
systemctl stop "$VELOCITY_SERVICE"
:; install "$RELEASE_JAR" "$plugin_dir/starx-velocity.jar"
systemctl start "$VELOCITY_SERVICE"
```
'@
    "continued heredoc delimiter fails closed" =
      $ValidEnvironmentText + @'

```bash
: <<UWORLD_\
IGNORE
ignored
UWORLD_IGNORE
systemctl stop "$VELOCITY_SERVICE"
:; install "$RELEASE_JAR" "$plugin_dir/starx-velocity.jar"
systemctl start "$VELOCITY_SERVICE"
```
'@
    "absolute install path hides unmarked Linux deployment" =
      $ValidEnvironmentText + @'

```bash
systemctl stop "$VELOCITY_SERVICE"
/usr/bin/install "$RELEASE_JAR" "$plugin_dir/starx-velocity.jar"
systemctl start "$VELOCITY_SERVICE"
```
'@
    "command wrapper hides unmarked Linux deployment" =
      $ValidEnvironmentText + @'

```bash
systemctl stop "$VELOCITY_SERVICE"
command install "$RELEASE_JAR" "$plugin_dir/starx-velocity.jar"
systemctl start "$VELOCITY_SERVICE"
```
'@
    "environment wrapper hides unmarked Linux deployment" =
      $ValidEnvironmentText + @'

```bash
systemctl stop "$VELOCITY_SERVICE"
env LC_ALL=C install "$RELEASE_JAR" "$plugin_dir/starx-velocity.jar"
systemctl start "$VELOCITY_SERVICE"
```
'@
    "quoted install path hides unmarked Linux deployment" =
      $ValidEnvironmentText + @'

```bash
systemctl stop "$VELOCITY_SERVICE"
"/usr/bin/install" "$RELEASE_JAR" "$plugin_dir/starx-velocity.jar"
systemctl start "$VELOCITY_SERVICE"
```
'@
    "pipeline hides unmarked Linux deployment" =
      $ValidEnvironmentText + @'

```bash
systemctl stop "$VELOCITY_SERVICE"
true | install "$RELEASE_JAR" "$plugin_dir/starx-velocity.jar"
systemctl start "$VELOCITY_SERVICE"
```
'@
    "background list hides unmarked Linux deployment" =
      $ValidEnvironmentText + @'

```bash
systemctl stop "$VELOCITY_SERVICE"
true & install "$RELEASE_JAR" "$plugin_dir/starx-velocity.jar"
systemctl start "$VELOCITY_SERVICE"
```
'@
    "braced release variable hides unmarked Linux deployment" =
      $ValidEnvironmentText + @'

```bash
systemctl stop "$VELOCITY_SERVICE"
install "${RELEASE_JAR}" "$plugin_dir/starx-velocity.jar"
systemctl start "$VELOCITY_SERVICE"
```
'@
    "release alias hides unmarked Linux deployment" =
      $ValidEnvironmentText + @'

```bash
release="$RELEASE_JAR"
systemctl stop "$VELOCITY_SERVICE"
install "$release" "$plugin_dir/starx-velocity.jar"
systemctl start "$VELOCITY_SERVICE"
```
'@
    "ANSI-C executable hides unmarked Linux deployment" =
      $ValidEnvironmentText + @'

```bash
systemctl stop "$VELOCITY_SERVICE"
$'install' "$RELEASE_JAR" "$plugin_dir/starx-velocity.jar"
systemctl start "$VELOCITY_SERVICE"
```
'@
    "ANSI-C fragment hides unmarked Linux deployment" =
      $ValidEnvironmentText + @'

```bash
systemctl stop "$VELOCITY_SERVICE"
in$'stall' "$RELEASE_JAR" "$plugin_dir/starx-velocity.jar"
systemctl start "$VELOCITY_SERVICE"
```
'@
    "ANSI-C escaped fragment hides unmarked Linux deployment" =
      $ValidEnvironmentText + @'

```bash
systemctl stop "$VELOCITY_SERVICE"
in$'\x73'tall "$RELEASE_JAR" "$plugin_dir/starx-velocity.jar"
systemctl start "$VELOCITY_SERVICE"
```
'@
    "release expansion modifier hides unmarked Linux deployment" =
      $ValidEnvironmentText + @'

```bash
systemctl stop "$VELOCITY_SERVICE"
install "${RELEASE_JAR:?missing}" "$plugin_dir/starx-velocity.jar"
systemctl start "$VELOCITY_SERVICE"
```
'@
    "hardcoded release path hides unmarked Linux deployment" =
      $ValidEnvironmentText + @'

```bash
systemctl stop "$VELOCITY_SERVICE"
install /srv/releases/starx-velocity.jar "$plugin_dir/starx-velocity.jar"
systemctl start "$VELOCITY_SERVICE"
```
'@
    "relative release path hides unmarked Linux deployment" =
      $ValidEnvironmentText + @'

```bash
systemctl stop "$VELOCITY_SERVICE"
install releases/starx-velocity.jar "$plugin_dir/starx-velocity.jar"
systemctl start "$VELOCITY_SERVICE"
```
'@
    "eval hides unmarked Linux deployment" =
      $ValidEnvironmentText + @'

```bash
systemctl stop "$VELOCITY_SERVICE"
eval 'install "$RELEASE_JAR" "$plugin_dir/starx-velocity.jar"'
systemctl start "$VELOCITY_SERVICE"
```
'@
  }
  if ($RollbackFailFastOnly) {
    $FixtureName = "Linux rollback command fails before fail-fast is enabled"
    $FixtureText = $UnsafeFixtures[$FixtureName]
    if ($FixtureText -ceq $ValidEnvironmentText) {
      throw "Unsafe fixture did not alter the environment guide: $FixtureName"
    }
    Write-AsciiFile $EnvironmentDocument $FixtureText
    $FixtureOutput = & $PowerShell -NoProfile -ExecutionPolicy Bypass `
      -File $FixtureGate -DocumentationOnly 2>&1
    if ($LASTEXITCODE -eq 0) {
      throw "Documentation gate accepted unsafe fixture: $FixtureName"
    }
    Write-Output "UWORLD_ROLLBACK_FAIL_FAST_FIXTURE=PASS"
    return
  }
  $AcceptedUnsafeFixtures = [System.Collections.Generic.List[string]]::new()
  foreach ($UnsafeFixture in $UnsafeFixtures.GetEnumerator()) {
    if ($UnsafeFixture.Value -ceq $ValidEnvironmentText) {
      throw "Unsafe fixture did not alter the environment guide: $($UnsafeFixture.Key)"
    }
    Write-AsciiFile $EnvironmentDocument $UnsafeFixture.Value
    $PreviousErrorAction = $ErrorActionPreference
    try {
      $ErrorActionPreference = "Continue"
      $UnsafeOutput = & $PowerShell -NoProfile -ExecutionPolicy Bypass `
        -File $FixtureGate -DocumentationOnly 2>&1
      $UnsafeExitCode = $LASTEXITCODE
    } finally {
      $ErrorActionPreference = $PreviousErrorAction
    }
    if ($UnsafeExitCode -eq 0) {
      $AcceptedUnsafeFixtures.Add($UnsafeFixture.Key)
    }
  }
  $SafeFixtures = [ordered]@{
    "literal path parameter reordering" = $ValidEnvironmentText -replace
      'Get-FileHash -LiteralPath \$CandidateJar -Algorithm SHA256',
      'Get-FileHash -Algorithm SHA256 -LiteralPath $CandidateJar'
    "Get-FileHash InputStream parameter set" = $ValidEnvironmentText -replace
      '\$CandidateSha = \(Get-FileHash -LiteralPath \$CandidateJar -Algorithm SHA256\)\.Hash',
      @'
$CandidateStream = [System.IO.File]::OpenRead($CandidateJar)
try {
  $CandidateSha = (Get-FileHash -InputStream $CandidateStream -Algorithm SHA256).Hash
} finally {
  $CandidateStream.Dispose()
}
'@
    "Get-FileHash pipeline input" = $ValidEnvironmentText -replace
      'Get-FileHash -LiteralPath \$CandidateJar -Algorithm SHA256',
      'Get-Item -LiteralPath $CandidateJar | Get-FileHash -Algorithm SHA256'
    "Get-FileHash parenthesized pipeline input" =
      $ValidEnvironmentText -replace
        'Get-FileHash -LiteralPath \$CandidateJar -Algorithm SHA256',
        '(Get-Item -LiteralPath $CandidateJar) | Get-FileHash -Algorithm SHA256'
    "module-qualified command participates in deployment order" =
      $ValidEnvironmentText -replace
        'Stop-Service -Name \$VelocityService',
        'Microsoft.PowerShell.Management\Stop-Service -Name $VelocityService'
    "module-qualified filesystem command keeps literal path" =
      $ValidEnvironmentText -replace
        'Get-ChildItem -LiteralPath \$PluginDir -File',
        'Microsoft.PowerShell.Management\Get-ChildItem -LiteralPath $PluginDir -File'
    "icacls executable suffix is allowed inside checked helper" =
      $ValidEnvironmentText -replace
        '& icacls @Arguments',
        '& icacls.exe @Arguments'
    "deployment control parameter reordering" =
      $ValidEnvironmentText `
        -replace '(?m)^Stop-Service -Name \$VelocityService -ErrorAction Stop -WhatIf:\$false -Confirm:\$false$',
          'Stop-Service -WhatIf:$false -Name $VelocityService -Confirm:$false -ErrorAction Stop' `
        -replace '(?m)^Copy-Item -LiteralPath \$CandidateJar -Destination \(Join-Path \$PluginDir ''starx-velocity\.jar''\) -ErrorAction Stop -WhatIf:\$false -Confirm:\$false$',
          "Copy-Item -Confirm:`$false -Destination (Join-Path `$PluginDir 'starx-velocity.jar') -LiteralPath `$CandidateJar -WhatIf:`$false -ErrorAction Stop" `
        -replace '(?m)^Start-Service -Name \$VelocityService -ErrorAction Stop -WhatIf:\$false -Confirm:\$false$',
          'Start-Service -Confirm:$false -ErrorAction Stop -Name $VelocityService -WhatIf:$false'
    "ANSI-C quoted heredoc delimiter" = $ValidEnvironmentText.Replace(
      'assert_uworld_jar_identity "$current_jar"',
      @'
: <<$'UWORLD_NOTE'
ignored
UWORLD_NOTE
assert_uworld_jar_identity "$current_jar"
'@)
    "mixed ANSI-C heredoc fragment" = $ValidEnvironmentText.Replace(
      'assert_uworld_jar_identity "$current_jar"',
      @'
: <<UWORLD$'_NOTE'
ignored
UWORLD_NOTE
assert_uworld_jar_identity "$current_jar"
'@)
    "CommonMark tilde fence" = Set-WindowsDeploymentFence `
      $ValidEnvironmentText '~~~powershell' '~~~'
    "CommonMark longer closing fence" = Set-WindowsDeploymentFence `
      $ValidEnvironmentText '```powershell' '````'
    "CommonMark rich info string" = Set-WindowsDeploymentFence `
      $ValidEnvironmentText `
      '```powershell title="Uworld deployment" linenos=1' `
      '```'
  }
  foreach ($Indent in 1..3) {
    $Pad = ' ' * $Indent
    $SafeFixtures["CommonMark ${Indent}-space fence indentation"] =
      Set-WindowsDeploymentFence `
        $ValidEnvironmentText `
        ($Pad + '```powershell') `
        ($Pad + '```')
  }
  $RejectedSafeFixtures = [System.Collections.Generic.List[string]]::new()
  foreach ($SafeFixture in $SafeFixtures.GetEnumerator()) {
    Write-AsciiFile $EnvironmentDocument $SafeFixture.Value
    $SafeOutput = & $PowerShell -NoProfile -ExecutionPolicy Bypass `
      -File $FixtureGate -DocumentationOnly 2>&1
    if ($LASTEXITCODE -ne 0) {
      $RejectedSafeFixtures.Add($SafeFixture.Key)
    }
  }
  if ($AcceptedUnsafeFixtures.Count -ne 0 -or $RejectedSafeFixtures.Count -ne 0) {
    throw "Documentation gate mismatch unsafe=[$($AcceptedUnsafeFixtures -join ', ')] safe=[$($RejectedSafeFixtures -join ', ')]"
  }
  Write-AsciiFile $EnvironmentDocument $ValidEnvironmentText

  Write-AsciiFile (Join-Path $TempRoot "starx-plugins\starx-velocity\build.gradle.kts") @'
tasks.test {
    systemProperty("starx.project.version", project.version.toString())
}
tasks.processResources {
    filesMatching("velocity-plugin.json") {
        expand("version" to project.version)
    }
}
tasks.shadowJar {
    archiveFileName.set("starx-velocity.jar")
}
'@
  $ThinJarOutput = & $PowerShell -NoProfile -ExecutionPolicy Bypass -File $FixtureGate -MetadataOnly 2>&1
  if ($LASTEXITCODE -eq 0 -or ($ThinJarOutput -join "`n") -notmatch "Thin JAR task must be disabled") {
    throw "Metadata gate accepted a build that can emit a thin JAR`n$($ThinJarOutput -join "`n")"
  }

  Write-AsciiFile (Join-Path $TempRoot "starx-plugins\starx-velocity\build.gradle.kts") @'
tasks.test {
    systemProperty("starx.project.version", project.version.toString())
}
tasks.jar {
    enabled = false
}
tasks.processResources {
    filesMatching("velocity-plugin.json") {
        expand("version" to project.version)
    }
}
tasks.shadowJar {
    archiveFileName.set("starx-velocity.jar")
}
'@
  $FullOutput = & $PowerShell -NoProfile -ExecutionPolicy Bypass -File $FixtureGate 2>&1
  $Calls = @([System.IO.File]::ReadAllLines($Log))
  $ExpectedGradleArgs = @(
    ":verifyVelocityBuild606",
    ":starx-plugins:starx-limbo-api:clean",
    ":starx-plugins:starx-common:clean",
    ":starx-plugins:starx-standalone-limbo:clean",
    ":starx-plugins:starx-velocity:clean",
    ":starx-plugins:starx-limbo-api:test",
    ":starx-plugins:starx-common:test",
    ":starx-plugins:starx-standalone-limbo:test",
    ":starx-plugins:starx-velocity:test",
    ":starx-plugins:starx-velocity:compileJava",
    ":starx-plugins:starx-velocity:build",
    ":starx-plugins:starx-velocity:shadowJar",
    "--rerun-tasks",
    "--no-parallel",
    "--no-daemon",
    "--console=plain"
  )
  $ExpectedGradleCall = "gradle|$($ExpectedGradleArgs -join '|')"
  $ExpectedCalls = @("pin", "sync", $ExpectedGradleCall)
  if ($Calls.Count -ne $ExpectedCalls.Count) {
    throw "Expected pin, sync, and Gradle calls, actual=$($Calls -join ', ')`n$($FullOutput -join "`n")"
  }
  for ($Index = 0; $Index -lt $ExpectedCalls.Count; $Index++) {
    if ($Calls[$Index] -cne $ExpectedCalls[$Index]) {
      throw "Unexpected build call at index $Index`nExpected: $($ExpectedCalls[$Index])`nActual:   $($Calls[$Index])"
    }
  }

  foreach ($Mode in @("MetadataOnly", "StaticOnly", "SkipBuild")) {
    if (Test-Path -LiteralPath $Log) {
      Remove-Item -LiteralPath $Log -Force
    }
    $ModeArgument = "-$Mode"
    $ModeOutput = & $PowerShell -NoProfile -ExecutionPolicy Bypass -File $FixtureGate $ModeArgument 2>&1
    if (Test-Path -LiteralPath $Log) {
      $UnexpectedCalls = @([System.IO.File]::ReadAllLines($Log))
      if ($UnexpectedCalls.Count -ne 0) {
        throw "$Mode unexpectedly invoked build helpers: $($UnexpectedCalls -join ', ')"
      }
    }
    if ($Mode -eq "MetadataOnly" -and $LASTEXITCODE -ne 0) {
      throw "Fixture metadata verification failed`n$($ModeOutput -join "`n")"
    }
  }

  $ArtifactDirectory = Join-Path $TempRoot "starx-plugins\starx-velocity\build\libs"
  $Artifact = Join-Path $ArtifactDirectory "starx-velocity.jar"
  Write-EmptyZip (Join-Path $ArtifactDirectory "starx-velocity-9.9.9-test.jar")
  $DuplicateOutput = & $PowerShell -NoProfile -ExecutionPolicy Bypass -File $FixtureGate -StaticOnly -JarPath $Artifact 2>&1
  if ($LASTEXITCODE -eq 0 -or ($DuplicateOutput -join "`n") -notmatch "Unexpected starx-velocity JAR artifacts") {
    throw "Artifact gate did not reject an additional starx-velocity JAR`n$($DuplicateOutput -join "`n")"
  }
} finally {
  if ($HadLogVariable) {
    $env:UWORLD_VERIFY_TEST_LOG = $PreviousLogVariable
  } else {
    Remove-Item Env:UWORLD_VERIFY_TEST_LOG -ErrorAction SilentlyContinue
  }
  $ResolvedTempRoot = [System.IO.Path]::GetFullPath($TempRoot)
  if ($ResolvedTempRoot.StartsWith($TempPrefix, [System.StringComparison]::OrdinalIgnoreCase) -and
      [System.IO.Directory]::Exists($ResolvedTempRoot)) {
    [System.IO.Directory]::Delete($ResolvedTempRoot, $true)
  }
}

Write-Host "PASS: Uworld metadata and build-routing verification gates behave as required"
