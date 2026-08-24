[CmdletBinding(SupportsShouldProcess)]
param(
  [string] $ConfigPath = (Join-Path $PSScriptRoot 'production.config.json'),
  [string] $ProductionRoot = 'D:\StarMC',
  [switch] $Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'StarX.Production.psm1') -Force

function New-RandomSecret([int] $Bytes = 36) {
  $buffer = New-Object byte[] $Bytes
  $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
  try { $rng.GetBytes($buffer) } finally { $rng.Dispose() }
  return [Convert]::ToBase64String($buffer).TrimEnd('=').Replace('/','_').Replace('+','-')
}

function Set-SecretAcl([string] $Path) {
  Set-StarXRestrictedFileAcl $Path
}

$ConfigPath = [IO.Path]::GetFullPath($ConfigPath)
$ProductionRoot = [IO.Path]::GetFullPath($ProductionRoot)
$templatePath = Join-Path $PSScriptRoot 'production.config.example.json'
if (-not (Test-Path -LiteralPath $templatePath -PathType Leaf)) { throw "Config template missing: $templatePath" }
if ((Test-Path -LiteralPath $ConfigPath) -and -not $Force) { throw "Config already exists: $ConfigPath. Use -Force only after reviewing the existing installation." }

if ($PSCmdlet.ShouldProcess($ProductionRoot, 'Initialize StarX production directories, config and secrets')) {
  [IO.Directory]::CreateDirectory((Split-Path -Parent $ConfigPath)) | Out-Null
  $config = Get-Content -LiteralPath $templatePath -Raw -Encoding UTF8 | ConvertFrom-Json
  $config.paths.velocityHome = Join-Path $ProductionRoot 'runtime\velocity'
  $config.paths.paperHome = Join-Path $ProductionRoot 'runtime\paper'
  $config.paths.releaseRoot = Join-Path $ProductionRoot 'releases'
  $config.paths.backupRoot = Join-Path $ProductionRoot 'backups'
  $config.paths.stateRoot = Join-Path $ProductionRoot 'state'
  $config.paths.logRoot = Join-Path $ProductionRoot 'logs'
  $config.java.velocityFlagsFile = Join-Path $PSScriptRoot 'jvm\velocity-production.flags'
  $config.java.paperFlagsFile = Join-Path $PSScriptRoot 'jvm\paper-production.flags'
  $secretRoot = Join-Path (Split-Path -Parent $ConfigPath) 'secrets'
  $config.security.rconPasswordFile = Join-Path $secretRoot 'rcon-password.txt'
  $config.security.apiKeyFile = Join-Path $secretRoot 'api-key.txt'
  $config.security.forwardingSecretFile = Join-Path $secretRoot 'forwarding-secret.txt'
  Write-StarXJsonAtomic $ConfigPath $config

  $resolved = Import-StarXProductionConfig $ConfigPath
  Initialize-StarXProductionDirectories $resolved
  [IO.Directory]::CreateDirectory($secretRoot) | Out-Null
  foreach ($name in @('rcon-password.txt','api-key.txt','forwarding-secret.txt')) {
    $path = Join-Path $secretRoot $name
    if (-not (Test-Path -LiteralPath $path) -or $Force) {
      [IO.File]::WriteAllText($path, (New-RandomSecret) + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
      Set-SecretAcl $path
    }
  }
  Add-StarXProductionEvent $resolved 'initialize' 'Production control plane initialized' @{ root=$ProductionRoot; config=$ConfigPath }
  Write-Output ("PRODUCTION_CONFIG=$ConfigPath")
  Write-Output ("PRODUCTION_ROOT=$ProductionRoot")
  Write-Output 'STARX_PRODUCTION_INITIALIZED=PASS'
}
