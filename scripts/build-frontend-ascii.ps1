param(
  [switch]$SkipTests,
  [switch]$SkipTypecheck,
  [switch]$FrontendOnly
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$workspace = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$frontend = (Resolve-Path (Join-Path $workspace "重构\starmc")).Path
$drive = $null

foreach ($letter in [char[]]([char]'S'..[char]'Z')) {
  $candidate = "$letter`:"
  if (-not (Test-Path "$candidate\")) {
    $drive = $candidate
    break
  }
}

if (-not $drive) {
  throw "No free drive letter is available for the ASCII build workspace."
}

function Invoke-Checked {
  param(
    [Parameter(Mandatory)]
    [string]$Command,
    [string[]]$Arguments = @()
  )

  & $Command @Arguments
  if ($LASTEXITCODE -ne 0) {
    throw "$Command failed with exit code $LASTEXITCODE."
  }
}

try {
  Invoke-Checked "subst.exe" @($drive, $frontend)
  Push-Location "$drive\"

  if (-not $SkipTests) {
    Invoke-Checked "npm.cmd" @("run", "test:unit")
  }
  if (-not $SkipTypecheck) {
    Invoke-Checked "npm.cmd" @("run", "lint")
  }
  $buildScript = if ($FrontendOnly) { "build:frontend" } else { "build" }
  Invoke-Checked "npm.cmd" @("run", $buildScript)
} finally {
  if ((Get-Location).Path -like "$drive*") {
    Pop-Location
  }
  if ($drive) {
    & subst.exe $drive /D | Out-Null
  }
}
