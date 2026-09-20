[CmdletBinding()]
param(
  [switch] $SkipGradle,
  [switch] $CheckLiveTestStack
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$RepoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$modulePath = Join-Path $PSScriptRoot 'StarX.Production.psm1'

Write-Output '### POWERSHELL AST'
$scriptFiles = @(Get-ChildItem -LiteralPath $PSScriptRoot -File -Recurse | Where-Object { $_.Extension -in @('.ps1','.psm1') })
foreach ($file in $scriptFiles) {
  $tokens = $null
  $errors = $null
  [Management.Automation.Language.Parser]::ParseFile($file.FullName,[ref]$tokens,[ref]$errors) | Out-Null
  if ($errors.Count -gt 0) {
    $first = $errors[0]
    throw "PowerShell AST failed: $($file.FullName):$($first.Extent.StartLineNumber):$($first.Extent.StartColumnNumber) $($first.Message)"
  }
}
Write-Output ("PRODUCTION_AST_FILES=$($scriptFiles.Count)")

Write-Output '### CONFIG TEMPLATE'
Import-Module $modulePath -Force
$config = Import-StarXProductionConfig (Join-Path $PSScriptRoot 'production.config.example.json')
if (-not [bool]$config.security.velocityOnlineMode) { throw 'Production example must default to Velocity online mode' }
if ([string]$config.network.httpAddress -notin @('127.0.0.1','localhost','::1')) { throw 'Production example HTTP API is not loopback-only' }
if ([string]$config.network.rconAddress -notin @('127.0.0.1','localhost','::1')) { throw 'Production example RCON is not loopback-only' }
Write-Output 'PRODUCTION_CONFIG_TEMPLATE=PASS'

Write-Output '### CONTROL PLANE FAILURE INJECTION'
& (Join-Path $PSScriptRoot 'tests\Production.ControlPlane.Tests.ps1')

if (-not $SkipGradle) {
  Write-Output '### UNIVERSAL PLUGIN TESTS'
  $gradleScript = Join-Path $RepoRoot 'scripts\invoke-gradle-ascii.ps1'
  & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $gradleScript ':starx-plugins:starx-velocity:test' ':starx-plugins:starx-server:test' ':starx-plugins:starx-universal:check' '--no-daemon'
  if ($LASTEXITCODE -ne 0) { throw "Universal plugin Gradle verification failed: exit=$LASTEXITCODE" }
}

if ($CheckLiveTestStack) {
  Write-Output '### EXISTING LIVE TEST STACK (READ ONLY)'
  $statusScript = Join-Path $RepoRoot 'tmp\status-live-test-server.ps1'
  if (Test-Path -LiteralPath $statusScript -PathType Leaf) {
    & $statusScript
  } else {
    Write-Output 'LIVE_TEST_STATUS=SKIPPED_NOT_FOUND'
  }
}

Write-Output 'STARX_PRODUCTION_CONTROL_PLANE_VERIFICATION=PASS'
