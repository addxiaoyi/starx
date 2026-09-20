[CmdletBinding(SupportsShouldProcess, ConfirmImpact='High')]
param(
  [Parameter(Mandatory)][string] $ConfigPath,
  [Parameter(Mandatory)][string] $BackupPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'StarX.Production.psm1') -Force
$config = Import-StarXProductionConfig $ConfigPath
$BackupPath = [IO.Path]::GetFullPath($BackupPath)
$manifestPath = Join-Path $BackupPath 'manifest.json'
$payloadRoot = Join-Path $BackupPath 'payload'
$manifest = Read-StarXJson $manifestPath
if ([int]$manifest.schemaVersion -ne 1) { throw "Unsupported backup schema: $($manifest.schemaVersion)" }
if (-not (Test-Path -LiteralPath $payloadRoot -PathType Container)) { throw "Backup payload missing: $payloadRoot" }

function Get-RuntimeRoot([string] $Name) {
  switch ($Name) {
    'velocity' { return [string]$config.paths.velocityHome }
    'paper' { return [string]$config.paths.paperHome }
    default { throw "Unknown restore root: $Name" }
  }
}

function Resolve-RestoredFile([string] $ManifestPath) {
  $normalized = $ManifestPath.Replace('\','/')
  $matches = @($manifest.dataSets | Where-Object {
    $target = ([string]$_.target).TrimEnd('/')
    $normalized -eq $target -or $normalized.StartsWith($target + '/', [StringComparison]::Ordinal)
  })
  if ($matches.Count -ne 1) { throw "Backup file does not map to exactly one data set: $ManifestPath" }
  $set = $matches[0]
  $targetRoot = ([string]$set.target).TrimEnd('/')
  $destination = Join-Path (Get-RuntimeRoot ([string]$set.root)) ([string]$set.path)
  if ($normalized -eq $targetRoot) { return $destination }
  $relative = $normalized.Substring($targetRoot.Length + 1)
  return Join-Path $destination $relative
}

$mutex = Enter-StarXProductionLock $config
try {
  $state = Read-StarXJson (Join-Path $config.paths.stateRoot 'stack.json') -Optional
  if ($state) {
    $velocity = Get-StarXProcessIdentity ([int]$state.velocityPid) ([string]$config.artifacts.velocityJar) ([string]$config.java.velocityExecutable) ([string]$state.velocityStartedAt)
    $paper = Get-StarXProcessIdentity ([int]$state.paperPid) ([string]$config.artifacts.paperJar) ([string]$config.java.paperExecutable) ([string]$state.paperStartedAt)
    if ($velocity.exists -or $paper.exists) { throw 'Restore refused because the production stack is running' }
  }

  foreach ($file in $manifest.files) {
    $sourcePath = Join-Path $payloadRoot ([string]$file.path)
    if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) { throw "Backup file missing: $($file.path)" }
    if ((Get-StarXSha256 $sourcePath) -ne [string]$file.sha256) { throw "Backup checksum mismatch: $($file.path)" }
    [void](Resolve-RestoredFile ([string]$file.path))
  }

  if ($PSCmdlet.ShouldProcess($BackupPath, 'Restore StarX production data transactionally')) {
    $changes = New-Object System.Collections.Generic.List[object]
    try {
      foreach ($set in $manifest.dataSets) {
        $destination = Join-Path (Get-RuntimeRoot ([string]$set.root)) ([string]$set.path)
        $source = Join-Path $payloadRoot ([string]$set.target)
        if (-not (Test-Path -LiteralPath $source)) { throw "Backup data set missing: $($set.target)" }
        $hadOriginal = Test-Path -LiteralPath $destination
        $old = "$destination.restore-old-$([Guid]::NewGuid().ToString('N'))"
        if ($hadOriginal) { Move-Item -LiteralPath $destination -Destination $old }
        $changes.Add([pscustomobject]@{
          destination=$destination
          old=$old
          hadOriginal=$hadOriginal
        })
        if ((Get-Item -LiteralPath $source).PSIsContainer) {
          Copy-Item -LiteralPath $source -Destination $destination -Recurse -Force
        } else {
          [IO.Directory]::CreateDirectory((Split-Path -Parent $destination)) | Out-Null
          Copy-Item -LiteralPath $source -Destination $destination -Force
        }
      }

      foreach ($file in $manifest.files) {
        $destinationFile = Resolve-RestoredFile ([string]$file.path)
        if (-not (Test-Path -LiteralPath $destinationFile -PathType Leaf)) {
          throw "Restored file missing: $($file.path)"
        }
        if ((Get-StarXSha256 $destinationFile) -ne [string]$file.sha256) {
          throw "Restored checksum mismatch: $($file.path)"
        }
      }
      foreach ($entry in $changes) {
        if ([bool]$entry.hadOriginal) { Remove-Item -LiteralPath $entry.old -Recurse -Force }
      }
    } catch {
      for ($index = $changes.Count - 1; $index -ge 0; $index--) {
        $entry = $changes[$index]
        Remove-Item -LiteralPath $entry.destination -Recurse -Force -ErrorAction SilentlyContinue
        if ([bool]$entry.hadOriginal -and (Test-Path -LiteralPath $entry.old)) {
          Move-Item -LiteralPath $entry.old -Destination $entry.destination
        }
      }
      Add-StarXProductionEvent $config 'backup-restore-failed' $_.Exception.Message @{
        backupId=[string]$manifest.backupId
      }
      throw
    }

    & (Join-Path $PSScriptRoot 'Configure-StarXProduction.ps1') -ConfigPath $ConfigPath
    $currentRelease = Read-StarXJson (Join-Path $config.paths.stateRoot 'current-release.json') -Optional
    if ($currentRelease) {
      & (Join-Path $PSScriptRoot 'Test-StarXProduction.ps1') -ConfigPath $ConfigPath -StaticOnly
    }
    Add-StarXProductionEvent $config 'backup-restored' "Backup $($manifest.backupId) restored" @{
      backupId=[string]$manifest.backupId
    }
    Write-Output ("RESTORED_BACKUP=$($manifest.backupId)")
    Write-Output 'STARX_BACKUP_RESTORED=PASS'
  }
} finally {
  Exit-StarXProductionLock $mutex
}
