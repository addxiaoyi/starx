[CmdletBinding()]
param(
  [Parameter(Mandatory)][string] $ConfigPath,
  [switch] $Once
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'StarX.Production.psm1') -Force
$config = Import-StarXProductionConfig $ConfigPath
Initialize-StarXProductionDirectories $config
$statePath = Join-Path $config.paths.stateRoot 'watchdog.json'
$alertPath = Join-Path $config.paths.stateRoot 'alert.json'

function New-WatchdogState {
  return [ordered]@{
    schemaVersion=1
    failureTimes=@()
    failedRestarts=0
    breakerUntil=$null
    lastHealthyAt=$null
    lastCheckAt=$null
    lastAction='initialized'
  }
}

function Save-State($State) { Write-StarXJsonAtomic $statePath $State }

function Open-Breaker($State,[string] $Reason) {
  $until = [DateTimeOffset]::UtcNow.AddSeconds([int]$config.watchdog.breakerCooldownSeconds)
  $State.breakerUntil = $until.ToString('o')
  $State.lastAction = 'breaker-open'
  Save-State $State
  $current = Read-StarXJson (Join-Path $config.paths.stateRoot 'current-release.json') -Optional
  $alert = [ordered]@{
    active=$true
    createdAt=[DateTimeOffset]::UtcNow.ToString('o')
    breakerUntil=$State.breakerUntil
    reason=$Reason
    currentRelease=$(if($current){[string]$current.releaseId}else{$null})
  }
  Write-StarXJsonAtomic $alertPath $alert
  Add-StarXProductionEvent $config 'watchdog-breaker-open' $Reason @{ breakerUntil=$State.breakerUntil }
}

function Invoke-WatchdogIteration {
  $state = Read-StarXJson $statePath -Optional
  if (-not $state) { $state = New-WatchdogState }
  $now = [DateTimeOffset]::UtcNow
  $state.lastCheckAt = $now.ToString('o')

  if ($state.breakerUntil) {
    $breakerUntil = [DateTimeOffset]::Parse([string]$state.breakerUntil)
    if ($breakerUntil -gt $now) {
      $state.lastAction = 'breaker-still-open'
      Save-State $state
      Write-Output ("WATCHDOG_BREAKER_UNTIL=$($state.breakerUntil)")
      return $false
    }
    $state.breakerUntil = $null
    $state.failedRestarts = 0
  }

  $healthy = $false
  try {
    $statusJson = & (Join-Path $PSScriptRoot 'Test-StarXProduction.ps1') -ConfigPath $ConfigPath -NoThrow -Json
    $status = $statusJson | ConvertFrom-Json
    $healthy = [bool]$status.healthy
  } catch {
    Add-StarXProductionEvent $config 'watchdog-health-error' $_.Exception.Message
  }

  if ($healthy) {
    $state.failureTimes = @()
    $state.failedRestarts = 0
    $state.lastHealthyAt = $now.ToString('o')
    $state.lastAction = 'healthy'
    Save-State $state
    Remove-Item -LiteralPath $alertPath -Force -ErrorAction SilentlyContinue
    Write-Output 'STARX_WATCHDOG_HEALTHY=PASS'
    return $true
  }

  $windowStart = $now.AddSeconds(-[int]$config.watchdog.restartWindowSeconds)
  $recent = @($state.failureTimes | Where-Object { [DateTimeOffset]::Parse([string]$_) -ge $windowStart })
  $recent += $now.ToString('o')
  $state.failureTimes = $recent
  if ($recent.Count -gt [int]$config.watchdog.restartLimit) {
    Open-Breaker $state "Restart limit exceeded: $($recent.Count) failures within $($config.watchdog.restartWindowSeconds)s"
    return $false
  }

  $state.lastAction = 'restart-attempt'
  Save-State $state
  Add-StarXProductionEvent $config 'watchdog-restart-attempt' 'Production health failed; attempting a controlled restart' @{ attempt=$recent.Count }
  try {
    & (Join-Path $PSScriptRoot 'Stop-StarXProduction.ps1') -ConfigPath $ConfigPath
    & (Join-Path $PSScriptRoot 'Start-StarXProduction.ps1') -ConfigPath $ConfigPath
    $state.failureTimes = @()
    $state.failedRestarts = 0
    $state.lastHealthyAt = [DateTimeOffset]::UtcNow.ToString('o')
    $state.lastAction = 'restart-succeeded'
    Save-State $state
    Remove-Item -LiteralPath $alertPath -Force -ErrorAction SilentlyContinue
    Add-StarXProductionEvent $config 'watchdog-restart-succeeded' 'Controlled restart restored production health'
    Write-Output 'STARX_WATCHDOG_RESTARTED=PASS'
    return $true
  } catch {
    $state.failedRestarts = [int]$state.failedRestarts + 1
    $state.lastAction = 'restart-failed'
    Save-State $state
    Add-StarXProductionEvent $config 'watchdog-restart-failed' $_.Exception.Message @{ failedRestarts=$state.failedRestarts }

    if ([int]$state.failedRestarts -ge [int]$config.watchdog.autoRollbackAfterFailedRestarts) {
      $previous = Read-StarXJson (Join-Path $config.paths.stateRoot 'previous-release.json') -Optional
      if ($previous -and $previous.releaseId) {
        try {
          & (Join-Path $PSScriptRoot 'Rollback-StarXProduction.ps1') -ConfigPath $ConfigPath -ReleaseId ([string]$previous.releaseId) -Confirm:$false
          $state.failureTimes = @()
          $state.failedRestarts = 0
          $state.lastAction = 'version-rollback-succeeded'
          $state.lastHealthyAt = [DateTimeOffset]::UtcNow.ToString('o')
          Save-State $state
          Remove-Item -LiteralPath $alertPath -Force -ErrorAction SilentlyContinue
          Add-StarXProductionEvent $config 'watchdog-version-rollback-succeeded' "Rolled back to $($previous.releaseId)"
          Write-Output ("STARX_WATCHDOG_ROLLED_BACK=$($previous.releaseId)")
          return $true
        } catch {
          Open-Breaker $state "Restart and automatic version rollback failed: $($_.Exception.Message)"
          return $false
        }
      }
    }
    Open-Breaker $state "Controlled restart failed: $($_.Exception.Message)"
    return $false
  }
}

do {
  $iterationOutput = @(Invoke-WatchdogIteration)
  if ($iterationOutput.Count -eq 0) { throw 'Watchdog iteration returned no result' }
  $ok = [bool]$iterationOutput[$iterationOutput.Count - 1]
  for ($index = 0; $index -lt $iterationOutput.Count - 1; $index++) {
    Write-Output $iterationOutput[$index]
  }
  if ($Once) {
    if (-not $ok) { exit 2 }
    break
  }
  Start-Sleep -Seconds ([int]$config.watchdog.pollSeconds)
} while ($true)
