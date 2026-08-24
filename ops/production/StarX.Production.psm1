Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Resolve-StarXPath {
  [CmdletBinding()]
  param([Parameter(Mandatory)][string] $Path,[Parameter(Mandatory)][string] $BaseDirectory)
  $candidate = if ([IO.Path]::IsPathRooted($Path)) { $Path } else { Join-Path $BaseDirectory $Path }
  return [IO.Path]::GetFullPath($candidate)
}

function Import-StarXProductionConfig {
  [CmdletBinding()]
  param([Parameter(Mandatory)][string] $ConfigPath)
  $resolved = [IO.Path]::GetFullPath($ConfigPath)
  if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) { throw "Production config not found: $resolved" }
  $config = Get-Content -LiteralPath $resolved -Raw -Encoding UTF8 | ConvertFrom-Json
  if ([int]$config.schemaVersion -ne 1) { throw "Unsupported production config schema: $($config.schemaVersion)" }
  if ([string]$config.environment -ne 'production') { throw 'Production config must set environment=production' }
  $base = Split-Path -Parent $resolved
  foreach ($name in @('velocityHome','paperHome','releaseRoot','backupRoot','stateRoot','logRoot')) { $config.paths.$name = Resolve-StarXPath ([string]$config.paths.$name) $base }
  foreach ($name in @('velocityExecutable','paperExecutable','velocityFlagsFile','paperFlagsFile')) { $config.java.$name = Resolve-StarXPath ([string]$config.java.$name) $base }
  foreach ($name in @('rconPasswordFile','apiKeyFile','forwardingSecretFile')) { $config.security.$name = Resolve-StarXPath ([string]$config.security.$name) $base }
  if ($config.backup.PSObject.Properties.Name -contains 'mirrorRoot' -and -not [string]::IsNullOrWhiteSpace([string]$config.backup.mirrorRoot)) {
    $config.backup.mirrorRoot = Resolve-StarXPath ([string]$config.backup.mirrorRoot) $base
  }
  $config | Add-Member -NotePropertyName configPath -NotePropertyValue $resolved -Force
  Assert-StarXProductionConfig $config
  return $config
}

function Assert-StarXProductionConfig {
  [CmdletBinding()]
  param([Parameter(Mandatory)] $Config)
  $ports = @([int]$Config.network.javaPort,[int]$Config.network.backendPort,[int]$Config.network.bedrockPort,[int]$Config.network.httpPort,[int]$Config.network.rconPort)
  foreach ($port in $ports) { if ($port -lt 1 -or $port -gt 65535) { throw "Invalid network port: $port" } }
  if (($ports | Sort-Object -Unique).Count -ne $ports.Count) { throw 'Production network ports must be unique' }
  $publicBind = [string]$Config.network.bindAddress -notin @('127.0.0.1','localhost','::1')
  if ($publicBind -and -not [bool]$Config.security.velocityOnlineMode -and -not [bool]$Config.security.allowInsecurePublicOffline) { throw 'Refusing public offline-mode configuration. Enable Velocity online mode or explicitly allow insecure public offline mode.' }
  if ([string]$Config.network.httpAddress -notin @('127.0.0.1','localhost','::1')) { throw 'Production HTTP API must bind to loopback; expose it through an authenticated reverse proxy.' }
  if ([string]$Config.network.rconAddress -notin @('127.0.0.1','localhost','::1')) { throw 'Production RCON must bind to loopback.' }
  if (-not ($Config.artifacts.PSObject.Properties.Name -contains 'universalPlugin') -or [string]::IsNullOrWhiteSpace([string]$Config.artifacts.universalPlugin)) { throw 'Production artifacts.universalPlugin must point to the shared StarX universal JAR path' }
  if ([int]$Config.backup.retentionCount -lt 2) { throw 'Backup retentionCount must be at least 2' }
  if ($Config.backup.PSObject.Properties.Name -contains 'mirrorRoot' -and -not [string]::IsNullOrWhiteSpace([string]$Config.backup.mirrorRoot)) {
    if ([string]::Equals([IO.Path]::GetFullPath([string]$Config.backup.mirrorRoot),[IO.Path]::GetFullPath([string]$Config.paths.backupRoot),[StringComparison]::OrdinalIgnoreCase)) { throw 'backup.mirrorRoot must differ from paths.backupRoot' }
    if ([int]$Config.backup.mirrorRetentionCount -lt 2) { throw 'Backup mirrorRetentionCount must be at least 2' }
  }
  if ([int]$Config.watchdog.restartLimit -lt 1) { throw 'Watchdog restartLimit must be at least 1' }
  if ([int]$Config.watchdog.pollSeconds -lt 10) { throw 'Watchdog pollSeconds must be at least 10' }
}

