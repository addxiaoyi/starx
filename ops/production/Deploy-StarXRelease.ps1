[CmdletBinding(SupportsShouldProcess, ConfirmImpact='Medium')]
param(
  [Parameter(Mandatory)][string] $ConfigPath,
  [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9._-]{1,80}$')][string] $ReleaseId,
  [switch] $SkipBackup,
  [switch] $NoStart,
  [switch] $ForceReapply
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'StarX.Production.psm1') -Force
$config = Import-StarXProductionConfig $ConfigPath
Initialize-StarXProductionDirectories $config

function Get-Release([string] $Id) {
  $root = Join-Path $config.paths.releaseRoot $Id
  $manifestPath = Join-Path $root 'manifest.json'
  $manifest = Read-StarXJson $manifestPath
  if ([int]$manifest.schemaVersion -ne 1 -or [string]$manifest.releaseId -ne $Id) { throw "Invalid release manifest: $manifestPath" }
  foreach ($file in $manifest.files) {
    $source = Join-Path $root ([string]$file.path)
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw "Release file missing: $($file.path)" }
    if ((Get-StarXSha256 $source) -ne [string]$file.sha256) { throw "Release checksum mismatch: $($file.path)" }
  }
  return [pscustomobject]@{ id=$Id; root=$root; manifest=$manifest; manifestSha256=Get-StarXSha256 $manifestPath }
}

