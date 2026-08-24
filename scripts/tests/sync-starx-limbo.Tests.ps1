$ErrorActionPreference = "Stop"

$WorkspaceRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$SyncScript = Join-Path $WorkspaceRoot "scripts/sync-starx-limbo.ps1"
$SourceRepo = Join-Path $WorkspaceRoot "LimboAPI-source"
$ArtifactPath = Join-Path $WorkspaceRoot "starx-plugins/starx-standalone-limbo/vendor/limboapi-1.1.27-SNAPSHOT.jar"
$OverrideRoot = "starx-plugins/starx-standalone-limbo/src/main/java/io/github/addxiaoyi/starx/limbo"
$ControlPath = "$OverrideRoot/server/LimboSessionHandlerImpl.java"
$OverridePaths = @(
  "$OverrideRoot/LimboAPI.java",
  "$OverrideRoot/server/LimboImpl.java",
  "$OverrideRoot/server/LimboPlayerImpl.java",
  "$OverrideRoot/server/CachedPackets.java",
  "$OverrideRoot/injection/packet/UpsertPlayerInfoHook.java"
)
$Sentinel = "starx-embedded-override`n"

function New-SyncFixture([string[]]$Paths) {
  $fixture = Join-Path ([System.IO.Path]::GetTempPath()) ("starx-limbo-sync-" + [guid]::NewGuid())
  $scriptDir = Join-Path $fixture "scripts"
  [System.IO.Directory]::CreateDirectory($scriptDir) | Out-Null
  Copy-Item -LiteralPath $SyncScript -Destination (Join-Path $scriptDir "sync-starx-limbo.ps1")

  foreach ($relativePath in $Paths) {
    $target = Join-Path $fixture $relativePath
    [System.IO.Directory]::CreateDirectory((Split-Path -Parent $target)) | Out-Null
    [System.IO.File]::WriteAllText($target, $Sentinel, [System.Text.UTF8Encoding]::new($false))
  }

  return $fixture
}

function Remove-SyncFixture([string]$Fixture) {
  $tempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
  $resolvedFixture = [System.IO.Path]::GetFullPath($Fixture)
  if (-not $resolvedFixture.StartsWith($tempRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to remove fixture outside the system temp directory: $resolvedFixture"
  }
  Remove-Item -LiteralPath $resolvedFixture -Recurse -Force
}

$preserveFixture = New-SyncFixture $OverridePaths
try {
  $fixtureScript = Join-Path $preserveFixture "scripts/sync-starx-limbo.ps1"
  $fixtureArtifact = Join-Path $preserveFixture "starx-plugins/starx-standalone-limbo/vendor/limboapi-1.1.27-SNAPSHOT.jar"
  [System.IO.Directory]::CreateDirectory((Split-Path -Parent $fixtureArtifact)) | Out-Null
  Copy-Item -LiteralPath $ArtifactPath -Destination $fixtureArtifact
  & $fixtureScript -SourceRepo $SourceRepo *> $null

  foreach ($relativePath in $OverridePaths) {
    $actual = [System.IO.File]::ReadAllText((Join-Path $preserveFixture $relativePath))
    if ($actual -ne $Sentinel) {
      throw "Sync overwrote embedded Limbo override: $relativePath"
    }
  }

  $controlTarget = Join-Path $preserveFixture $ControlPath
  if (-not (Test-Path -LiteralPath $controlTarget -PathType Leaf)) {
    throw "Sync did not generate upstream control file: $ControlPath"
  }
} finally {
  Remove-SyncFixture $preserveFixture
}

$missingOverride = $OverridePaths[0]
$missingFixture = New-SyncFixture ($OverridePaths | Where-Object { $_ -ne $missingOverride })
try {
  $fixtureScript = Join-Path $missingFixture "scripts/sync-starx-limbo.ps1"
  $expectedFailure = "Embedded Limbo override is missing:"
  $failure = $null

  try {
    & $fixtureScript -SourceRepo $SourceRepo -ArtifactPath $ArtifactPath *> $null
  } catch {
    $failure = $_.Exception.Message
  }

  if ($null -eq $failure -or -not $failure.StartsWith($expectedFailure, [System.StringComparison]::Ordinal)) {
    throw "Sync did not fail clearly for missing override: $missingOverride"
  }
} finally {
  Remove-SyncFixture $missingFixture
}

$artifactFixture = New-SyncFixture $OverridePaths
try {
  $fixtureScript = Join-Path $artifactFixture "scripts/sync-starx-limbo.ps1"
  $controlTarget = Join-Path $artifactFixture $ControlPath
  [System.IO.Directory]::CreateDirectory((Split-Path -Parent $controlTarget)) | Out-Null
  [System.IO.File]::WriteAllText($controlTarget, $Sentinel, [System.Text.UTF8Encoding]::new($false))
  $missingArtifact = Join-Path $artifactFixture "missing-limboapi.jar"
  $failure = $null

  try {
    & $fixtureScript -SourceRepo $SourceRepo -ArtifactPath $missingArtifact *> $null
  } catch {
    $failure = $_.Exception.Message
  }

  if ($null -eq $failure -or -not $failure.StartsWith("LimboAPI artifact is missing:", [System.StringComparison]::Ordinal)) {
    throw "Sync did not fail clearly for a missing artifact."
  }

  $actual = [System.IO.File]::ReadAllText($controlTarget)
  if ($actual -ne $Sentinel) {
    throw "Sync modified sources before validating the LimboAPI artifact."
  }
} finally {
  Remove-SyncFixture $artifactFixture
}

Write-Host "sync-starx-limbo override tests passed."