function Initialize-StarXProductionDirectories {
  [CmdletBinding()]
  param([Parameter(Mandatory)] $Config)
  foreach ($path in @($Config.paths.velocityHome,$Config.paths.paperHome,$Config.paths.releaseRoot,$Config.paths.backupRoot,$Config.paths.stateRoot,$Config.paths.logRoot)) { [IO.Directory]::CreateDirectory([string]$path) | Out-Null }
  if ($Config.backup.PSObject.Properties.Name -contains 'mirrorRoot' -and -not [string]::IsNullOrWhiteSpace([string]$Config.backup.mirrorRoot)) {
    [IO.Directory]::CreateDirectory([string]$Config.backup.mirrorRoot) | Out-Null
  }
}

function Read-StarXSecret {
  [CmdletBinding()]
  param([Parameter(Mandatory)][string] $Path,[Parameter(Mandatory)][string] $Name)
  if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "$Name secret file not found: $Path" }
  $value = (Get-Content -LiteralPath $Path -Raw -Encoding UTF8).Trim()
  if ($value.Length -lt 24) { throw "$Name secret is too short; use at least 24 characters" }
  return $value
}

function Get-StarXSha256 {
  [CmdletBinding()]
  param([Parameter(Mandatory)][string] $Path)
  if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "File not found: $Path" }
  $stream = [IO.File]::OpenRead($Path)
  try {
    $hash = [Security.Cryptography.SHA256]::Create().ComputeHash($stream)
    return ([BitConverter]::ToString($hash) -replace '-', '').ToLowerInvariant()
  } finally {
    $stream.Dispose()
  }
}

function Write-StarXJsonAtomic {
  [CmdletBinding()]
  param([Parameter(Mandatory)][string] $Path,[Parameter(Mandatory)] $Value)
  [IO.Directory]::CreateDirectory((Split-Path -Parent $Path)) | Out-Null
  $temp = "$Path.$([Guid]::NewGuid().ToString('N')).tmp"
  [IO.File]::WriteAllText($temp,($Value | ConvertTo-Json -Depth 12) + [Environment]::NewLine,[Text.UTF8Encoding]::new($false))
  Move-Item -LiteralPath $temp -Destination $Path -Force
}

function Read-StarXJson {
  [CmdletBinding()]
  param([Parameter(Mandatory)][string] $Path,[switch] $Optional)
  if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { if ($Optional) { return $null }; throw "JSON file not found: $Path" }
  return Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json
}

function Add-StarXProductionEvent {
  [CmdletBinding()]
  param([Parameter(Mandatory)] $Config,[Parameter(Mandatory)][string] $Type,[Parameter(Mandatory)][string] $Message,[hashtable] $Data = @{})
  $entry = [ordered]@{ at=[DateTimeOffset]::UtcNow.ToString('o'); type=$Type; message=$Message; data=$Data }
  Add-Content -LiteralPath (Join-Path $Config.paths.logRoot 'production-events.jsonl') -Value ($entry | ConvertTo-Json -Compress -Depth 8) -Encoding UTF8
}

function Get-StarXFlags {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory)][string] $Path,
    [hashtable] $Variables = @{}
  )
  if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "JVM flags file not found: $Path" }
  $flags = @(Get-Content -LiteralPath $Path -Encoding UTF8 | ForEach-Object { $_.Trim() } | Where-Object { $_ -and -not $_.StartsWith('#') })
  return @($flags | ForEach-Object {
    $value = $_
    foreach ($entry in $Variables.GetEnumerator()) { $value = $value.Replace('${' + $entry.Key + '}', [string]$entry.Value) }
    if ($value -match '\$\{[A-Z0-9_]+\}') { throw "Unresolved JVM flag variable: $value" }
    $value
  })
}

