$ErrorActionPreference = "Stop"

$WorkspaceRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$Runner = Join-Path $WorkspaceRoot "scripts/invoke-gradle-ascii.ps1"

function Get-SubstLine([string]$Drive) {
  return @(& subst.exe) |
    Where-Object { $_.StartsWith($Drive, [System.StringComparison]::OrdinalIgnoreCase) } |
    Select-Object -First 1
}

function Get-SubstMappings {
  return @(& subst.exe | ForEach-Object { $_.Trim() } | Where-Object { $_ })
}

function Assert-SubstMappingsUnchanged([string[]]$ExpectedMappings) {
  $actualMappings = @(Get-SubstMappings)
  if (@(Compare-Object -ReferenceObject $ExpectedMappings -DifferenceObject $actualMappings).Count -ne 0) {
    throw "Runner changed persistent drive mappings. Before: $($ExpectedMappings -join '; '); after: $($actualMappings -join '; ')"
  }
}

function Test-DriveAvailable([string]$Drive) {
  $driveName = $Drive.TrimEnd(':')
  return $null -eq (Get-PSDrive -Name $driveName -ErrorAction SilentlyContinue) -and
    $null -eq (Get-SubstLine $Drive) -and
    -not (Test-Path -LiteralPath "$Drive\")
}

function New-DriveFixture([string]$Drive) {
  $fixture = Join-Path ([System.IO.Path]::GetTempPath()) ("starx-gradle-drive-" + [guid]::NewGuid())
  [System.IO.Directory]::CreateDirectory($fixture) | Out-Null
  & subst.exe $Drive $fixture
  if ($LASTEXITCODE -ne 0) {
    throw "Unable to create test mapping $Drive"
  }

  return [pscustomobject]@{
    Path = $fixture
    Mapping = Get-SubstLine $Drive
  }
}

function Remove-DriveFixture([string]$Drive, $Fixture) {
  $mapping = Get-SubstLine $Drive
  if ($mapping -eq $Fixture.Mapping) {
    & subst.exe $Drive /d | Out-Null
    if ($LASTEXITCODE -ne 0) {
      throw "Unable to remove test mapping $Drive"
    }
  }

  $tempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
  $fixturePath = [System.IO.Path]::GetFullPath($Fixture.Path)
  if (-not $fixturePath.StartsWith($tempRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to remove fixture outside the system temp directory: $fixturePath"
  }
  Remove-Item -LiteralPath $fixturePath -Recurse -Force
}

function Invoke-Runner([string[]]$RunnerArgs) {
  $previousErrorPreference = $ErrorActionPreference
  try {
    $ErrorActionPreference = "Continue"
    $output = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $Runner @RunnerArgs 2>&1 |
      Out-String
    $exitCode = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $previousErrorPreference
  }
  return [pscustomobject]@{
    ExitCode = $exitCode
    Output = $output
  }
}

if (-not (Test-Path -LiteralPath $Runner -PathType Leaf)) {
  throw "Gradle ASCII runner is missing: $Runner"
}

$initialMappings = @(Get-SubstMappings)

$version = Invoke-Runner @("--version")
if ($version.ExitCode -ne 0) {
  throw "Gradle runner failed with exit $($version.ExitCode):`n$($version.Output)"
}
if ($version.Output -notmatch "Gradle \d+\.\d+") {
  throw "Gradle runner returned unexpected output:`n$($version.Output)"
}
Assert-SubstMappingsUnchanged $initialMappings

foreach ($occupiedPreferredDrive in @('S:', 'G:') | Where-Object { Test-DriveAvailable $_ }) {
  $fixture = $null
  try {
    $fixture = New-DriveFixture $occupiedPreferredDrive
    $fallback = Invoke-Runner @("--version")
    if ($fallback.ExitCode -ne 0) {
      throw "Runner did not select free ASCII mappings after $occupiedPreferredDrive was occupied:`n$($fallback.Output)"
    }
    if ((Get-SubstLine $occupiedPreferredDrive) -ne $fixture.Mapping) {
      throw "Runner removed or changed the existing $occupiedPreferredDrive mapping"
    }
    Assert-SubstMappingsUnchanged (@($initialMappings) + $fixture.Mapping)
  } finally {
    if ($null -ne $fixture) {
      Remove-DriveFixture $occupiedPreferredDrive $fixture
    }
  }
}

Assert-SubstMappingsUnchanged $initialMappings
Write-Host "PASS: Gradle selects free ASCII mappings and preserves existing mappings"
