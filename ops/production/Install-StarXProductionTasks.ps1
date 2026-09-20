[CmdletBinding(SupportsShouldProcess)]
param(
  [Parameter(Mandatory)][string] $ConfigPath,
  [string] $WatchdogTaskName = 'StarX Production Watchdog',
  [string] $BackupTaskName = 'StarX Production Daily Backup',
  [ValidatePattern('^([01]\d|2[0-3]):[0-5]\d$')][string] $BackupTime = '04:00'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ConfigPath = [IO.Path]::GetFullPath($ConfigPath)
$watchScript = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'Watch-StarXProduction.ps1'))
$backupScript = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'Invoke-StarXScheduledBackup.ps1'))
foreach ($path in @($ConfigPath,$watchScript,$backupScript)) { if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Scheduled task input missing: $path" } }
$watchCommand = "powershell.exe -NoProfile -ExecutionPolicy Bypass -File `"$watchScript`" -ConfigPath `"$ConfigPath`" -Once"
$backupCommand = "powershell.exe -NoProfile -ExecutionPolicy Bypass -File `"$backupScript`" -ConfigPath `"$ConfigPath`""

if ($PSCmdlet.ShouldProcess($WatchdogTaskName,'Install SYSTEM watchdog task every minute')) {
  & schtasks.exe /Create /TN $WatchdogTaskName /TR $watchCommand /SC MINUTE /MO 1 /RU SYSTEM /RL HIGHEST /F | Out-Null
  if ($LASTEXITCODE -ne 0) { throw "Failed to install watchdog scheduled task: exit=$LASTEXITCODE" }
}
if ($PSCmdlet.ShouldProcess($BackupTaskName,"Install SYSTEM daily backup task at $BackupTime")) {
  & schtasks.exe /Create /TN $BackupTaskName /TR $backupCommand /SC DAILY /ST $BackupTime /RU SYSTEM /RL HIGHEST /F | Out-Null
  if ($LASTEXITCODE -ne 0) { throw "Failed to install backup scheduled task: exit=$LASTEXITCODE" }
}
Write-Output ("WATCHDOG_TASK=$WatchdogTaskName")
Write-Output ("BACKUP_TASK=$BackupTaskName")
Write-Output 'STARX_PRODUCTION_TASKS_INSTALLED=PASS'
