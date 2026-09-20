[CmdletBinding(SupportsShouldProcess)]
param([Parameter(Mandatory)][string] $ConfigPath)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'StarX.Production.psm1') -Force
$config = Import-StarXProductionConfig $ConfigPath
Initialize-StarXProductionDirectories $config
$mutex = Enter-StarXProductionLock $config
$utf8 = [Text.UTF8Encoding]::new($false)

function Expand-Template([string] $Path,[hashtable] $Variables) {
  if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Template not found: $Path" }
  $text = Get-Content -LiteralPath $Path -Raw -Encoding UTF8
  foreach ($entry in $Variables.GetEnumerator()) { $text = $text.Replace('${' + $entry.Key + '}',[string]$entry.Value) }
  if ($text -match '\$\{[A-Z0-9_]+\}') { throw "Unresolved template variable in $Path" }
  return $text
}

function Set-Properties([string] $Path,[Collections.IDictionary] $Values) {
  $lines = if (Test-Path -LiteralPath $Path -PathType Leaf) { @(Get-Content -LiteralPath $Path -Encoding UTF8) } else { @() }
  $written = @{}
  $output = New-Object System.Collections.Generic.List[string]
  foreach ($line in $lines) {
    $trimmed = $line.Trim()
    if ($trimmed -and -not $trimmed.StartsWith('#') -and $trimmed.Contains('=')) {
      $key = $trimmed.Split('=',2)[0].Trim()
      if ($Values.Contains($key)) { $output.Add("$key=$($Values[$key])"); $written[$key]=$true; continue }
    }
    $output.Add($line)
  }
  foreach ($entry in $Values.GetEnumerator()) { if (-not $written.ContainsKey($entry.Key)) { $output.Add("$($entry.Key)=$($entry.Value)") } }
  [IO.File]::WriteAllLines($Path,$output,$utf8)
}

