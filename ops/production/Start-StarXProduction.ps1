[CmdletBinding(SupportsShouldProcess)]
param(
  [Parameter(Mandatory)][string] $ConfigPath,
  [switch] $ValidateOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'StarX.Production.psm1') -Force
$config = Import-StarXProductionConfig $ConfigPath
Initialize-StarXProductionDirectories $config
$mutex = Enter-StarXProductionLock $config
$velocityProcess = $null
$paperProcess = $null
try {
  $staticJson = & (Join-Path $PSScriptRoot 'Test-StarXProduction.ps1') -ConfigPath $ConfigPath -StaticOnly -NoThrow -Json
  $static = $staticJson | ConvertFrom-Json
  if (-not [bool]$static.healthy) { throw 'Static production verification failed; inspect state/status.json' }
  if ($ValidateOnly) { Write-Output 'STARX_PRODUCTION_START_VALIDATION=PASS'; return }

  $existing = Read-StarXJson (Join-Path $config.paths.stateRoot 'stack.json') -Optional
  if ($existing) {
    $existingVelocity = Get-StarXProcessIdentity ([int]$existing.velocityPid) ([string]$config.artifacts.velocityJar) ([string]$config.java.velocityExecutable) ([string]$existing.velocityStartedAt)
    $existingPaper = Get-StarXProcessIdentity ([int]$existing.paperPid) ([string]$config.artifacts.paperJar) ([string]$config.java.paperExecutable) ([string]$existing.paperStartedAt)
    if ($existingVelocity.exists -or $existingPaper.exists) { throw 'Production stack already appears to be running' }
  }
  foreach ($port in @($config.network.javaPort,$config.network.backendPort,$config.network.httpPort,$config.network.rconPort)) {
    if (Test-StarXTcpListener ([int]$port)) { throw "Required TCP port is already occupied: $port" }
  }
  if (([bool]$config.network.bedrockEnabled) -and (Test-StarXUdpListener ([int]$config.network.bedrockPort))) { throw "Required UDP port is already occupied: $($config.network.bedrockPort)" }

  $variables = @{ LOG_ROOT = ([string]$config.paths.logRoot).Replace('\','/') }
  $velocityArgs = @(Get-StarXFlags ([string]$config.java.velocityFlagsFile) $variables) + @('-jar',[string]$config.artifacts.velocityJar)
  $paperArgs = @(Get-StarXFlags ([string]$config.java.paperFlagsFile) $variables) + @('-jar',[string]$config.artifacts.paperJar,'--nogui')
  $stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
  $stdin = Join-Path $config.paths.stateRoot 'stdin.closed'
  [IO.File]::WriteAllText($stdin,'',[Text.UTF8Encoding]::new($false))
  $velocityOut = Join-Path $config.paths.logRoot "velocity-$stamp.out.log"
  $velocityErr = Join-Path $config.paths.logRoot "velocity-$stamp.err.log"
  $paperOut = Join-Path $config.paths.logRoot "paper-$stamp.out.log"
  $paperErr = Join-Path $config.paths.logRoot "paper-$stamp.err.log"

  if ($PSCmdlet.ShouldProcess($config.paths.velocityHome,'Start Velocity production process')) {
    $velocityProcess = Start-Process -FilePath ([string]$config.java.velocityExecutable) -ArgumentList $velocityArgs -WorkingDirectory ([string]$config.paths.velocityHome) -RedirectStandardInput $stdin -RedirectStandardOutput $velocityOut -RedirectStandardError $velocityErr -WindowStyle Hidden -PassThru
    Wait-StarXCondition {
      $velocityProcess.Refresh()
      if ($velocityProcess.HasExited) { throw "Velocity exited with code $($velocityProcess.ExitCode)" }
      $ready = (Test-StarXTcpListener ([int]$config.network.javaPort) $velocityProcess.Id ([string]$config.network.bindAddress)) -and
        (Test-StarXTcpListener ([int]$config.network.httpPort) $velocityProcess.Id ([string]$config.network.httpAddress)) -and
        (Test-StarXHttpHealth ([string]$config.network.healthUrl) 2)
      if ([bool]$config.network.bedrockEnabled) { $ready = $ready -and (Test-StarXUdpListener ([int]$config.network.bedrockPort) $velocityProcess.Id ([string]$config.network.bindAddress)) }
      $ready
    } ([int]$config.startup.velocityTimeoutSeconds) 'Velocity did not become healthy'
  }

  if ($PSCmdlet.ShouldProcess($config.paths.paperHome,'Start Paper production process')) {
    $paperProcess = Start-Process -FilePath ([string]$config.java.paperExecutable) -ArgumentList $paperArgs -WorkingDirectory ([string]$config.paths.paperHome) -RedirectStandardInput $stdin -RedirectStandardOutput $paperOut -RedirectStandardError $paperErr -WindowStyle Hidden -PassThru
    Wait-StarXCondition {
      $paperProcess.Refresh()
      if ($paperProcess.HasExited) { throw "Paper exited with code $($paperProcess.ExitCode)" }
      (Test-StarXTcpListener ([int]$config.network.backendPort) $paperProcess.Id ([string]$config.network.backendAddress)) -and
        (Test-StarXTcpListener ([int]$config.network.rconPort) $paperProcess.Id ([string]$config.network.rconAddress))
    } ([int]$config.startup.paperTimeoutSeconds) 'Paper did not become healthy'
  }

  $current = Read-StarXJson (Join-Path $config.paths.stateRoot 'current-release.json')
  $state = [ordered]@{
    schemaVersion=1
    releaseId=[string]$current.releaseId
    startedAt=[DateTimeOffset]::UtcNow.ToString('o')
    velocityPid=$velocityProcess.Id
    velocityStartedAt=$velocityProcess.StartTime.ToUniversalTime().ToString('o')
    paperPid=$paperProcess.Id
    paperStartedAt=$paperProcess.StartTime.ToUniversalTime().ToString('o')
    velocityJava=[string]$config.java.velocityExecutable
    paperJava=[string]$config.java.paperExecutable
    logs=[ordered]@{ velocityOut=$velocityOut; velocityErr=$velocityErr; paperOut=$paperOut; paperErr=$paperErr }
  }
  Write-StarXJsonAtomic (Join-Path $config.paths.stateRoot 'stack.json') $state
  & (Join-Path $PSScriptRoot 'Test-StarXProduction.ps1') -ConfigPath $ConfigPath
  Add-StarXProductionEvent $config 'stack-started' "Production stack started on release $($state.releaseId)" @{ releaseId=$state.releaseId; velocityPid=$state.velocityPid; paperPid=$state.paperPid }
  Write-Output ("VELOCITY_PID=$($state.velocityPid)")
  Write-Output ("PAPER_PID=$($state.paperPid)")
  Write-Output 'STARX_PRODUCTION_STARTED=PASS'
} catch {
  foreach ($process in @($paperProcess,$velocityProcess)) {
    if ($null -ne $process) {
      try { $process.Refresh(); if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue } } catch {}
    }
  }
  Remove-Item -LiteralPath (Join-Path $config.paths.stateRoot 'stack.json') -Force -ErrorAction SilentlyContinue
  Add-StarXProductionEvent $config 'stack-start-failed' $_.Exception.Message
  throw
} finally {
  Exit-StarXProductionLock $mutex
}
