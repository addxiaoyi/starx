[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProductionScripts = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$RepoRoot = [IO.Path]::GetFullPath((Join-Path $ProductionScripts '..\..'))
$FixtureRoot = Join-Path $RepoRoot ("tmp\production-control-plane-" + [Guid]::NewGuid().ToString('N'))
$ConfigPath = Join-Path $FixtureRoot 'control\production.config.json'
$ProductionRoot = Join-Path $FixtureRoot 'instance'
$Utf8 = [Text.UTF8Encoding]::new($false)

function Invoke-Script([string] $Name,[hashtable] $Parameters = @{}) {
  $path = Join-Path $ProductionScripts $Name
  if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Test target missing: $path" }
  return @(& $path @Parameters)
}

function Assert-True([bool] $Condition,[string] $Message) {
  if (-not $Condition) { throw $Message }
}

function Assert-Throws([scriptblock] $Action,[string] $MessagePattern) {
  $threw = $false
  try { & $Action } catch {
    $threw = $true
    if ($MessagePattern -and $_.Exception.Message -notmatch $MessagePattern) {
      throw "Unexpected failure message. Expected /$MessagePattern/, actual: $($_.Exception.Message)"
    }
  }
  if (-not $threw) { throw "Expected failure was not raised: $MessagePattern" }
}

function Write-Text([string] $Path,[string] $Value) {
  [IO.Directory]::CreateDirectory((Split-Path -Parent $Path)) | Out-Null
  [IO.File]::WriteAllText($Path,$Value,$Utf8)
}

try {
  [IO.Directory]::CreateDirectory($FixtureRoot) | Out-Null
  $hashFixture = Join-Path $FixtureRoot 'sha256-fixture.txt'
  [IO.File]::WriteAllText($hashFixture, '', $Utf8)
  Import-Module (Join-Path $ProductionScripts 'StarX.Production.psm1') -Force
  Assert-True ((Get-StarXSha256 $hashFixture) -eq 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855') 'SHA-256 helper did not hash an empty file correctly'

  $init = Invoke-Script 'Initialize-StarXProduction.ps1' @{
    ConfigPath=$ConfigPath
    ProductionRoot=$ProductionRoot
    Force=$true
    Confirm=$false
  }
  Assert-True (($init -join "`n") -match 'STARX_PRODUCTION_INITIALIZED=PASS') 'Production initializer did not pass'

  $config = Get-Content -LiteralPath $ConfigPath -Raw -Encoding UTF8 | ConvertFrom-Json
  $config.paths.velocityHome = Join-Path $ProductionRoot 'runtime\velocity'
  $config.paths.paperHome = Join-Path $ProductionRoot 'runtime\paper'
  $config.paths.releaseRoot = Join-Path $ProductionRoot 'releases'
  $config.paths.backupRoot = Join-Path $ProductionRoot 'backups'
  $config.backup.mirrorRoot = Join-Path $ProductionRoot 'backup-mirror'
  $config.backup.mirrorRetentionCount = 5
  $config.paths.stateRoot = Join-Path $ProductionRoot 'state'
  $config.paths.logRoot = Join-Path $ProductionRoot 'logs'
  $config.network.bindAddress = '127.0.0.1'
  $config.network.backendAddress = '127.0.0.1'
  $config.network.httpAddress = '127.0.0.1'
  $config.network.rconAddress = '127.0.0.1'
  $config.network.javaPort = 64011
  $config.network.backendPort = 64012
  $config.network.bedrockEnabled = $false
  $config.network.bedrockPort = 64013
  $config.network.httpPort = 64014
  $config.network.rconPort = 64015
  $config.network.healthUrl = 'http://127.0.0.1:64014/v1/health'
  $config.security.velocityOnlineMode = $true
  $config.security.allowInsecurePublicOffline = $false
  $config.backup.retentionCount = 5
  $config.backup.releaseRetentionCount = 5
  [IO.File]::WriteAllText($ConfigPath,($config | ConvertTo-Json -Depth 12) + [Environment]::NewLine,$Utf8)

  Write-Text (Join-Path $config.paths.paperHome 'eula.txt') "eula=true`n"
  [IO.Directory]::CreateDirectory((Join-Path $config.paths.paperHome 'world')) | Out-Null
  Write-Text (Join-Path $config.paths.paperHome 'world\player.dat') 'player-state-v1'

  $sources = Join-Path $FixtureRoot 'sources'
  [IO.Directory]::CreateDirectory($sources) | Out-Null
  foreach ($release in @('r1','r2','bad')) {
    Write-Text (Join-Path $sources "$release-velocity.jar") "$release-velocity-core"
    Write-Text (Join-Path $sources "$release-paper.jar") "$release-paper-core"
    Write-Text (Join-Path $sources "$release-starx-universal.jar") "$release-starx-universal"
  }
  $badPaperGlobalTemplate = Join-Path $sources 'bad-paper-global.yml'
  Write-Text $badPaperGlobalTemplate "version: 31`nproxies: {}`n"
  $paperGlobalTemplate = Join-Path $PSScriptRoot 'fixtures\paper-global.yml'
  Assert-True (Test-Path -LiteralPath $paperGlobalTemplate -PathType Leaf) 'Version-matched Paper config fixture is missing'

  foreach ($release in @('r1','r2')) {
    $releaseOutput = Invoke-Script 'New-StarXRelease.ps1' @{
      ConfigPath=$ConfigPath
      ReleaseId=$release
      VelocityJar=(Join-Path $sources "$release-velocity.jar")
      PaperJar=(Join-Path $sources "$release-paper.jar")
      UniversalPlugin=(Join-Path $sources "$release-starx-universal.jar")
      PaperGlobalTemplate=$paperGlobalTemplate
      Confirm=$false
    }
    Assert-True (($releaseOutput -join "`n") -match 'STARX_RELEASE_CREATED=PASS') "Release creation failed: $release"
  }
  $badReleaseOutput = Invoke-Script 'New-StarXRelease.ps1' @{
    ConfigPath=$ConfigPath
    ReleaseId='bad'
    VelocityJar=(Join-Path $sources 'bad-velocity.jar')
    PaperJar=(Join-Path $sources 'bad-paper.jar')
    UniversalPlugin=(Join-Path $sources 'bad-starx-universal.jar')
    PaperGlobalTemplate=$badPaperGlobalTemplate
    Confirm=$false
  }
  Assert-True (($badReleaseOutput -join "`n") -match 'STARX_RELEASE_CREATED=PASS') 'Bad fixture release creation failed'

  $deployR1 = Invoke-Script 'Deploy-StarXRelease.ps1' @{
    ConfigPath=$ConfigPath
    ReleaseId='r1'
    SkipBackup=$true
    NoStart=$true
    Confirm=$false
  }
  Assert-True (($deployR1 -join "`n") -match 'STARX_RELEASE_DEPLOYED=PASS') 'Initial release deployment failed'
  $current = Get-Content -LiteralPath (Join-Path $config.paths.stateRoot 'current-release.json') -Raw -Encoding UTF8 | ConvertFrom-Json
  Assert-True ($current.releaseId -eq 'r1') 'r1 was not recorded as current release'
  Assert-True ((Get-Content -LiteralPath (Join-Path $config.paths.velocityHome 'velocity.jar') -Raw -Encoding UTF8) -eq 'r1-velocity-core') 'r1 core artifact was not deployed'
  Assert-True ((Get-Content -LiteralPath (Join-Path $config.paths.velocityHome 'plugins\starx-universal.jar') -Raw -Encoding UTF8) -eq 'r1-starx-universal') 'r1 universal plugin was not deployed to Velocity'
  Assert-True ((Get-Content -LiteralPath (Join-Path $config.paths.paperHome 'plugins\starx-universal.jar') -Raw -Encoding UTF8) -eq 'r1-starx-universal') 'r1 universal plugin was not deployed to Paper'

  Write-Text (Join-Path $config.paths.paperHome 'plugins\runtime-data.db') 'plugin-state-v1'
  $backupR1Output = Invoke-Script 'Backup-StarXProduction.ps1' @{ ConfigPath=$ConfigPath; Reason='fixture-r1' }
  $backupR1Line = $backupR1Output | Where-Object { $_ -like 'BACKUP_ROOT=*' } | Select-Object -Last 1
  $backupMirrorLine = $backupR1Output | Where-Object { $_ -like 'BACKUP_MIRROR_ROOT=*' } | Select-Object -Last 1
  Assert-True ($null -ne $backupR1Line) 'Explicit r1 backup path was not returned'
  Assert-True ($null -ne $backupMirrorLine) 'Verified mirror backup path was not returned'
  $backupR1 = $backupR1Line.Substring('BACKUP_ROOT='.Length)
  $backupMirrorR1 = $backupMirrorLine.Substring('BACKUP_MIRROR_ROOT='.Length)
  Assert-True (Test-Path -LiteralPath (Join-Path $backupMirrorR1 'manifest.json') -PathType Leaf) 'Mirror backup manifest is missing'
  Assert-True ((Get-StarXSha256 (Join-Path $backupR1 'manifest.json')) -eq (Get-StarXSha256 (Join-Path $backupMirrorR1 'manifest.json'))) 'Mirror backup manifest differs from local backup'
  Write-Output 'BACKUP_MIRROR=PASS'

  $deployR2 = Invoke-Script 'Deploy-StarXRelease.ps1' @{
    ConfigPath=$ConfigPath
    ReleaseId='r2'
    NoStart=$true
    Confirm=$false
  }
  Assert-True (($deployR2 -join "`n") -match 'STARX_RELEASE_DEPLOYED=PASS') 'r2 deployment failed'
  $current = Get-Content -LiteralPath (Join-Path $config.paths.stateRoot 'current-release.json') -Raw -Encoding UTF8 | ConvertFrom-Json
  $previous = Get-Content -LiteralPath (Join-Path $config.paths.stateRoot 'previous-release.json') -Raw -Encoding UTF8 | ConvertFrom-Json
  Assert-True ($current.releaseId -eq 'r2' -and $previous.releaseId -eq 'r1') 'Current/previous release state is incorrect after r2 deployment'
  Assert-True ((Get-Content -LiteralPath (Join-Path $config.paths.velocityHome 'velocity.jar') -Raw -Encoding UTF8) -eq 'r2-velocity-core') 'r2 core artifact was not deployed'
  Assert-True (@(Get-ChildItem -LiteralPath $config.paths.backupRoot -Directory).Count -ge 2) 'Pre-deployment backup was not created'

  Assert-Throws {
    Invoke-Script 'Deploy-StarXRelease.ps1' @{
      ConfigPath=$ConfigPath
      ReleaseId='bad'
      SkipBackup=$true
      NoStart=$true
      Confirm=$false
    } | Out-Null
  } 'previous version was restored'
  $currentAfterFailure = Get-Content -LiteralPath (Join-Path $config.paths.stateRoot 'current-release.json') -Raw -Encoding UTF8 | ConvertFrom-Json
  $previousAfterFailure = Get-Content -LiteralPath (Join-Path $config.paths.stateRoot 'previous-release.json') -Raw -Encoding UTF8 | ConvertFrom-Json
  Assert-True ($currentAfterFailure.releaseId -eq 'r2') 'Failed deployment did not restore current release state'
  Assert-True ($previousAfterFailure.releaseId -eq 'r1') 'Failed deployment did not restore previous release history'
  Assert-True ((Get-Content -LiteralPath (Join-Path $config.paths.velocityHome 'velocity.jar') -Raw -Encoding UTF8) -eq 'r2-velocity-core') 'Failed deployment did not restore runtime artifacts'
  Write-Output 'FAILED_DEPLOYMENT_ROLLBACK=PASS'

  $rollback = Invoke-Script 'Rollback-StarXProduction.ps1' @{
    ConfigPath=$ConfigPath
    ReleaseId='r1'
    SkipBackup=$true
    NoStart=$true
    Confirm=$false
  }
  Assert-True (($rollback -join "`n") -match 'STARX_PRODUCTION_ROLLBACK=PASS') 'Explicit version rollback failed'
  Assert-True ((Get-Content -LiteralPath (Join-Path $config.paths.velocityHome 'velocity.jar') -Raw -Encoding UTF8) -eq 'r1-velocity-core') 'r1 artifact was not restored by rollback'

  Write-Text (Join-Path $config.paths.paperHome 'world\player.dat') 'corrupted-live-state'
  Write-Text (Join-Path $config.paths.paperHome 'plugins\runtime-data.db') 'corrupted-plugin-state'
  $restore = Invoke-Script 'Restore-StarXProduction.ps1' @{ ConfigPath=$ConfigPath; BackupPath=$backupR1; Confirm=$false }
  Assert-True (($restore -join "`n") -match 'STARX_BACKUP_RESTORED=PASS') 'Backup restore failed'
  Assert-True ((Get-Content -LiteralPath (Join-Path $config.paths.paperHome 'world\player.dat') -Raw -Encoding UTF8) -eq 'player-state-v1') 'World data was not restored'
  Assert-True ((Get-Content -LiteralPath (Join-Path $config.paths.paperHome 'plugins\runtime-data.db') -Raw -Encoding UTF8) -eq 'plugin-state-v1') 'Plugin data was not restored'

  $manifest = Get-Content -LiteralPath (Join-Path $backupR1 'manifest.json') -Raw -Encoding UTF8 | ConvertFrom-Json
  $payloadFile = Join-Path (Join-Path $backupR1 'payload') ([string]$manifest.files[0].path)
  Add-Content -LiteralPath $payloadFile -Value 'tamper' -Encoding UTF8
  Assert-Throws { Invoke-Script 'Restore-StarXProduction.ps1' @{ ConfigPath=$ConfigPath; BackupPath=$backupR1; Confirm=$false } | Out-Null } 'checksum mismatch'
  Write-Output 'BACKUP_TAMPER_REJECTION=PASS'

  $unsafePath = Join-Path $FixtureRoot 'control\unsafe.config.json'
  $unsafe = Get-Content -LiteralPath $ConfigPath -Raw -Encoding UTF8 | ConvertFrom-Json
  $unsafe.network.bindAddress = '0.0.0.0'
  $unsafe.security.velocityOnlineMode = $false
  $unsafe.security.allowInsecurePublicOffline = $false
  [IO.File]::WriteAllText($unsafePath,($unsafe | ConvertTo-Json -Depth 12) + [Environment]::NewLine,$Utf8)
  Import-Module (Join-Path $ProductionScripts 'StarX.Production.psm1') -Force
  Assert-Throws { Import-StarXProductionConfig $unsafePath | Out-Null } 'public offline-mode'
  Assert-Throws { Get-StarXFlags (Join-Path $ProductionScripts 'jvm\velocity-production.flags') @{} | Out-Null } 'Unresolved JVM flag variable'
  Write-Output 'UNSAFE_CONFIGURATION_REJECTION=PASS'

  $stackPath = Join-Path $config.paths.stateRoot 'stack.json'
  $start = (Get-Process -Id $PID).StartTime.ToUniversalTime().ToString('o')
  [IO.File]::WriteAllText($stackPath,([ordered]@{
    schemaVersion=1
    releaseId='r1'
    velocityPid=$PID
    velocityStartedAt=$start
    paperPid=$PID
    paperStartedAt=$start
  } | ConvertTo-Json -Depth 5) + [Environment]::NewLine,$Utf8)
  Assert-Throws { Invoke-Script 'Stop-StarXProduction.ps1' @{ ConfigPath=$ConfigPath; Confirm=$false } | Out-Null } 'Refusing to stop untrusted'
  Assert-True ($null -ne (Get-Process -Id $PID -ErrorAction SilentlyContinue)) 'Production stop guard terminated the test host'
  Assert-True (Test-Path -LiteralPath $stackPath -PathType Leaf) 'Production stop guard removed state after refusal'
  Remove-Item -LiteralPath $stackPath -Force
  Write-Output 'PRODUCTION_PID_GUARD=PASS'

  $breakerUntil = [DateTimeOffset]::UtcNow.AddMinutes(5).ToString('o')
  [IO.File]::WriteAllText((Join-Path $config.paths.stateRoot 'watchdog.json'),([ordered]@{
    schemaVersion=1
    failureTimes=@()
    failedRestarts=0
    breakerUntil=$breakerUntil
    lastHealthyAt=$null
    lastCheckAt=$null
    lastAction='fixture-breaker'
  } | ConvertTo-Json -Depth 5) + [Environment]::NewLine,$Utf8)
  $watchOut = Join-Path $FixtureRoot 'watchdog.out.log'
  $watchErr = Join-Path $FixtureRoot 'watchdog.err.log'
  $watch = Start-Process powershell.exe -ArgumentList @(
    '-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $ProductionScripts 'Watch-StarXProduction.ps1'),
    '-ConfigPath',$ConfigPath,'-Once'
  ) -RedirectStandardOutput $watchOut -RedirectStandardError $watchErr -PassThru -Wait -WindowStyle Hidden
  $watchText = (Get-Content -LiteralPath $watchOut -Raw -ErrorAction SilentlyContinue) + (Get-Content -LiteralPath $watchErr -Raw -ErrorAction SilentlyContinue)
  Assert-True ($watch.ExitCode -eq 2) "Watchdog breaker exit code was $($watch.ExitCode), expected 2"
  Assert-True ($watchText -match 'WATCHDOG_BREAKER_UNTIL=') 'Watchdog did not honor the open breaker'
  Write-Output 'WATCHDOG_BREAKER=PASS'

  $taskWhatIf = Invoke-Script 'Install-StarXProductionTasks.ps1' @{ ConfigPath=$ConfigPath; WhatIf=$true }
  Assert-True (($taskWhatIf -join "`n") -match 'STARX_PRODUCTION_TASKS_INSTALLED=PASS') 'Scheduled task WhatIf validation failed'

  $static = Invoke-Script 'Test-StarXProduction.ps1' @{ ConfigPath=$ConfigPath; StaticOnly=$true; NoThrow=$true; Json=$true } | ConvertFrom-Json
  Assert-True ([bool]$static.healthy) ('Final fixture static verification failed: ' + ($static.failures -join '; '))
  Write-Output 'PRODUCTION_CONTROL_PLANE_TESTS=PASS'
} finally {
  Remove-Item -LiteralPath $FixtureRoot -Recurse -Force -ErrorAction SilentlyContinue
}
