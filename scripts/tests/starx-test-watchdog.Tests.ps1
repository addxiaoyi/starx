$ErrorActionPreference = 'Stop'

$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$scriptPath = Join-Path $root 'scripts\starx-test-watchdog.ps1'
if (-not (Test-Path -LiteralPath $scriptPath -PathType Leaf)) {
  throw "watchdog script is missing: $scriptPath"
}

$source = Get-Content -Raw -LiteralPath $scriptPath
$required = @(
  'Global\StarXTestWatchdog',
  'velocity-3.5.0-SNAPSHOT-606.jar',
  '.paper-runtime\instances\factions',
  'paper.jar',
  '25565',
  '25579',
  '8788',
  '-WindowStyle Hidden',
  '[switch] $Once',
  '[switch] $DryRun'
  'Wait-Port 25565 240'
  'Write-Warning $message'
)
foreach ($pattern in $required) {
  if ($source -notmatch [regex]::Escape($pattern)) {
    throw "watchdog contract is missing: $pattern"
  }
}
if ($source -match 'Write-Error\s+\$message') {
  throw 'watchdog must keep polling after a recoverable service-start failure'
}
if ($source -match 'finally\s*\{[\s\S]*Stop-Process') {
  throw 'watchdog must not kill owned game processes when the supervisor exits'
}

$probe = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $scriptPath -Once -DryRun 2>&1
if ($LASTEXITCODE -ne 0) {
  throw "watchdog must resolve its default runtime path when launched by Windows PowerShell: $probe"
}

Write-Host 'starx-test-watchdog.Tests.ps1: PASS'
