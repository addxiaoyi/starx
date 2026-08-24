[CmdletBinding()]
param(
  [string] $VelocityHome,
  [switch] $Once,
  [switch] $DryRun,
  [int] $PollSeconds = 10
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptHome = Split-Path -Parent $MyInvocation.MyCommand.Path
if ([string]::IsNullOrWhiteSpace($VelocityHome)) {
  $VelocityHome = Join-Path $scriptHome '..\velocity-test'
}
$VelocityHome = [IO.Path]::GetFullPath($VelocityHome)
$PaperHome = Join-Path $VelocityHome '.paper-runtime\instances\factions'
$VelocityJar = Join-Path $VelocityHome 'velocity-3.5.0-SNAPSHOT-606.jar'
$PaperJar = Join-Path $PaperHome 'paper.jar'
$LogRoot = Join-Path $VelocityHome 'watchdog'
$MutexName = 'Global\StarXTestWatchdog'

foreach ($required in @($VelocityHome, $PaperHome, $VelocityJar, $PaperJar)) {
  if (-not (Test-Path -LiteralPath $required)) {
    throw "StarX watchdog input is missing: $required"
  }
}
if ($PollSeconds -lt 2) {
  throw 'PollSeconds must be at least 2'
}

[IO.Directory]::CreateDirectory($LogRoot) | Out-Null
$mutex = [Threading.Mutex]::new($false, $MutexName)
if (-not $mutex.WaitOne(0)) {
  Write-Host 'Another StarX test watchdog is already running'
  exit 0
}

function Test-TcpPort([int] $Port) {
  $client = [Net.Sockets.TcpClient]::new()
  try {
    $connect = $client.ConnectAsync('127.0.0.1', $Port)
    return $connect.Wait(750) -and $client.Connected
  } catch {
    return $false
  } finally {
    $client.Dispose()
  }
}

function Start-Paper {
  if ($DryRun) {
    Write-Host 'DRY_RUN start Paper on 25565'
    return
  }
  $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
  Start-Process -FilePath 'java' `
    -ArgumentList @('-Xms1G', '-Xmx2G', '-XX:+UseG1GC', '-jar', 'paper.jar', '--nogui') `
    -WorkingDirectory $PaperHome `
    -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $LogRoot "paper-$stamp.out.log") `
    -RedirectStandardError (Join-Path $LogRoot "paper-$stamp.err.log") | Out-Null
  Write-Host "Started Paper at $(Get-Date -Format o)"
}

function Start-Velocity {
  if ($DryRun) {
    Write-Host 'DRY_RUN start Velocity on 25579 and 8788'
    return
  }
  $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
  Start-Process -FilePath 'java' `
    -ArgumentList @('-Xms256M', '-Xmx512M', '-XX:+UseG1GC', '-jar', 'velocity-3.5.0-SNAPSHOT-606.jar') `
    -WorkingDirectory $VelocityHome `
    -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $LogRoot "velocity-$stamp.out.log") `
    -RedirectStandardError (Join-Path $LogRoot "velocity-$stamp.err.log") | Out-Null
  Write-Host "Started Velocity at $(Get-Date -Format o)"
}

function Wait-Port([int] $Port, [int] $TimeoutSeconds) {
  $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
  while ([DateTime]::UtcNow -lt $deadline) {
    if (Test-TcpPort $Port) { return $true }
    Start-Sleep -Seconds 2
  }
  return $false
}

function Ensure-StarXRuntime {
  $paperReady = Test-TcpPort 25565
  if (-not $paperReady) {
    Start-Paper
    if (-not $DryRun) {
      $paperReady = Wait-Port 25565 240
      if (-not $paperReady) { throw 'Paper did not become ready on 25565' }
    }
  }

  $velocityMinecraft = Test-TcpPort 25579
  $velocityHttp = Test-TcpPort 8788
  if (-not ($velocityMinecraft -and $velocityHttp)) {
    Start-Velocity
    if (-not $DryRun) {
      if (-not (Wait-Port 25579 60) -or -not (Wait-Port 8788 60)) {
        throw 'Velocity did not become ready on 25579 and 8788'
      }
    }
  }
}

try {
  do {
    try {
      Ensure-StarXRuntime
    } catch {
      $message = "$(Get-Date -Format o) $($_.Exception.Message)"
      Add-Content -LiteralPath (Join-Path $LogRoot 'watchdog-errors.log') -Value $message
      Write-Warning $message
    }
    if (-not $Once) { Start-Sleep -Seconds $PollSeconds }
  } while (-not $Once)
} finally {
  $mutex.ReleaseMutex()
  $mutex.Dispose()
}
