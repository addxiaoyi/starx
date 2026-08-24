[CmdletBinding(SupportsShouldProcess)]
param(
  [Parameter(Mandatory)][string] $ConfigPath,
  [string] $Reason = 'manual',
  [switch] $AllowRunning
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'StarX.Production.psm1') -Force
$config = Import-StarXProductionConfig $ConfigPath
Initialize-StarXProductionDirectories $config
$mutex = Enter-StarXProductionLock $config
try {
  $statePath = Join-Path $config.paths.stateRoot 'stack.json'
  $state = Read-StarXJson $statePath -Optional
  if ($state -and -not $AllowRunning) {
    $velocity = Get-StarXProcessIdentity ([int]$state.velocityPid) ([string]$config.artifacts.velocityJar) ([string]$config.java.velocityExecutable) ([string]$state.velocityStartedAt)
    $paper = Get-StarXProcessIdentity ([int]$state.paperPid) ([string]$config.artifacts.paperJar) ([string]$config.java.paperExecutable) ([string]$state.paperStartedAt)
    if ($velocity.exists -or $paper.exists) {
      throw 'Cold backup refused because the production stack is running. Stop it first; -AllowRunning is an explicit consistency waiver.'
    }
  }

  $stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
  $safeReason = ($Reason -replace '[^A-Za-z0-9._-]','-').Trim('-')
  if (-not $safeReason) { $safeReason = 'manual' }
  $backupId = "$stamp-$safeReason"
  $backupRoot = Join-Path $config.paths.backupRoot $backupId
  if (Test-Path -LiteralPath $backupRoot) { throw "Backup already exists: $backupRoot" }
  $stagingRoot = "$backupRoot.staging-$([Guid]::NewGuid().ToString('N'))"
  $payloadRoot = Join-Path $stagingRoot 'payload'

  if ($PSCmdlet.ShouldProcess($backupRoot, 'Create atomic StarX production backup')) {
    try {
      [IO.Directory]::CreateDirectory($payloadRoot) | Out-Null
      $sets = New-Object System.Collections.Generic.List[object]
      foreach ($set in $config.backup.dataSets) {
        $runtimeRoot = switch ([string]$set.root) {
          'velocity' { $config.paths.velocityHome }
          'paper' { $config.paths.paperHome }
          default { throw "Unknown backup root: $($set.root)" }
        }
        $source = Join-Path $runtimeRoot ([string]$set.path)
        if (-not (Test-Path -LiteralPath $source)) {
          if ([bool]$set.required) { throw "Required backup data set missing: $source" }
          continue
        }
        $targetName = [string]$set.name
        $target = Join-Path $payloadRoot $targetName
        if ((Get-Item -LiteralPath $source).PSIsContainer) {
          Copy-Item -LiteralPath $source -Destination $target -Recurse -Force
        } else {
          [IO.Directory]::CreateDirectory((Split-Path -Parent $target)) | Out-Null
          Copy-Item -LiteralPath $source -Destination $target -Force
        }
        $sets.Add([ordered]@{
          name=$targetName
          root=[string]$set.root
          path=[string]$set.path
          target=$targetName
        })
      }

      $files = @(Get-ChildItem -LiteralPath $payloadRoot -File -Recurse | Sort-Object FullName | ForEach-Object {
        [ordered]@{
          path=$_.FullName.Substring($payloadRoot.Length + 1).Replace('\','/')
          sha256=Get-StarXSha256 $_.FullName
          bytes=$_.Length
        }
      })
      if ($files.Count -eq 0) { throw 'Backup payload is empty' }
      $current = Read-StarXJson (Join-Path $config.paths.stateRoot 'current-release.json') -Optional
      $totalBytes = [long](($files | ForEach-Object { [long]$_['bytes'] } | Measure-Object -Sum).Sum)
      $manifest = [ordered]@{
        schemaVersion=1
        backupId=$backupId
        createdAt=[DateTimeOffset]::UtcNow.ToString('o')
        reason=$Reason
        consistency=$(if($AllowRunning){'operator-waived-live-copy'}else{'cold'})
        releaseId=$(if($current){$current.releaseId}else{$null})
        dataSets=$sets.ToArray()
        files=$files
        totalBytes=$totalBytes
      }
      Write-StarXJsonAtomic (Join-Path $stagingRoot 'manifest.json') $manifest

      foreach ($file in $manifest.files) {
        $path = Join-Path $payloadRoot ([string]$file.path)
        if ((Get-StarXSha256 $path) -ne [string]$file.sha256) {
          throw "Backup staging checksum mismatch: $($file.path)"
        }
      }
      Move-Item -LiteralPath $stagingRoot -Destination $backupRoot

      $mirrorRoot = if ($config.backup.PSObject.Properties.Name -contains 'mirrorRoot') { [string]$config.backup.mirrorRoot } else { '' }
      $mirrorBackupRoot = $null
      if (-not [string]::IsNullOrWhiteSpace($mirrorRoot)) {
        $mirrorBackupRoot = Join-Path $mirrorRoot $backupId
        if (Test-Path -LiteralPath $mirrorBackupRoot) { throw "Mirror backup already exists: $mirrorBackupRoot" }
        $mirrorStaging = "$mirrorBackupRoot.staging-$([Guid]::NewGuid().ToString('N'))"
        try {
          [IO.Directory]::CreateDirectory($mirrorStaging) | Out-Null
          foreach ($item in Get-ChildItem -LiteralPath $backupRoot -Force) {
            $destination = Join-Path $mirrorStaging $item.Name
            if ($item.PSIsContainer) { Copy-Item -LiteralPath $item.FullName -Destination $destination -Recurse -Force }
            else { Copy-Item -LiteralPath $item.FullName -Destination $destination -Force }
          }
          $mirrorManifest = Read-StarXJson (Join-Path $mirrorStaging 'manifest.json')
          foreach ($file in $mirrorManifest.files) {
            $mirrorFile = Join-Path (Join-Path $mirrorStaging 'payload') ([string]$file.path)
            if (-not (Test-Path -LiteralPath $mirrorFile -PathType Leaf) -or (Get-StarXSha256 $mirrorFile) -ne [string]$file.sha256) {
              throw "Mirror backup checksum mismatch: $($file.path)"
            }
          }
          Move-Item -LiteralPath $mirrorStaging -Destination $mirrorBackupRoot
          Remove-StarXOldDirectories $mirrorRoot ([int]$config.backup.mirrorRetentionCount)
        } catch {
          Remove-Item -LiteralPath $mirrorStaging -Recurse -Force -ErrorAction SilentlyContinue
          Add-StarXProductionEvent $config 'backup-mirror-failed' $_.Exception.Message @{ backupId=$backupId; localBackup=$backupRoot; mirrorRoot=$mirrorRoot }
          throw "Local backup completed but verified mirror failed: $($_.Exception.Message). Local backup: $backupRoot"
        }
      }

      Remove-StarXOldDirectories $config.paths.backupRoot ([int]$config.backup.retentionCount)
      Add-StarXProductionEvent $config 'backup-created' "Backup $backupId created" @{
        backupId=$backupId
        consistency=$manifest.consistency
        mirror=$mirrorBackupRoot
      }
      Write-Output ("BACKUP_ROOT=$backupRoot")
      if ($mirrorBackupRoot) { Write-Output ("BACKUP_MIRROR_ROOT=$mirrorBackupRoot") }
      Write-Output ("BACKUP_FILES=$($files.Count)")
      Write-Output ("BACKUP_BYTES=$totalBytes")
      Write-Output 'STARX_BACKUP_CREATED=PASS'
    } catch {
      Remove-Item -LiteralPath $stagingRoot -Recurse -Force -ErrorAction SilentlyContinue
      Add-StarXProductionEvent $config 'backup-failed' $_.Exception.Message @{ backupId=$backupId }
      throw
    }
  }
} finally {
  Exit-StarXProductionLock $mutex
}