function Enter-StarXProductionLock {
  [CmdletBinding()]
  param([Parameter(Mandatory)] $Config,[int] $TimeoutSeconds = 15)
  $sha = [Security.Cryptography.SHA256]::Create()
  try {
    $digest = $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes(([string]$Config.paths.stateRoot).ToLowerInvariant()))
  } finally {
    $sha.Dispose()
  }
  $hash = (-join ($digest | Select-Object -First 16 | ForEach-Object { $_.ToString('x2') }))
  $mutex = [Threading.Mutex]::new($false,"Global\StarXProduction-$hash")
  if (-not $mutex.WaitOne([TimeSpan]::FromSeconds($TimeoutSeconds))) { $mutex.Dispose(); throw 'Another StarX production operation is already running' }
  return $mutex
}

function Exit-StarXProductionLock {
  [CmdletBinding()]
  param([Parameter(Mandatory)][Threading.Mutex] $Mutex)
  try { $Mutex.ReleaseMutex() } finally { $Mutex.Dispose() }
}

function Test-StarXBindAddress {
  [CmdletBinding()]
  param([Parameter(Mandatory)][string] $Actual,[Parameter(Mandatory)][string] $Expected)
  if ([string]::Equals($Actual,$Expected,[StringComparison]::OrdinalIgnoreCase)) { return $true }
  $actualIp = $null; $expectedIp = $null
  if ([Net.IPAddress]::TryParse($Actual,[ref]$actualIp)) {
    if ($actualIp.IsIPv4MappedToIPv6) { $actualIp = $actualIp.MapToIPv4() }
    if ($Expected -eq 'localhost') { return [Net.IPAddress]::IsLoopback($actualIp) }
    if ([Net.IPAddress]::TryParse($Expected,[ref]$expectedIp)) { if ($expectedIp.IsIPv4MappedToIPv6) { $expectedIp = $expectedIp.MapToIPv4() }; return $actualIp.Equals($expectedIp) }
  }
  return $false
}

function Get-StarXProcessIdentity {
  [CmdletBinding()]
  param([Parameter(Mandatory)][int] $PidValue,[Parameter(Mandatory)][string] $JarName,[string] $ExpectedExecutable = '',[string] $ExpectedStartedAt = '')
  $process = Get-Process -Id $PidValue -ErrorAction SilentlyContinue
  if ($null -eq $process) { return [pscustomobject]@{ exists=$false; trusted=$true; reason='not-running'; startedAt='' } }

  $reasons = New-Object System.Collections.Generic.List[string]
  $record = $null
  try {
    $record = Get-CimInstance Win32_Process -Filter "ProcessId = $PidValue" -ErrorAction Stop
  } catch {
    $reasons.Add("unable to inspect process metadata: $($_.Exception.Message)")
  }

  if ($null -ne $record) {
    $exeName = if ($record.ExecutablePath) { [IO.Path]::GetFileName($record.ExecutablePath) } else { '' }
    if ($exeName -notin @('java.exe','javaw.exe')) { $reasons.Add("unexpected executable: $exeName") }
    if (-not $record.CommandLine -or -not $record.CommandLine.Contains($JarName)) { $reasons.Add("missing jar: $JarName") }
    if ($ExpectedExecutable) { $samePath = $record.ExecutablePath -and [string]::Equals([IO.Path]::GetFullPath($record.ExecutablePath),[IO.Path]::GetFullPath($ExpectedExecutable),[StringComparison]::OrdinalIgnoreCase); if (-not $samePath) { $reasons.Add("Java path mismatch: $($record.ExecutablePath)") } }
  } else {
    $reasons.Add('process metadata unavailable')
  }

  $startedAt = ''
  try { $start = $process.StartTime.ToUniversalTime(); $startedAt = $start.ToString('o'); if ($ExpectedStartedAt) { $expected=[DateTimeOffset]::Parse($ExpectedStartedAt).UtcDateTime; if ([Math]::Abs(($start-$expected).TotalSeconds) -gt 2) { $reasons.Add('process start time mismatch') } } } catch { $reasons.Add("unable to read start time: $($_.Exception.Message)") }
  return [pscustomobject]@{ exists=$true; trusted=$reasons.Count -eq 0; reason=$reasons -join '; '; startedAt=$startedAt; executable=$(if($record){$record.ExecutablePath}else{$process.Path}); commandLine=$(if($record){$record.CommandLine}else{''}) }
}

