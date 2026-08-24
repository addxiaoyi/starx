[CmdletBinding()]
param(
  [Parameter(Mandatory)][string] $ConfigPath,
  [switch] $StaticOnly,
  [switch] $Json,
  [switch] $NoThrow
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'StarX.Production.psm1') -Force
$config = Import-StarXProductionConfig $ConfigPath
$failures = New-Object System.Collections.Generic.List[string]
$checks = [ordered]@{}

function Add-Check([string] $Name,[bool] $Passed,[string] $Failure) {
  $checks[$Name] = $Passed
  if (-not $Passed) { $failures.Add($Failure) }
}

function Read-PropertiesFile([string] $Path) {
  $values = @{}
  if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $values }
  foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
    $trimmed = $line.Trim()
    if (-not $trimmed -or $trimmed.StartsWith('#')) { continue }
    $parts = $trimmed.Split('=',2)
    if ($parts.Count -eq 2) { $values[$parts[0].Trim()] = $parts[1].Trim() }
  }
  return $values
}

$requiredFiles = [ordered]@{
  velocityJava = [string]$config.java.velocityExecutable
  paperJava = [string]$config.java.paperExecutable
  velocityFlags = [string]$config.java.velocityFlagsFile
  paperFlags = [string]$config.java.paperFlagsFile
  velocityJar = Join-Path $config.paths.velocityHome ([string]$config.artifacts.velocityJar)
  paperJar = Join-Path $config.paths.paperHome ([string]$config.artifacts.paperJar)
  velocityUniversalPlugin = Join-Path $config.paths.velocityHome ([string]$config.artifacts.universalPlugin)
  paperUniversalPlugin = Join-Path $config.paths.paperHome ([string]$config.artifacts.universalPlugin)
  velocityConfig = Join-Path $config.paths.velocityHome 'velocity.toml'
  paperProperties = Join-Path $config.paths.paperHome 'server.properties'
  paperGlobal = Join-Path $config.paths.paperHome 'config\paper-global.yml'
  paperEula = Join-Path $config.paths.paperHome 'eula.txt'
  forwardingSecret = Join-Path $config.paths.velocityHome 'forwarding.secret'
}
foreach ($entry in $requiredFiles.GetEnumerator()) {
  Add-Check "file.$($entry.Key)" (Test-Path -LiteralPath $entry.Value -PathType Leaf) "Required production file missing: $($entry.Value)"
}
$eulaPath = Join-Path $config.paths.paperHome 'eula.txt'
$eulaAccepted = (Test-Path -LiteralPath $eulaPath -PathType Leaf) -and ((Get-Content -LiteralPath $eulaPath -Raw -Encoding UTF8) -match '(?m)^eula=true\s*$')
Add-Check 'config.paper.eula' $eulaAccepted 'Paper EULA has not been explicitly accepted'

if ([bool]$config.network.bedrockEnabled) {
  $geyserJar = @(Get-ChildItem -LiteralPath (Join-Path $config.paths.velocityHome 'plugins') -File -Filter '*Geyser*.jar' -ErrorAction SilentlyContinue).Count -gt 0
  $floodgateJar = @(Get-ChildItem -LiteralPath (Join-Path $config.paths.velocityHome 'plugins') -File -Filter '*floodgate*.jar' -ErrorAction SilentlyContinue).Count -gt 0
  $geyserConfig = (Test-Path -LiteralPath (Join-Path $config.paths.velocityHome 'plugins\Geyser-Velocity\config.yml') -PathType Leaf) -or
    (Test-Path -LiteralPath (Join-Path $config.paths.velocityHome 'plugins\Geyser\config.yml') -PathType Leaf)
  Add-Check 'bedrock.geyserJar' $geyserJar 'Bedrock is enabled but no Geyser JAR is installed'
  Add-Check 'bedrock.floodgateJar' $floodgateJar 'Bedrock is enabled but no Floodgate JAR is installed'
  Add-Check 'bedrock.geyserConfig' $geyserConfig 'Bedrock is enabled but no Geyser configuration is installed'
}

foreach ($secret in @(
  @{ name='RCON'; path=[string]$config.security.rconPasswordFile },
  @{ name='API'; path=[string]$config.security.apiKeyFile },
  @{ name='forwarding'; path=[string]$config.security.forwardingSecretFile }
)) {
  try {
    [void](Read-StarXSecret $secret.path $secret.name)
    Add-Check "secret.$($secret.name)" $true ''
    Add-Check "secretAcl.$($secret.name)" (Test-StarXRestrictedFileAcl $secret.path) "$($secret.name) secret ACL is not restricted"
  } catch {
    Add-Check "secret.$($secret.name)" $false $_.Exception.Message
  }
}

