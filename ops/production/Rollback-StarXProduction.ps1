[CmdletBinding(SupportsShouldProcess, ConfirmImpact='High')]
param(
  [Parameter(Mandatory)][string] $ConfigPath,
  [string] $ReleaseId,
  [switch] $SkipBackup,
  [switch] $NoStart
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'StarX.Production.psm1') -Force
$config = Import-StarXProductionConfig $ConfigPath
if (-not $ReleaseId) {
  $previous = Read-StarXJson (Join-Path $config.paths.stateRoot 'previous-release.json')
  $ReleaseId = [string]$previous.releaseId
}
if (-not $ReleaseId) { throw 'No rollback release was specified or recorded' }
if ($PSCmdlet.ShouldProcess($ReleaseId,'Roll back StarX production version')) {
  & (Join-Path $PSScriptRoot 'Deploy-StarXRelease.ps1') -ConfigPath $ConfigPath -ReleaseId $ReleaseId -SkipBackup:$SkipBackup -NoStart:$NoStart
  Write-Output ("ROLLED_BACK_RELEASE=$ReleaseId")
  Write-Output 'STARX_PRODUCTION_ROLLBACK=PASS'
}