try {
  $eula = Join-Path $config.paths.paperHome 'eula.txt'
  if (-not (Test-Path -LiteralPath $eula -PathType Leaf) -or (Get-Content -LiteralPath $eula -Raw -Encoding UTF8) -notmatch '(?m)^eula=true\s*$') {
    throw "Paper EULA has not been explicitly accepted. Review Mojang's EULA, then create $eula containing eula=true"
  }
  $forwardingSecret = Read-StarXSecret ([string]$config.security.forwardingSecretFile) 'forwarding'
  $rconPassword = Read-StarXSecret ([string]$config.security.rconPasswordFile) 'RCON'
  $apiKey = Read-StarXSecret ([string]$config.security.apiKeyFile) 'API'
  $online = ([bool]$config.security.velocityOnlineMode).ToString().ToLowerInvariant()
  $bedrock = ([bool]$config.network.bedrockEnabled).ToString().ToLowerInvariant()

  if ($PSCmdlet.ShouldProcess($config.paths.velocityHome,'Write production Velocity and StarX configuration')) {
    $velocityToml = @(
      "bind = `"$($config.network.bindAddress):$($config.network.javaPort)`"",
      "online-mode = $online",
      'player-info-forwarding-mode = "modern"',
      'forwarding-secret-file = "forwarding.secret"',
      "force-key-authentication = $online",
      "motd = `"$(([string]$config.server.motd).Replace('`"','\`"'))`"",
      'config-version = "2.8"',
      "show-max-players = $($config.server.maxPlayers)",
      'login-ratelimit = 1000',
      '',
      '[servers]',
      "$($config.server.nodeId) = `"$($config.network.backendAddress):$($config.network.backendPort)`"",
      "try = [`"$($config.server.nodeId)`"]",
      '',
      '[forced-hosts]',
      ''
    )
    [IO.File]::WriteAllLines((Join-Path $config.paths.velocityHome 'velocity.toml'),$velocityToml,$utf8)
    $runtimeForwardingSecret = Join-Path $config.paths.velocityHome 'forwarding.secret'
    [IO.File]::WriteAllText($runtimeForwardingSecret,$forwardingSecret + [Environment]::NewLine,$utf8)
    Set-StarXRestrictedFileAcl $runtimeForwardingSecret
    $starxRoot = Join-Path $config.paths.velocityHome 'plugins\starx'
    [IO.Directory]::CreateDirectory($starxRoot) | Out-Null
    $velocityConfig = Expand-Template (Join-Path $PSScriptRoot 'templates\starx-velocity.safe.yml') @{
      API_KEY=$apiKey
      HTTP_BIND=[string]$config.network.httpAddress
      HTTP_PORT=[string]$config.network.httpPort
      BEDROCK_ENABLED=$bedrock
      BEDROCK_PORT=[string]$config.network.bedrockPort
      TARGET_SERVER=[string]$config.server.targetServer
    }
    $velocityStarXConfig = Join-Path $starxRoot 'config.yml'
    [IO.File]::WriteAllText($velocityStarXConfig,$velocityConfig,$utf8)
    Set-StarXRestrictedFileAcl $velocityStarXConfig
  }

  if ($PSCmdlet.ShouldProcess($config.paths.paperHome,'Patch production Paper and StarX configuration')) {
    $serverPropertiesPath = Join-Path $config.paths.paperHome 'server.properties'
    Set-Properties $serverPropertiesPath ([ordered]@{
      'server-ip'=[string]$config.network.backendAddress
      'server-port'=[string]$config.network.backendPort
      'online-mode'='false'
      'enforce-secure-profile'='false'
      'enable-rcon'='true'
      'rcon.port'=[string]$config.network.rconPort
      'rcon.password'=$rconPassword
      'enable-query'='false'
      'enable-jmx-monitoring'='false'
      'management-server-enabled'='false'
      'max-players'=[string]$config.server.maxPlayers
      'motd'=[string]$config.server.motd
    })
    Set-StarXRestrictedFileAcl $serverPropertiesPath
    $paperGlobalPath = Join-Path $config.paths.paperHome 'config\paper-global.yml'
    if (-not (Test-Path -LiteralPath $paperGlobalPath -PathType Leaf)) { throw "Version-matched Paper global config missing from release: $paperGlobalPath" }
    $paperGlobal = Get-Content -LiteralPath $paperGlobalPath -Raw -Encoding UTF8
    $pattern = '(?ms)(  velocity:\r?\n    enabled:\s*)[^\r\n]+(\r?\n    online-mode:\s*)[^\r\n]+(\r?\n    secret:\s*)[^\r\n]+'
    if ([regex]::Matches($paperGlobal,$pattern).Count -ne 1) { throw 'Paper velocity proxy section was not found exactly once' }
    $paperGlobal = [regex]::Replace($paperGlobal,$pattern,{ param($m) $m.Groups[1].Value + 'true' + $m.Groups[2].Value + $online + $m.Groups[3].Value + $forwardingSecret })
    $paperGlobal = [regex]::Replace($paperGlobal,'(?m)^(update-checker:\s*\r?\n\s*enabled:\s*)true\s*$','${1}false')
    [IO.File]::WriteAllText($paperGlobalPath,$paperGlobal,$utf8)
    Set-StarXRestrictedFileAcl $paperGlobalPath
    $serverRoot = Join-Path $config.paths.paperHome 'plugins\StarXServer'
    [IO.Directory]::CreateDirectory($serverRoot) | Out-Null
    $baseUrl = ([string]$config.network.healthUrl) -replace '/v1/health$',''
    $serverConfig = Expand-Template (Join-Path $PSScriptRoot 'templates\starx-server.safe.yml') @{
      NODE_ID=[string]$config.server.nodeId
      SERVER_TYPE=[string]$config.server.serverType
      HEALTH_BASE_URL=$baseUrl
      API_KEY=$apiKey
    }
    $paperStarXConfig = Join-Path $serverRoot 'config.yml'
    [IO.File]::WriteAllText($paperStarXConfig,$serverConfig,$utf8)
    Set-StarXRestrictedFileAcl $paperStarXConfig
  }

  if ([bool]$config.network.bedrockEnabled) {
    $geyserCandidates = @(
      (Join-Path $config.paths.velocityHome 'plugins\Geyser-Velocity\config.yml'),
      (Join-Path $config.paths.velocityHome 'plugins\Geyser\config.yml')
    )
    $geyserPath = $geyserCandidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
    if (-not $geyserPath) { throw 'Bedrock is enabled but a versioned Geyser config is missing from the release' }
    if ($PSCmdlet.ShouldProcess($geyserPath,'Patch Geyser production bind and Floodgate authentication')) {
      $geyser = Get-Content -LiteralPath $geyserPath -Raw -Encoding UTF8
      $geyser = [regex]::Replace($geyser,'(?m)^(\s*address:\s*).+$','${1}' + [string]$config.network.bindAddress,1)
      $geyser = [regex]::Replace($geyser,'(?m)^(\s*port:\s*)\d+\s*$','${1}' + [string]$config.network.bedrockPort,1)
      $geyser = [regex]::Replace($geyser,'(?m)^(\s*auth-type:\s*).+$','${1}floodgate',1)
      [IO.File]::WriteAllText($geyserPath,$geyser,$utf8)
    }
  }

  Add-StarXProductionEvent $config 'configuration-applied' 'Production configuration applied' @{ nodeId=[string]$config.server.nodeId; onlineMode=[bool]$config.security.velocityOnlineMode; bedrock=[bool]$config.network.bedrockEnabled }
  Write-Output 'STARX_PRODUCTION_CONFIGURED=PASS'
} finally {
  Exit-StarXProductionLock $mutex
}
