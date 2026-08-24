[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [ValidatePattern('^[A-Fa-f0-9]{64}$')]
  [string] $ExpectedVelocitySha256,
  [Parameter(Mandatory = $true)]
  [ValidatePattern('^[A-Fa-f0-9]{64}$')]
  [string] $ExpectedServerSha256,
  [string] $VelocityHome,
  [string] $PaperHome
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ([string]::IsNullOrWhiteSpace($VelocityHome)) {
  $VelocityHome = Join-Path $Root 'velocity-test'
}
if ([string]::IsNullOrWhiteSpace($PaperHome)) {
  $PaperHome = Join-Path $VelocityHome '.paper-runtime\instances\factions'
}
$VelocityHome = [IO.Path]::GetFullPath($VelocityHome)
$PaperHome = [IO.Path]::GetFullPath($PaperHome)

$VelocityCandidate = Join-Path $Root 'starx-plugins\starx-velocity\build\libs\starx-velocity.jar'
$ServerCandidate = Join-Path $Root 'starx-plugins\starx-server\build\libs\starx-server.jar'
$VelocityInstalled = Join-Path $VelocityHome 'plugins\starx-velocity.jar'
$ServerInstalled = Join-Path $PaperHome 'plugins\starx-server.jar'
$VelocityPidFile = Join-Path $VelocityHome '.velocity-current.pid'
$PaperPidFile = Join-Path $VelocityHome '.paper-current.pid'

function Assert-FileHash([string] $Path, [string] $Expected, [string] $Label) {
  if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
    throw "$Label does not exist: $Path"
  }
  $Actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
  if ($Actual -cne $Expected.ToUpperInvariant()) {
    throw "$Label SHA-256 mismatch expected=$Expected actual=$Actual"
  }
}

function Assert-PidStopped([string] $PidFile, [string] $Label) {
  if (-not (Test-Path -LiteralPath $PidFile -PathType Leaf)) {
    return
  }
  $Raw = (Get-Content -LiteralPath $PidFile -Raw).Trim()
  [int] $ProcessId = 0
  if ([int]::TryParse($Raw, [ref] $ProcessId) -and
      $null -ne (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)) {
    throw "$Label is still running as PID $ProcessId"
  }
}

function Install-Atomic([string] $Source, [string] $Target, [string] $ExpectedHash) {
  $Directory = Split-Path -Parent $Target
  [IO.Directory]::CreateDirectory($Directory) | Out-Null
  $Temporary = "$Target.new"
  if (Test-Path -LiteralPath $Target -PathType Leaf) {
    $ExistingHash = (Get-FileHash -LiteralPath $Target -Algorithm SHA256).Hash
    if ($ExistingHash -ceq $ExpectedHash.ToUpperInvariant()) {
      Remove-Item -LiteralPath $Temporary -Force -ErrorAction SilentlyContinue
      return
    }
  }
  Copy-Item -LiteralPath $Source -Destination $Temporary -Force
  Assert-FileHash $Temporary $ExpectedHash 'Temporary installed artifact'
  if (Test-Path -LiteralPath $Target -PathType Leaf) {
    $ReplaceBackup = "$Target.replace.bak"
    if (Test-Path -LiteralPath $ReplaceBackup) {
      Remove-Item -LiteralPath $ReplaceBackup -Force
    }
    [IO.File]::Replace($Temporary, $Target, $ReplaceBackup)
    Remove-Item -LiteralPath $ReplaceBackup -Force -ErrorAction SilentlyContinue
  } else {
    [IO.File]::Move($Temporary, $Target)
  }
  Assert-FileHash $Target $ExpectedHash 'Installed artifact'
}

function Test-PortFree([int] $Port) {
  $Listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Port)
  try {
    $Listener.Start()
    return $true
  } catch [Net.Sockets.SocketException] {
    return $false
  } finally {
    try { $Listener.Stop() } catch { }
  }
}

function Assert-PortFree([int] $Port, [string] $Label) {
  if (-not (Test-PortFree $Port)) {
    throw "$Label port 127.0.0.1:$Port is already occupied"
  }
}

function Test-PortConnect([int] $Port) {
  $Client = [Net.Sockets.TcpClient]::new()
  try {
    $Task = $Client.ConnectAsync('127.0.0.1', $Port)
    return $Task.Wait(500) -and $Client.Connected
  } catch {
    return $false
  } finally {
    $Client.Dispose()
  }
}

function Read-StartupLog([string] $OutLog, [string] $ErrLog) {
  $Builder = [Text.StringBuilder]::new()
  foreach ($Path in @($OutLog, $ErrLog)) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { continue }
    $Stream = $null
    $Reader = $null
    try {
      $Stream = [IO.File]::Open(
          $Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
      $Reader = [IO.StreamReader]::new($Stream)
      [void] $Builder.AppendLine($Reader.ReadToEnd())
    } catch [IO.IOException] {
      # The redirect file can be momentarily unavailable while the process starts.
    } finally {
      if ($null -ne $Reader) { $Reader.Dispose() }
      elseif ($null -ne $Stream) { $Stream.Dispose() }
    }
  }
  return $Builder.ToString()
}

function Wait-Startup(
    [int] $ProcessId,
    [string] $OutLog,
    [string] $ErrLog,
    [string] $SuccessPattern,
    [int] $Port,
    [int] $TimeoutSeconds,
    [string] $Label) {
  $Deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
  $FatalPattern = '(?im)(Address already in use|Failed to start the minecraft server|Exception in thread "main"|Unable to access jarfile|session\.lock)'
  while ([DateTime]::UtcNow -lt $Deadline) {
    $Running = $null -ne (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)
    $LogText = Read-StartupLog $OutLog $ErrLog
    if ($LogText -match $FatalPattern) {
      throw "$Label startup log contains a fatal error for PID $ProcessId"
    }
    if (-not $Running) {
      $Tail = ($LogText -split '\r?\n' | Select-Object -Last 25) -join [Environment]::NewLine
      throw "$Label PID $ProcessId exited before startup completed`n$Tail"
    }
    if ($LogText -match $SuccessPattern -and (Test-PortConnect $Port)) {
      return
    }
    Start-Sleep -Milliseconds 250
  }
  throw "$Label PID $ProcessId did not emit the startup marker and listen on port $Port within ${TimeoutSeconds}s"
}