function Get-RuntimePath([string] $Relative) {
  $normalized = $Relative.Replace('\','/')
  if ($normalized.StartsWith('velocity/')) { return Join-Path $config.paths.velocityHome $normalized.Substring('velocity/'.Length) }
  if ($normalized.StartsWith('paper/')) { return Join-Path $config.paths.paperHome $normalized.Substring('paper/'.Length) }
  throw "Unsupported release path: $Relative"
}

function Apply-Release($Release,$OldRelease) {
  $newPaths = @{}
  foreach ($file in $Release.manifest.files) { $newPaths[[string]$file.path] = $true }
  if ($OldRelease) {
    foreach ($oldFile in $OldRelease.manifest.files) {
      if (-not $newPaths.ContainsKey([string]$oldFile.path)) {
        $stale = Get-RuntimePath ([string]$oldFile.path)
        Remove-Item -LiteralPath $stale -Recurse -Force -ErrorAction SilentlyContinue
      }
    }
  }
  foreach ($file in $Release.manifest.files) {
    $source = Join-Path $Release.root ([string]$file.path)
    $destination = Get-RuntimePath ([string]$file.path)
    Copy-StarXFileAtomic $source $destination
  }
}

function Write-ReleaseState($Release,$Previous) {
  if ($Previous) {
    Write-StarXJsonAtomic (Join-Path $config.paths.stateRoot 'previous-release.json') ([ordered]@{
      releaseId=[string]$Previous.id
      manifestSha256=[string]$Previous.manifestSha256
      recordedAt=[DateTimeOffset]::UtcNow.ToString('o')
    })
  } else {
    Remove-Item -LiteralPath (Join-Path $config.paths.stateRoot 'previous-release.json') -Force -ErrorAction SilentlyContinue
  }
  Write-StarXJsonAtomic (Join-Path $config.paths.stateRoot 'current-release.json') ([ordered]@{
    releaseId=[string]$Release.id
    manifestSha256=[string]$Release.manifestSha256
    appliedAt=[DateTimeOffset]::UtcNow.ToString('o')
  })
}

function Restore-ReleaseStateSnapshot($CurrentState,$PreviousState) {
  $currentPath = Join-Path $config.paths.stateRoot 'current-release.json'
  $previousPath = Join-Path $config.paths.stateRoot 'previous-release.json'
  if ($CurrentState) { Write-StarXJsonAtomic $currentPath $CurrentState } else { Remove-Item -LiteralPath $currentPath -Force -ErrorAction SilentlyContinue }
  if ($PreviousState) { Write-StarXJsonAtomic $previousPath $PreviousState } else { Remove-Item -LiteralPath $previousPath -Force -ErrorAction SilentlyContinue }
}

$mutex = Enter-StarXProductionLock $config
try {
  $target = Get-Release $ReleaseId
  $currentState = Read-StarXJson (Join-Path $config.paths.stateRoot 'current-release.json') -Optional
  $previousState = Read-StarXJson (Join-Path $config.paths.stateRoot 'previous-release.json') -Optional
  if ($currentState -and [string]$currentState.releaseId -eq $ReleaseId -and -not $ForceReapply) { throw "Release is already current: $ReleaseId" }
  $current = if ($currentState) { Get-Release ([string]$currentState.releaseId) } else { $null }
  $stackState = Read-StarXJson (Join-Path $config.paths.stateRoot 'stack.json') -Optional
  $wasRunning = $null -ne $stackState
  $backupPath = $null

  if ($PSCmdlet.ShouldProcess($ReleaseId,'Deploy StarX production release')) {
    try {
      if ($stackState) { & (Join-Path $PSScriptRoot 'Stop-StarXProduction.ps1') -ConfigPath $ConfigPath }
      if ($current -and -not $SkipBackup) {
        $backupOutput = @(& (Join-Path $PSScriptRoot 'Backup-StarXProduction.ps1') -ConfigPath $ConfigPath -Reason "before-$ReleaseId")
        $backupLine = $backupOutput | Where-Object { $_ -like 'BACKUP_ROOT=*' } | Select-Object -Last 1
        if ($backupLine) { $backupPath = $backupLine.Substring('BACKUP_ROOT='.Length) }
      }

      Apply-Release $target $current
      Write-ReleaseState $target $current
      & (Join-Path $PSScriptRoot 'Configure-StarXProduction.ps1') -ConfigPath $ConfigPath
      & (Join-Path $PSScriptRoot 'Test-StarXProduction.ps1') -ConfigPath $ConfigPath -StaticOnly
      if (-not $NoStart) { & (Join-Path $PSScriptRoot 'Start-StarXProduction.ps1') -ConfigPath $ConfigPath }

      Add-StarXProductionEvent $config 'release-deployed' "Release $ReleaseId deployed" @{ releaseId=$ReleaseId; previous=$(if($current){$current.id}else{$null}); backup=$backupPath; started=(-not $NoStart) }
      Write-Output ("DEPLOYED_RELEASE=$ReleaseId")
      if ($backupPath) { Write-Output ("PRE_DEPLOY_BACKUP=$backupPath") }
      Write-Output 'STARX_RELEASE_DEPLOYED=PASS'
    } catch {
      $deployError = $_
      Add-StarXProductionEvent $config 'release-deploy-failed' $deployError.Exception.Message @{ releaseId=$ReleaseId; backup=$backupPath }
      try { & (Join-Path $PSScriptRoot 'Stop-StarXProduction.ps1') -ConfigPath $ConfigPath -Force -ErrorAction SilentlyContinue | Out-Null } catch {}
      try {
        if ($current) {
          Apply-Release $current $target
          Restore-ReleaseStateSnapshot $currentState $previousState
          & (Join-Path $PSScriptRoot 'Configure-StarXProduction.ps1') -ConfigPath $ConfigPath
          & (Join-Path $PSScriptRoot 'Test-StarXProduction.ps1') -ConfigPath $ConfigPath -StaticOnly
          if ($wasRunning -and -not $NoStart) { & (Join-Path $PSScriptRoot 'Start-StarXProduction.ps1') -ConfigPath $ConfigPath }
          Add-StarXProductionEvent $config 'release-auto-rollback' "Restored release $($current.id) after failed deployment" @{ failedRelease=$ReleaseId; restoredRelease=$current.id; dataBackup=$backupPath }
        } else {
          foreach ($file in $target.manifest.files) { Remove-Item -LiteralPath (Get-RuntimePath ([string]$file.path)) -Recurse -Force -ErrorAction SilentlyContinue }
          Restore-ReleaseStateSnapshot $currentState $previousState
        }
      } catch {
        Add-StarXProductionEvent $config 'release-auto-rollback-failed' $_.Exception.Message @{ failedRelease=$ReleaseId; previous=$(if($current){$current.id}else{$null}); dataBackup=$backupPath }
        throw "Deployment failed: $($deployError.Exception.Message). Automatic version rollback also failed: $($_.Exception.Message). Data backup: $backupPath"
      }
      throw "Deployment failed and previous version was restored: $($deployError.Exception.Message). Player data was not automatically rolled back. Backup: $backupPath"
    }
  }
} finally {
  Exit-StarXProductionLock $mutex
}