function Test-StarXTcpListener {
  [CmdletBinding()]
  param([Parameter(Mandatory)][int] $Port,[int] $PidValue = 0,[string] $Address = '')
  $rows = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
  if ($PidValue -gt 0) { $rows = @($rows | Where-Object { $_.OwningProcess -eq $PidValue }) }
  if ($Address) { $rows = @($rows | Where-Object { Test-StarXBindAddress ([string]$_.LocalAddress) $Address }) }
  return $rows.Count -gt 0
}

function Test-StarXUdpListener {
  [CmdletBinding()]
  param([Parameter(Mandatory)][int] $Port,[int] $PidValue = 0,[string] $Address = '')
  $rows = @(Get-NetUDPEndpoint -LocalPort $Port -ErrorAction SilentlyContinue)
  if ($PidValue -gt 0) { $rows = @($rows | Where-Object { $_.OwningProcess -eq $PidValue }) }
  if ($Address) { $rows = @($rows | Where-Object { Test-StarXBindAddress ([string]$_.LocalAddress) $Address }) }
  return $rows.Count -gt 0
}

function Test-StarXHttpHealth {
  [CmdletBinding()]
  param([Parameter(Mandatory)][string] $Url,[int] $TimeoutSeconds = 5)
  try { return (Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec $TimeoutSeconds).StatusCode -eq 200 } catch { return $false }
}

function Wait-StarXCondition {
  [CmdletBinding()]
  param([Parameter(Mandatory)][scriptblock] $Condition,[Parameter(Mandatory)][int] $TimeoutSeconds,[string] $FailureMessage = 'Condition did not become ready')
  $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
  while ([DateTime]::UtcNow -lt $deadline) { if (& $Condition) { return }; Start-Sleep -Milliseconds 500 }
  throw "$FailureMessage within ${TimeoutSeconds}s"
}

function Copy-StarXFileAtomic {
  [CmdletBinding()]
  param([Parameter(Mandatory)][string] $Source,[Parameter(Mandatory)][string] $Destination)
  if (-not (Test-Path -LiteralPath $Source -PathType Leaf)) { throw "Source file not found: $Source" }
  [IO.Directory]::CreateDirectory((Split-Path -Parent $Destination)) | Out-Null
  $temp = "$Destination.$([Guid]::NewGuid().ToString('N')).next"
  Copy-Item -LiteralPath $Source -Destination $temp -Force
  if ((Get-StarXSha256 $temp) -ne (Get-StarXSha256 $Source)) { throw "Atomic copy hash mismatch: $Source" }
  Move-Item -LiteralPath $temp -Destination $Destination -Force
}

function Remove-StarXOldDirectories {
  [CmdletBinding()]
  param([Parameter(Mandatory)][string] $Root,[Parameter(Mandatory)][int] $Keep)
  if (-not (Test-Path -LiteralPath $Root -PathType Container)) { return }
  foreach ($item in @(Get-ChildItem -LiteralPath $Root -Directory | Sort-Object LastWriteTimeUtc -Descending | Select-Object -Skip $Keep)) { Remove-Item -LiteralPath $item.FullName -Recurse -Force }
}

