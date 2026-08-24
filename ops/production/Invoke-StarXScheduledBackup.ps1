[CmdletBinding()]
param([Parameter(Mandatory)][string] $ConfigPath)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'StarX.Production.psm1') -Force
$config = Import-StarXProductionConfig $ConfigPath
$stack = Read-StarXJson (Join-Path $config.paths.stateRoot 'stack.json') -Optional
$wasRunning = $null -ne $stack
$backupError = $null
try {
  if ($wasRunning) { & (Join-Path $PSScriptRoot 'Stop-StarXProduction.ps1') -ConfigPath $ConfigPath }
  & (Join-Path $PSScriptRoot 'Backup-StarXProduction.ps1') -ConfigPath $ConfigPath -Reason 'scheduled'
} catch {
  $backupError = $_
  Add-StarXProductionEvent $config 'scheduled-backup-failed' $_.Exception.Message
} finally {
  if ($wasRunning) {
    try { & (Join-Path $PSScriptRoot 'Start-StarXProduction.ps1') -ConfigPath $ConfigPath } catch {
      Add-StarXProductionEvent $config 'scheduled-backup-restart-failed' $_.Exception.Message
      if ($backupError) { throw "Scheduled backup failed: $($backupError.Exception.Message). Restart also failed: $($_.Exception.Message)" }
      throw
    }
  }
}
if ($backupError) { throw $backupError }
Write-Output 'STARX_SCHEDULED_BACKUP=PASS'
