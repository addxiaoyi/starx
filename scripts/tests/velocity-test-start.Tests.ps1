[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$ScriptPath = Join-Path $Root 'velocity-test\start-test.ps1'
$ScriptText = [System.IO.File]::ReadAllText($ScriptPath)

foreach ($Required in @(
    'factions:25565',
    '"-jar", "paper.jar"',
    '"--nogui"',
    'Test-NetConnection -ComputerName "127.0.0.1" -Port 8788',
    'Velocity -> Factions: 127.0.0.1:25565')) {
  if (-not $ScriptText.Contains($Required)) {
    throw "Velocity test starter is missing required runtime marker: $Required"
  }
}

foreach ($Forbidden in @(
    '25566',
    '25567',
    '.paper-cache/paper-1.21.11-132.jar')) {
  if ($ScriptText.Contains($Forbidden)) {
    throw "Velocity test starter still contains stale runtime marker: $Forbidden"
  }
}

Write-Host 'PASS: Velocity test starter matches the Paper and Velocity runtime configuration'
