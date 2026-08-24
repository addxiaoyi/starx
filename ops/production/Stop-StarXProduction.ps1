[CmdletBinding(SupportsShouldProcess, ConfirmImpact='Medium')]
param(
  [Parameter(Mandatory)][string] $ConfigPath,
  [switch] $Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'StarX.Production.psm1') -Force
$config = Import-StarXProductionConfig $ConfigPath
$mutex = Enter-StarXProductionLock $config
try {
  $stackPath = Join-Path $config.paths.stateRoot 'stack.json'
  $stack = Read-StarXJson $stackPath -Optional
  if (-not $stack) { Write-Output 'STARX_PRODUCTION_ALREADY_STOPPED=PASS'; return }

  $velocity = Get-StarXProcessIdentity ([int]$stack.velocityPid) ([string]$config.artifacts.velocityJar) ([string]$config.java.velocityExecutable) ([string]$stack.velocityStartedAt)
  $paper = Get-StarXProcessIdentity ([int]$stack.paperPid) ([string]$config.artifacts.paperJar) ([string]$config.java.paperExecutable) ([string]$stack.paperStartedAt)
  $velocityOwns = (Test-StarXTcpListener ([int]$config.network.javaPort) ([int]$stack.velocityPid)) -or (Test-StarXTcpListener ([int]$config.network.httpPort) ([int]$stack.velocityPid))
  $paperOwns = (Test-StarXTcpListener ([int]$config.network.backendPort) ([int]$stack.paperPid)) -or (Test-StarXTcpListener ([int]$config.network.rconPort) ([int]$stack.paperPid))
  if ($velocity.exists -and (-not $velocity.trusted -or -not $velocityOwns) -and -not $Force) { throw "Refusing to stop untrusted Velocity PID $($stack.velocityPid): $($velocity.reason)" }
  if ($paper.exists -and (-not $paper.trusted -or -not $paperOwns) -and -not $Force) { throw "Refusing to stop untrusted Paper PID $($stack.paperPid): $($paper.reason)" }

  if ($paper.exists -and $paper.trusted -and (Test-StarXTcpListener ([int]$config.network.rconPort) ([int]$stack.paperPid))) {
    if ($PSCmdlet.ShouldProcess("Paper PID $($stack.paperPid)",'Send RCON stop')) {
      $password = Read-StarXSecret ([string]$config.security.rconPasswordFile) 'RCON'
      try { [void](Invoke-StarXRcon ([string]$config.network.rconAddress) ([int]$config.network.rconPort) $password 'stop') } catch { Add-StarXProductionEvent $config 'rcon-stop-failed' $_.Exception.Message }
    }
  }

  $deadline = [DateTime]::UtcNow.AddSeconds([int]$config.startup.gracefulStopSeconds)
  while ([DateTime]::UtcNow -lt $deadline) {
    $paperAlive = (Get-StarXProcessIdentity ([int]$stack.paperPid) ([string]$config.artifacts.paperJar) ([string]$config.java.paperExecutable) ([string]$stack.paperStartedAt)).exists
    if (-not $paperAlive) { break }
    Start-Sleep -Milliseconds 500
  }

  foreach ($target in @(
    [pscustomobject]@{ role='PAPER'; pid=[int]$stack.paperPid; jar=[string]$config.artifacts.paperJar; java=[string]$config.java.paperExecutable; started=[string]$stack.paperStartedAt },
    [pscustomobject]@{ role='VELOCITY'; pid=[int]$stack.velocityPid; jar=[string]$config.artifacts.velocityJar; java=[string]$config.java.velocityExecutable; started=[string]$stack.velocityStartedAt }
  )) {
    $identity = Get-StarXProcessIdentity $target.pid $target.jar $target.java $target.started
    if (-not $identity.exists) { Write-Output ("$($target.role)_STOP=GRACEFUL_OR_ALREADY_STOPPED"); continue }
    if (-not $identity.trusted -and -not $Force) { throw "Process identity changed during shutdown for $($target.role) PID $($target.pid)" }
    if ($PSCmdlet.ShouldProcess("$($target.role) PID $($target.pid)",'Force stop remaining process')) { Stop-Process -Id $target.pid -Force -ErrorAction Stop; Write-Output ("$($target.role)_STOP=FORCED") }
  }

  Start-Sleep -Milliseconds 750
  foreach ($target in @(
    @{ pid=[int]$stack.paperPid; jar=[string]$config.artifacts.paperJar; java=[string]$config.java.paperExecutable; started=[string]$stack.paperStartedAt },
    @{ pid=[int]$stack.velocityPid; jar=[string]$config.artifacts.velocityJar; java=[string]$config.java.velocityExecutable; started=[string]$stack.velocityStartedAt }
  )) {
    $identity = Get-StarXProcessIdentity $target.pid $target.jar $target.java $target.started
    if ($identity.exists -and $identity.trusted) { throw "Production process is still running: PID $($target.pid)" }
  }
  Remove-Item -LiteralPath $stackPath -Force
  Add-StarXProductionEvent $config 'stack-stopped' 'Production stack stopped'
  Write-Output 'STARX_PRODUCTION_STOPPED=PASS'
} finally {
  Exit-StarXProductionLock $mutex
}