foreach ($sensitive in @(
  @{ name='velocityForwarding'; path=(Join-Path $config.paths.velocityHome 'forwarding.secret') },
  @{ name='velocityStarX'; path=(Join-Path $config.paths.velocityHome 'plugins\starx\config.yml') },
  @{ name='paperProperties'; path=(Join-Path $config.paths.paperHome 'server.properties') },
  @{ name='paperGlobal'; path=(Join-Path $config.paths.paperHome 'config\paper-global.yml') },
  @{ name='paperStarX'; path=(Join-Path $config.paths.paperHome 'plugins\StarXServer\config.yml') }
)) {
  if (Test-Path -LiteralPath $sensitive.path -PathType Leaf) {
    Add-Check "runtimeAcl.$($sensitive.name)" (Test-StarXRestrictedFileAcl $sensitive.path) "Sensitive runtime file ACL is not restricted: $($sensitive.path)"
  }
}

$currentPath = Join-Path $config.paths.stateRoot 'current-release.json'
$current = Read-StarXJson $currentPath -Optional
Add-Check 'release.current' ($null -ne $current -and -not [string]::IsNullOrWhiteSpace([string]$current.releaseId)) "Current release state missing: $currentPath"
if ($current) {
  $releaseRoot = Join-Path $config.paths.releaseRoot ([string]$current.releaseId)
  $manifestPath = Join-Path $releaseRoot 'manifest.json'
  $manifest = Read-StarXJson $manifestPath -Optional
  Add-Check 'release.manifest' ($null -ne $manifest) "Current release manifest missing: $manifestPath"
  if ($manifest) {
    foreach ($file in $manifest.files) {
      $relative = ([string]$file.path).Replace('\','/')
      $releaseFile = Join-Path $releaseRoot $relative
      $runtimeFile = if ($relative.StartsWith('velocity/')) {
        Join-Path $config.paths.velocityHome $relative.Substring('velocity/'.Length)
      } elseif ($relative.StartsWith('paper/')) {
        Join-Path $config.paths.paperHome $relative.Substring('paper/'.Length)
      } else {
        $null
      }
      if (-not $runtimeFile) {
        Add-Check ("release.path." + $relative.Replace('/','.')) $false "Unsupported release path: $relative"
        continue
      }
      $releaseMatches = (Test-Path -LiteralPath $releaseFile -PathType Leaf) -and ((Get-StarXSha256 $releaseFile) -eq [string]$file.sha256)
      $runtimeExists = Test-Path -LiteralPath $runtimeFile -PathType Leaf
      $isMutable = $file.PSObject.Properties.Name -contains 'mutable' -and [bool]$file.mutable
      $runtimeMatches = $runtimeExists -and ($isMutable -or ((Get-StarXSha256 $runtimeFile) -eq [string]$file.sha256))
      Add-Check ("release.hash." + $relative.Replace('/','.')) ($releaseMatches -and $runtimeMatches) "Runtime file does not satisfy release manifest: $relative"
    }
  }
}

$velocityTomlPath = Join-Path $config.paths.velocityHome 'velocity.toml'
if (Test-Path -LiteralPath $velocityTomlPath -PathType Leaf) {
  $toml = Get-Content -LiteralPath $velocityTomlPath -Raw -Encoding UTF8
  $expectedBind = [regex]::Escape("$($config.network.bindAddress):$($config.network.javaPort)")
  Add-Check 'config.velocity.bind' ($toml -match "(?m)^bind\s*=\s*`"$expectedBind`"\s*$") 'Velocity bind does not match production config'
  $expectedOnline = ([bool]$config.security.velocityOnlineMode).ToString().ToLowerInvariant()
  Add-Check 'config.velocity.onlineMode' ($toml -match "(?m)^online-mode\s*=\s*$expectedOnline\s*$") 'Velocity online-mode does not match production security config'
  Add-Check 'config.velocity.forwarding' ($toml -match '(?m)^player-info-forwarding-mode\s*=\s*"modern"\s*$') 'Velocity modern forwarding is not enabled'
}

$propertiesPath = Join-Path $config.paths.paperHome 'server.properties'
if (Test-Path -LiteralPath $propertiesPath -PathType Leaf) {
  $properties = Read-PropertiesFile $propertiesPath
  Add-Check 'config.paper.address' ($properties['server-ip'] -eq [string]$config.network.backendAddress) 'Paper server-ip does not match production config'
  Add-Check 'config.paper.port' ($properties['server-port'] -eq [string]$config.network.backendPort) 'Paper server-port does not match production config'
  Add-Check 'config.paper.offline' ($properties['online-mode'] -eq 'false') 'Paper must use online-mode=false behind Velocity'
  Add-Check 'config.paper.rcon' ($properties['enable-rcon'] -eq 'true' -and $properties['rcon.port'] -eq [string]$config.network.rconPort) 'Paper RCON is not correctly configured'
}

$paperGlobalPath = Join-Path $config.paths.paperHome 'config\paper-global.yml'
if (Test-Path -LiteralPath $paperGlobalPath -PathType Leaf) {
  $paperGlobal = Get-Content -LiteralPath $paperGlobalPath -Raw -Encoding UTF8
  Add-Check 'config.paper.forwardingEnabled' ($paperGlobal -match '(?ms)  velocity:\s*\r?\n    enabled:\s*true') 'Paper Velocity forwarding is not enabled'
  $expectedPaperOnline = ([bool]$config.security.velocityOnlineMode).ToString().ToLowerInvariant()
  Add-Check 'config.paper.forwardingOnlineMode' ($paperGlobal -match "(?ms)  velocity:.*?online-mode:\s*$expectedPaperOnline") 'Paper forwarding online-mode does not match Velocity'
}

$runtimeSecretPath = Join-Path $config.paths.velocityHome 'forwarding.secret'
if ((Test-Path -LiteralPath $runtimeSecretPath -PathType Leaf) -and (Test-Path -LiteralPath $config.security.forwardingSecretFile -PathType Leaf)) {
  $runtimeSecret = (Get-Content -LiteralPath $runtimeSecretPath -Raw -Encoding UTF8).Trim()
  $protectedSecret = Read-StarXSecret ([string]$config.security.forwardingSecretFile) 'forwarding'
  Add-Check 'config.forwardingSecret' ([string]::Equals($runtimeSecret,$protectedSecret,[StringComparison]::Ordinal)) 'Runtime forwarding secret differs from protected secret'
}

if (-not $StaticOnly) {
  $stackPath = Join-Path $config.paths.stateRoot 'stack.json'
  $stack = Read-StarXJson $stackPath -Optional
  Add-Check 'runtime.state' ($null -ne $stack) "Production stack state missing: $stackPath"
  if ($stack) {
    $velocity = Get-StarXProcessIdentity ([int]$stack.velocityPid) ([string]$config.artifacts.velocityJar) ([string]$config.java.velocityExecutable) ([string]$stack.velocityStartedAt)
    $paper = Get-StarXProcessIdentity ([int]$stack.paperPid) ([string]$config.artifacts.paperJar) ([string]$config.java.paperExecutable) ([string]$stack.paperStartedAt)
    Add-Check 'runtime.velocity.identity' ($velocity.exists -and $velocity.trusted) "Velocity process identity failed: $($velocity.reason)"
    Add-Check 'runtime.paper.identity' ($paper.exists -and $paper.trusted) "Paper process identity failed: $($paper.reason)"
    Add-Check 'runtime.velocity.javaPort' (Test-StarXTcpListener ([int]$config.network.javaPort) ([int]$stack.velocityPid) ([string]$config.network.bindAddress)) 'Velocity Java listener ownership/bind mismatch'
    Add-Check 'runtime.velocity.http' (Test-StarXTcpListener ([int]$config.network.httpPort) ([int]$stack.velocityPid) ([string]$config.network.httpAddress)) 'Velocity HTTP listener ownership/bind mismatch'
    if ([bool]$config.network.bedrockEnabled) { Add-Check 'runtime.velocity.bedrock' (Test-StarXUdpListener ([int]$config.network.bedrockPort) ([int]$stack.velocityPid) ([string]$config.network.bindAddress)) 'Geyser Bedrock listener ownership/bind mismatch' }
    Add-Check 'runtime.paper.backend' (Test-StarXTcpListener ([int]$config.network.backendPort) ([int]$stack.paperPid) ([string]$config.network.backendAddress)) 'Paper backend listener ownership/bind mismatch'
    Add-Check 'runtime.paper.rcon' (Test-StarXTcpListener ([int]$config.network.rconPort) ([int]$stack.paperPid) ([string]$config.network.rconAddress)) 'Paper RCON listener ownership/bind mismatch'
    Add-Check 'runtime.health' (Test-StarXHttpHealth ([string]$config.network.healthUrl) 5) 'StarX HTTP health check failed'
  }
}

$result = [ordered]@{
  checkedAt = [DateTimeOffset]::UtcNow.ToString('o')
  mode = if($StaticOnly){'static'}else{'live'}
  healthy = $failures.Count -eq 0
  checks = $checks
  failures = $failures.ToArray()
}
Write-StarXJsonAtomic (Join-Path $config.paths.stateRoot 'status.json') $result
if ($Json) { $result | ConvertTo-Json -Depth 10 } else {
  Write-Output ("STARX_PRODUCTION_HEALTHY=$($result.healthy)")
  foreach ($failure in $failures) { Write-Output ("PRODUCTION_FAILURE=$failure") }
}
if (-not $result.healthy -and -not $NoThrow) { throw "StarX production verification failed with $($failures.Count) issue(s)" }
