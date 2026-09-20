[CmdletBinding()]
param(
  [Parameter(ValueFromRemainingArguments = $true)]
  [string[]] $GradleArgs
)

$ErrorActionPreference = "Stop"
$WorkspaceRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$AsciiDriveCandidates = @('S:', 'G:', 'R:', 'H:', 'T:', 'I:', 'U:', 'J:', 'V:', 'K:', 'W:', 'L:', 'X:', 'M:', 'Y:', 'N:', 'Z:', 'O:', 'Q:', 'P:', 'F:', 'E:', 'D:')

if ([string]::IsNullOrWhiteSpace($env:USERPROFILE)) {
  throw "USERPROFILE is not set"
}

$CacheRoot = [System.IO.Path]::GetFullPath((Join-Path $env:USERPROFILE ".gradle"))
[System.IO.Directory]::CreateDirectory($CacheRoot) | Out-Null

function Get-SubstLine([string]$Drive) {
  return @(& subst.exe) |
    Where-Object { $_.StartsWith($Drive, [System.StringComparison]::OrdinalIgnoreCase) } |
    Select-Object -First 1
}

function Test-AsciiDriveAvailable([string]$Drive) {
  $driveName = $Drive.TrimEnd(':')
  return $null -eq (Get-PSDrive -Name $driveName -ErrorAction SilentlyContinue) -and
    $null -eq (Get-SubstLine $Drive) -and
    -not (Test-Path -LiteralPath "$Drive\")
}

function Get-FreeAsciiDrives([int]$Count) {
  $available = @($AsciiDriveCandidates | Where-Object { Test-AsciiDriveAvailable $_ })
  if ($available.Count -lt $Count) {
    throw "Need $Count free drive letters for Gradle ASCII mappings; found $($available.Count)"
  }
  return @($available | Select-Object -First $Count)
}

function Mount-AsciiDrive([string]$Drive, [string]$Target) {
  $driveName = $Drive.TrimEnd(":")
  $existingDrive = Get-PSDrive -Name $driveName -ErrorAction SilentlyContinue
  if ($null -ne $existingDrive -or $null -ne (Get-SubstLine $Drive)) {
    throw "Drive $Drive is already in use"
  }

  & subst.exe $Drive $Target
  $mountExitCode = $LASTEXITCODE
  $mapping = Get-SubstLine $Drive
  if ($mountExitCode -ne 0 -or $null -eq $mapping) {
    if ($null -ne $mapping) {
      & subst.exe $Drive /d | Out-Null
    }
    throw "Unable to map $Drive to $Target"
  }

  return $mapping
}

function Dismount-AsciiDrive([string]$Drive, [string]$ExpectedMapping) {
  $mapping = Get-SubstLine $Drive
  if ($null -eq $mapping) {
    return
  }
  if ($mapping -ne $ExpectedMapping) {
    throw "Drive $Drive changed while Gradle was running; refusing to remove it"
  }

  & subst.exe $Drive /d | Out-Null
  if ($LASTEXITCODE -ne 0) {
    throw "Unable to remove drive mapping $Drive"
  }
}

$PreviousLocation = (Get-Location).Path
$HadGradleUserHome = Test-Path Env:GRADLE_USER_HOME
$PreviousGradleUserHome = $env:GRADLE_USER_HOME
$WorkspaceMapping = $null
$CacheMapping = $null
$GradleExitCode = 1
$Failure = $null

try {
  $asciiDrives = @(Get-FreeAsciiDrives 2)
  $WorkspaceDrive = $asciiDrives[0]
  $CacheDrive = $asciiDrives[1]
  $WorkspaceMapping = Mount-AsciiDrive $WorkspaceDrive $WorkspaceRoot
  $CacheMapping = Mount-AsciiDrive $CacheDrive $CacheRoot

  Set-Location -LiteralPath "$WorkspaceDrive\"
  $env:GRADLE_USER_HOME = "$CacheDrive\"
  & .\gradlew.bat @GradleArgs
  $GradleExitCode = $LASTEXITCODE
} catch {
  $Failure = $_
} finally {
  try {
    Set-Location -LiteralPath $PreviousLocation
  } catch {
    if ($null -eq $Failure) {
      $Failure = $_
    }
  }

  try {
    if ($HadGradleUserHome) {
      $env:GRADLE_USER_HOME = $PreviousGradleUserHome
    } else {
      Remove-Item Env:GRADLE_USER_HOME -ErrorAction SilentlyContinue
    }
  } catch {
    if ($null -eq $Failure) {
      $Failure = $_
    }
  }

  if ($null -ne $CacheMapping) {
    try {
      Dismount-AsciiDrive $CacheDrive $CacheMapping
    } catch {
      if ($null -eq $Failure) {
        $Failure = $_
      }
    }
  }

  if ($null -ne $WorkspaceMapping) {
    try {
      Dismount-AsciiDrive $WorkspaceDrive $WorkspaceMapping
    } catch {
      if ($null -eq $Failure) {
        $Failure = $_
      }
    }
  }
}

if ($null -ne $Failure) {
  [Console]::Error.WriteLine($Failure.Exception.Message)
  exit 1
}

exit $GradleExitCode