function Invoke-StarXRcon {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory)][string] $HostName,
    [Parameter(Mandatory)][int] $Port,
    [Parameter(Mandatory)][string] $Password,
    [Parameter(Mandatory)][string] $Command,
    [int] $TimeoutMilliseconds = 5000
  )

  function Read-Exact([IO.Stream] $Stream,[int] $Count) {
    $buffer = New-Object byte[] $Count
    $offset = 0
    while ($offset -lt $Count) {
      $read = $Stream.Read($buffer,$offset,$Count-$offset)
      if ($read -le 0) { throw 'RCON connection closed before a complete packet was received' }
      $offset += $read
    }
    return $buffer
  }

  function Send-Packet([IO.Stream] $Stream,[int] $RequestId,[int] $Type,[string] $Payload) {
    $payloadBytes = [Text.Encoding]::UTF8.GetBytes($Payload)
    $length = 10 + $payloadBytes.Length
    $packet = New-Object byte[] ($length + 4)
    [BitConverter]::GetBytes($length).CopyTo($packet,0)
    [BitConverter]::GetBytes($RequestId).CopyTo($packet,4)
    [BitConverter]::GetBytes($Type).CopyTo($packet,8)
    $payloadBytes.CopyTo($packet,12)
    $Stream.Write($packet,0,$packet.Length)
    $Stream.Flush()
  }

  function Read-Packet([IO.Stream] $Stream) {
    $lengthBytes = Read-Exact $Stream 4
    $length = [BitConverter]::ToInt32($lengthBytes,0)
    if ($length -lt 10 -or $length -gt 1048576) { throw "Invalid RCON packet length: $length" }
    $body = Read-Exact $Stream $length
    $requestId = [BitConverter]::ToInt32($body,0)
    $type = [BitConverter]::ToInt32($body,4)
    $payloadLength = $length - 10
    $payload = if ($payloadLength -gt 0) { [Text.Encoding]::UTF8.GetString($body,8,$payloadLength) } else { '' }
    return [pscustomobject]@{ requestId=$requestId; type=$type; payload=$payload }
  }

  $client = [Net.Sockets.TcpClient]::new()
  try {
    $connect = $client.ConnectAsync($HostName,$Port)
    if (-not $connect.Wait($TimeoutMilliseconds) -or -not $client.Connected) { throw "RCON connection timed out: $HostName`:$Port" }
    $client.ReceiveTimeout = $TimeoutMilliseconds
    $client.SendTimeout = $TimeoutMilliseconds
    $stream = $client.GetStream()
    $authId = Get-Random -Minimum 10000 -Maximum 2000000000
    Send-Packet $stream $authId 3 $Password
    $auth = Read-Packet $stream
    if ($auth.requestId -eq -1 -or $auth.requestId -ne $authId) { throw 'RCON authentication failed' }
    $commandId = $authId + 1
    Send-Packet $stream $commandId 2 $Command
    $response = Read-Packet $stream
    if ($response.requestId -ne $commandId) { throw 'RCON response request id mismatch' }
    return [string]$response.payload
  } finally {
    $client.Dispose()
  }
}

function Set-StarXRestrictedFileAcl {
  [CmdletBinding()]
  param([Parameter(Mandatory)][string] $Path)
  if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Sensitive file not found: $Path" }
  $identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
  $sections = [Security.AccessControl.AccessControlSections]::Access -bor
    [Security.AccessControl.AccessControlSections]::Owner -bor
    [Security.AccessControl.AccessControlSections]::Group
  $acl = [IO.File]::GetAccessControl($Path,$sections)
  $acl.SetAccessRuleProtection($true,$false)
  foreach ($existing in @($acl.Access)) {
    [void]$acl.RemoveAccessRuleSpecific($existing)
  }
  foreach ($principal in @($identity,'NT AUTHORITY\SYSTEM','BUILTIN\Administrators')) {
    $rule = New-Object Security.AccessControl.FileSystemAccessRule(
      $principal,
      [Security.AccessControl.FileSystemRights]::FullControl,
      [Security.AccessControl.AccessControlType]::Allow)
    [void]$acl.AddAccessRule($rule)
  }
  [IO.File]::SetAccessControl($Path,$acl)
}

function Test-StarXRestrictedFileAcl {
  [CmdletBinding()]
  param([Parameter(Mandatory)][string] $Path)
  if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $false }
  $acl = [IO.File]::GetAccessControl($Path,[Security.AccessControl.AccessControlSections]::Access)
  if (-not $acl.AreAccessRulesProtected) { return $false }
  $forbidden = @(
    'S-1-1-0',
    'S-1-5-11',
    'S-1-5-32-545'
  )
  $rules = $acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier])
  foreach ($rule in $rules) {
    if ($rule.AccessControlType -eq [Security.AccessControl.AccessControlType]::Allow -and
        $forbidden -contains $rule.IdentityReference.Value) {
      return $false
    }
  }
  return $true
}

Export-ModuleMember -Function *-StarX*