function Stop-StartedId([int] $ProcessId) {
  if ($ProcessId -le 0) { return }
  try {
    if ($null -ne (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)) {
      Stop-Process -Id $ProcessId -Force -ErrorAction SilentlyContinue
    }
  } catch {
    # Preserve the original deployment failure.
  }
}

Assert-FileHash $VelocityCandidate $ExpectedVelocitySha256 'Velocity candidate'
Assert-FileHash $ServerCandidate $ExpectedServerSha256 'Server candidate'
Assert-PidStopped $VelocityPidFile 'Velocity test instance'
Assert-PidStopped $PaperPidFile 'Paper test instance'
Assert-PortFree 25565 'Paper'
Assert-PortFree 25579 'Velocity'
Assert-PortFree 8788 'StarX HTTP API'
Remove-Item -LiteralPath $VelocityPidFile -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $PaperPidFile -Force -ErrorAction SilentlyContinue

$Stamp = [DateTimeOffset]::Now.ToString('yyyyMMdd-HHmmss')
$Backup = Join-Path $VelocityHome "backups\starx\$Stamp"
[IO.Directory]::CreateDirectory($Backup) | Out-Null
if (Test-Path -LiteralPath $VelocityInstalled -PathType Leaf) {
  Copy-Item -LiteralPath $VelocityInstalled -Destination (Join-Path $Backup 'starx-velocity.jar')
}
if (Test-Path -LiteralPath $ServerInstalled -PathType Leaf) {
  Copy-Item -LiteralPath $ServerInstalled -Destination (Join-Path $Backup 'starx-server.jar')
}
Set-Content -LiteralPath (Join-Path $VelocityHome '.last-deploy-backup') -Value $Backup -Encoding UTF8

Install-Atomic $VelocityCandidate $VelocityInstalled $ExpectedVelocitySha256
Install-Atomic $ServerCandidate $ServerInstalled $ExpectedServerSha256

$PaperOut = Join-Path $VelocityHome '.paper-current.log'
$PaperErr = Join-Path $VelocityHome 'paper-current.err.log'
$VelocityOut = Join-Path $VelocityHome '.velocity-current.log'
$VelocityErr = Join-Path $VelocityHome 'velocity-current.err.log'
foreach ($Log in @($PaperOut, $PaperErr, $VelocityOut, $VelocityErr)) {
  if (Test-Path -LiteralPath $Log) { Remove-Item -LiteralPath $Log -Force }
}

$Paper = $null
$Velocity = $null
$PaperProcessId = 0
$VelocityProcessId = 0
try {
  $Paper = Start-Process -FilePath 'java' `
    -ArgumentList @('-Xms512M', '-Xmx1G', '-Dcom.mojang.eula.agree=true', '-jar', 'paper.jar', '--nogui') `
    -WorkingDirectory $PaperHome -RedirectStandardOutput $PaperOut `
    -RedirectStandardError $PaperErr -WindowStyle Hidden -PassThru
  $PaperProcessId = $Paper.Id
  Wait-Startup $PaperProcessId $PaperOut $PaperErr 'Done \([0-9.]+s\)' 25565 180 'Paper'

  $Velocity = Start-Process -FilePath 'java' `
    -ArgumentList @('-Xms512M', '-Xmx1G', '-Djava.awt.headless=true', '-Dterminal.jline=false', '-Dterminal.ansi=false', '-jar', 'velocity-3.5.0-SNAPSHOT-606.jar') `
    -WorkingDirectory $VelocityHome -RedirectStandardOutput $VelocityOut `
    -RedirectStandardError $VelocityErr -WindowStyle Hidden -PassThru
  $VelocityProcessId = $Velocity.Id
  Wait-Startup $VelocityProcessId $VelocityOut $VelocityErr 'Uworld runtime ready' 25579 90 'Velocity'
  if (-not (Test-PortConnect 8788)) {
    throw "StarX HTTP API did not listen on 127.0.0.1:8788 for Velocity PID $VelocityProcessId"
  }

  Set-Content -LiteralPath $PaperPidFile -Value $PaperProcessId -Encoding ASCII
  Set-Content -LiteralPath $VelocityPidFile -Value $VelocityProcessId -Encoding ASCII
} catch {
  Stop-StartedId $VelocityProcessId
  Stop-StartedId $PaperProcessId
  Remove-Item -LiteralPath $VelocityPidFile -Force -ErrorAction SilentlyContinue
  Remove-Item -LiteralPath $PaperPidFile -Force -ErrorAction SilentlyContinue
  throw
}

Assert-FileHash $VelocityInstalled $ExpectedVelocitySha256 'Installed Velocity plugin'
Assert-FileHash $ServerInstalled $ExpectedServerSha256 'Installed Server plugin'
Write-Host "BACKUP=$Backup"
Write-Host "PAPER_PID=$PaperProcessId"
Write-Host "VELOCITY_PID=$VelocityProcessId"
Write-Host "VELOCITY_SHA256=$($ExpectedVelocitySha256.ToUpperInvariant())"
Write-Host "SERVER_SHA256=$($ExpectedServerSha256.ToUpperInvariant())"
Write-Host 'STARX_LOCAL_DEPLOY=PASS'
