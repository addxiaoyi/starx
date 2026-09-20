$ErrorActionPreference = "Stop"

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$Root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$Doctor = Join-Path $Root "scripts\check-uworld-environment.ps1"
$PowerShell = (Get-Process -Id $PID).Path
$CurrentServiceIdentity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
$RealJava = (Get-Command java.exe -CommandType Application -ErrorAction Stop |
    Select-Object -First 1).Path
$RuntimeVelocityJar = Join-Path $Root "velocity-test\velocity-3.5.0-SNAPSHOT-606.jar"
$TempRoot = Join-Path ([System.IO.Path]::GetTempPath()) (
  "starx-uworld-environment-test-" + [guid]::NewGuid().ToString("N")
)
$TempPrefix = [System.IO.Path]::GetFullPath(
  [System.IO.Path]::GetTempPath()
).TrimEnd("\") + "\"
$DeniedDirectory = $null
$DeniedDirectorySddl = $null

function Write-AsciiFile([string] $Path, [string] $Content) {
  [System.IO.Directory]::CreateDirectory(
    [System.IO.Path]::GetDirectoryName($Path)
  ) | Out-Null
  [System.IO.File]::WriteAllText($Path, $Content, [System.Text.Encoding]::ASCII)
}

function New-FileSymbolicLink([string] $LinkPath, [string] $TargetPath) {
  $SourcePath = Join-Path $TempRoot "CreateFixtureLink.java"
  Write-AsciiFile $SourcePath @'
import java.nio.file.Files;
import java.nio.file.Path;

final class CreateFixtureLink {
  public static void main(String[] args) throws Exception {
    Files.createSymbolicLink(Path.of(args[0]), Path.of(args[1]));
  }
}
'@
  try {
    $Output = & $RealJava $SourcePath $LinkPath $TargetPath 2>&1
    if ($LASTEXITCODE -ne 0) {
      throw "Unable to create fixture symbolic link`n$($Output -join "`n")"
    }
  } finally {
    Remove-Item -LiteralPath $SourcePath -Force -ErrorAction SilentlyContinue
  }
}

function Write-JarEntry(
  [System.IO.Compression.ZipArchive] $Archive,
  [string] $Name,
  [string] $Content
) {
  $Entry = $Archive.CreateEntry($Name)
  $Writer = [System.IO.StreamWriter]::new(
    $Entry.Open(),
    [System.Text.Encoding]::ASCII
  )
  try {
    $Writer.Write($Content)
  } finally {
    $Writer.Dispose()
  }
}

function Write-TestJar(
  [string] $Path,
  [string] $BuildId,
  [ValidateSet("Starx", "Velocity", "LimboClass")]
  [string] $Kind = "Starx"
) {
  [System.IO.Directory]::CreateDirectory(
    [System.IO.Path]::GetDirectoryName($Path)
  ) | Out-Null
  $Stream = [System.IO.File]::Open(
    $Path,
    [System.IO.FileMode]::Create,
    [System.IO.FileAccess]::ReadWrite,
    [System.IO.FileShare]::None
  )
  try {
    $Archive = [System.IO.Compression.ZipArchive]::new(
      $Stream,
      [System.IO.Compression.ZipArchiveMode]::Create,
      $true
    )
    try {
      Write-JarEntry `
        $Archive `
        "META-INF/MANIFEST.MF" `
        (
          "Manifest-Version: 1.0`r`n" +
          "Implementation-Version: 3.5.0-SNAPSHOT (git-test-b606)`r`n" +
          "Build-Id: $BuildId`r`n`r`n"
        )
      if ($Kind -eq "Starx") {
        Write-JarEntry $Archive "velocity-plugin.json" @'
{
  "id": "starx",
  "name": "StarX",
  "main": "io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin"
}
'@
      } elseif ($Kind -eq "LimboClass") {
        Write-JarEntry $Archive "velocity-plugin.json" @'
{
  "id": "compatibility",
  "name": "Compatibility",
  "main": "example.CompatibilityPlugin"
}
'@
        Write-JarEntry $Archive "net/elytrium/limboapi/LimboAPI.class" "fixture"
      }
    } finally {
      $Archive.Dispose()
    }
  } finally {
    $Stream.Dispose()
  }
}

function Write-FakeJava([string] $Path, [string] $Delegate) {
  Write-AsciiFile $Path @"
@echo off
if "%~1"=="-version" (
  1>&2 echo openjdk version "21.0.7" 2025-04-15
  exit /b 0
)
"$Delegate" %*
exit /b %errorlevel%
"@
}

function Write-VelocityConfig(
  [string] $Path,
  [int] $Port,
  [string] $SecretFile,
  [bool] $OnlineMode = $true,
  [string] $HostName = "127.0.0.1"
) {
  $OnlineModeText = $OnlineMode.ToString().ToLowerInvariant()
  Write-AsciiFile $Path @"
online-mode = $OnlineModeText
player-info-forwarding-mode = "modern"
forwarding-secret-file = "$SecretFile"

[servers]
lobby = "$HostName`:$Port"
"@
}

function Write-StarxConfig(
  [string] $Path,
  [string] $Database,
  [bool] $Primary,
  [string] $Url = ""
) {
  $DatabaseBlock = if ([string]::IsNullOrWhiteSpace($Database) -and
      [string]::IsNullOrWhiteSpace($Url)) {
    ""
  } else {
    @"
database:
  type: "sqlite"
  database: "$Database"
  url: "$Url"
  pool-max-size: 2

"@
  }
  if ($Primary) {
    Write-AsciiFile $Path ($DatabaseBlock + @"
modules:
  starx.uworld:
    enabled: true

uworld:
  enabled: true
  auth:
    target-server: "lobby"
"@)
    return
  }

  Write-AsciiFile $Path ($DatabaseBlock + @"
modules:
  starx.limbo:
    enabled: true

limbo:
  enabled: true
  auth:
    target-server: "lobby"
"@)
}

function Write-PaperConfig(
  [string] $GlobalPath,
  [string] $PropertiesPath,
  [string] $Secret,
  [bool] $ForwardingEnabled,
  [bool] $OnlineMode,
  [int] $ServerPort,
  [string] $ServerIp = "127.0.0.1",
  [bool] $ForwardingOnlineMode = $true
) {
  $EnabledText = $ForwardingEnabled.ToString().ToLowerInvariant()
  $OnlineModeText = $OnlineMode.ToString().ToLowerInvariant()
  $ForwardingOnlineModeText = $ForwardingOnlineMode.ToString().ToLowerInvariant()
  Write-AsciiFile $GlobalPath @"
message: I'm the first fixture line
  continued fixture text

model-overrides:
  minecraft:lodestone:
    also-obfuscate: []

unrelated-list:
  - name: "fixture-entry"
    enabled: false

proxies:
  velocity:
    enabled: $EnabledText
    online-mode: $ForwardingOnlineModeText
    secret: "$Secret"
"@
  Write-AsciiFile $PropertiesPath @"
online-mode=$OnlineModeText
server-port=$ServerPort
server-ip=$ServerIp
"@
}

function Deny-DirectoryWrites([string] $Path) {
  $Script:DeniedDirectory = [System.IO.Path]::GetFullPath($Path)
  $AccessSection = [System.Security.AccessControl.AccessControlSections]::Access
  $Acl = Get-Acl -LiteralPath $Script:DeniedDirectory
  $Script:DeniedDirectorySddl = $Acl.GetSecurityDescriptorSddlForm(
    $AccessSection
  )
  $Identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
  $Rights = [System.Security.AccessControl.FileSystemRights]::CreateFiles -bor
    [System.Security.AccessControl.FileSystemRights]::CreateDirectories -bor
    [System.Security.AccessControl.FileSystemRights]::WriteData -bor
    [System.Security.AccessControl.FileSystemRights]::AppendData
  $Rule = [System.Security.AccessControl.FileSystemAccessRule]::new(
    $Identity,
    $Rights,
    [System.Security.AccessControl.AccessControlType]::Deny
  )
  $Acl.AddAccessRule($Rule) | Out-Null
  Set-Acl -LiteralPath $Script:DeniedDirectory -AclObject $Acl
}

function Restore-DirectoryWrites {
  if ($null -eq $Script:DeniedDirectory -or
      [string]::IsNullOrWhiteSpace($Script:DeniedDirectorySddl) -or
      -not [System.IO.Directory]::Exists($Script:DeniedDirectory)) {
    return
  }
  $Acl = [System.Security.AccessControl.DirectorySecurity]::new()
  $Acl.SetSecurityDescriptorSddlForm(
    $Script:DeniedDirectorySddl,
    [System.Security.AccessControl.AccessControlSections]::Access
  )
  Set-Acl -LiteralPath $Script:DeniedDirectory -AclObject $Acl
  $Script:DeniedDirectory = $null
  $Script:DeniedDirectorySddl = $null
}

function Invoke-Doctor(
  [string] $VelocityHome,
  [string] $CandidateJar,
  [string] $JavaExecutable,
  [string] $PaperGlobalConfig,
  [string] $PaperServerProperties,
  [bool] $RequireBackend = $true,
  [string] $VelocityJar = "",
  [string] $ServiceIdentity = $CurrentServiceIdentity
) {
  $Arguments = @(
    "-NoProfile",
    "-ExecutionPolicy", "Bypass",
    "-File", $Doctor,
    "-VelocityHome", $VelocityHome,
    "-CandidateJar", $CandidateJar,
    "-ServiceIdentity", $ServiceIdentity,
    "-JavaExecutable", $JavaExecutable,
    "-PaperGlobalConfig", $PaperGlobalConfig,
    "-PaperServerProperties", $PaperServerProperties
  )
  if ($RequireBackend) {
    $Arguments += "-RequireBackend"
  }
  if (-not [string]::IsNullOrWhiteSpace($VelocityJar)) {
    $Arguments += @("-VelocityJar", $VelocityJar)
  }
  $PreviousErrorAction = $ErrorActionPreference
  try {
    $ErrorActionPreference = "Continue"
    $Output = & $PowerShell @Arguments 2>&1
    $ExitCode = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $PreviousErrorAction
  }
  return [pscustomobject]@{
    ExitCode = $ExitCode
    Text = $Output -join "`n"
  }
}

function Assert-Check(
  [string] $Label,
  [string] $Text,
  [string] $Name,
  [string] $Status
) {
  $Pattern = "(?m)^CHECK name=$([regex]::Escape($Name)) status=$Status(?: detail=.*)?$"
  if ($Text -notmatch $Pattern) {
    throw "$Label did not report $Name as $Status`n$Text"
  }
}

if (-not (Test-Path -LiteralPath $Doctor -PathType Leaf)) {
  throw "Uworld environment doctor is missing: $Doctor"
}
if (-not (Test-Path -LiteralPath $RuntimeVelocityJar -PathType Leaf)) {
  throw "Velocity build 606 fixture is missing: $RuntimeVelocityJar"
}

try {
  [System.IO.Directory]::CreateDirectory($TempRoot) | Out-Null
  $FakeJava = Join-Path $TempRoot "java-21.cmd"
  Write-FakeJava $FakeJava $RealJava

  $Listener = [System.Net.Sockets.TcpListener]::new(
    [System.Net.IPAddress]::Loopback,
    0
  )
  $Listener.Start()
  try {
    $OpenPort = ([System.Net.IPEndPoint] $Listener.LocalEndpoint).Port
    $PassRoot = Join-Path $TempRoot "pass"
    $PassHome = Join-Path $PassRoot "velocity"
    $PassCandidate = Join-Path $PassRoot "candidate\starx-velocity.jar"
    $PassVelocityJar = Join-Path $PassHome "velocity.jar"
    $PassPlugin = Join-Path $PassHome "plugins\starx-velocity.jar"
    $PassSecret = "fixture-pass-secret-9F3A7D"
    $PassDatabaseParent = Join-Path $PassHome "plugins\starx"
    $PassPaperGlobal = Join-Path $PassRoot "paper\paper-global.yml"
    $PassPaperProperties = Join-Path $PassRoot "paper\server.properties"

    Write-TestJar $PassCandidate "candidate-pass"
    [System.IO.Directory]::CreateDirectory($PassHome) | Out-Null
    Copy-Item -LiteralPath $RuntimeVelocityJar -Destination $PassVelocityJar
    [System.IO.Directory]::CreateDirectory(
      [System.IO.Path]::GetDirectoryName($PassPlugin)
    ) | Out-Null
    Copy-Item -LiteralPath $PassCandidate -Destination $PassPlugin
    Write-VelocityConfig `
      (Join-Path $PassHome "velocity.toml") `
      $OpenPort `
      "forwarding.secret"
    Write-AsciiFile (Join-Path $PassHome "forwarding.secret") $PassSecret
    Write-StarxConfig `
      (Join-Path $PassHome "plugins\starx\config.yml") `
      "" `
      $true
    Write-PaperConfig `
      $PassPaperGlobal `
      $PassPaperProperties `
      $PassSecret `
      $true `
      $false `
      $OpenPort

    $Pass = Invoke-Doctor `
      $PassHome `
      $PassCandidate `
      $FakeJava `
      $PassPaperGlobal `
      $PassPaperProperties
  } finally {
    $Listener.Stop()
  }

  if ($Pass.ExitCode -ne 0) {
    throw "Passing environment fixture exited $($Pass.ExitCode)`n$($Pass.Text)"
  }

  $SplitRoot = Join-Path $TempRoot "split-config"
  $SplitHome = Join-Path $SplitRoot "velocity"
  $SplitConfig = Join-Path $SplitHome "plugins\starx\config.yml"
  $SplitConfigDirectory = Join-Path $SplitHome "plugins\starx\config"
  Copy-Item -LiteralPath $PassHome -Destination $SplitHome -Recurse
  Write-AsciiFile $SplitConfig @"
schema-version: 5
config-files:
  directory: config
  files:
    - core.yml
    - auth.yml
    - network.yml
    - modules.yml
    - uworld.yml
"@
  Write-AsciiFile (Join-Path $SplitConfigDirectory "core.yml") @"
database:
  type: "sqlite"
  database: "plugins/starx/data.db"
"@
  Write-AsciiFile (Join-Path $SplitConfigDirectory "modules.yml") @"
modules:
  starx.uworld:
    enabled: true
"@
  Write-AsciiFile (Join-Path $SplitConfigDirectory "uworld.yml") @"
uworld:
  enabled: true
  auth:
    target-server: "lobby"
"@
  Write-AsciiFile (Join-Path $SplitConfigDirectory "auth.yml") "auth:`r`n"
  Write-AsciiFile (Join-Path $SplitConfigDirectory "network.yml") "website-sync:`r`n"
  $Split = Invoke-Doctor `
    $SplitHome `
    $PassCandidate `
    $FakeJava `
    $PassPaperGlobal `
    $PassPaperProperties `
    $false
  if ($Split.ExitCode -ne 0) {
    throw "Split configuration fixture exited $($Split.ExitCode)`n$($Split.Text)"
  }
  Assert-Check "Split configuration fixture" $Split.Text "uworld_config" "PASS"

  $ModeRoot = Join-Path $TempRoot "forwarding-online-mode-mismatch"
  $ModeHome = Join-Path $ModeRoot "velocity"
  $ModePaperGlobal = Join-Path $ModeRoot "paper\paper-global.yml"
  $ModePaperProperties = Join-Path $ModeRoot "paper\server.properties"
  Copy-Item -LiteralPath $PassHome -Destination $ModeHome -Recurse
  Write-PaperConfig `
    $ModePaperGlobal `
    $ModePaperProperties `
    $PassSecret `
    $true `
    $false `
    $OpenPort `
    "127.0.0.1" `
    $false
  $ModeMismatch = Invoke-Doctor `
    $ModeHome `
    $PassCandidate `
    $FakeJava `
    $ModePaperGlobal `
    $ModePaperProperties `
    $false
  if ($ModeMismatch.ExitCode -ne 1) {
    throw "Forwarding online-mode mismatch fixture exited $($ModeMismatch.ExitCode), expected 1`n$($ModeMismatch.Text)"
  }
  Assert-Check `
    "Forwarding online-mode mismatch fixture" `
    $ModeMismatch.Text `
    "forwarding_online_mode_match" `
    "FAIL"
  if ($ModeMismatch.Text.Contains($PassSecret)) {
    throw "Forwarding online-mode mismatch fixture leaked its forwarding secret"
  }

  foreach ($DuplicateProperty in @(
    [pscustomobject]@{ Key = "online-mode"; Value = "true" },
    [pscustomobject]@{ Key = "server-port"; Value = "25566" },
    [pscustomobject]@{ Key = "server-ip"; Value = "192.168.50.10" }
  )) {
    $DuplicateRoot = Join-Path $TempRoot "duplicate-paper-property-$($DuplicateProperty.Key)"
    $DuplicateHome = Join-Path $DuplicateRoot "velocity"
    $DuplicatePaperGlobal = Join-Path $DuplicateRoot "paper\paper-global.yml"
    $DuplicatePaperProperties = Join-Path $DuplicateRoot "paper\server.properties"
    Copy-Item -LiteralPath $PassHome -Destination $DuplicateHome -Recurse
    [System.IO.Directory]::CreateDirectory(
      [System.IO.Path]::GetDirectoryName($DuplicatePaperGlobal)
    ) | Out-Null
    Copy-Item -LiteralPath $PassPaperGlobal -Destination $DuplicatePaperGlobal
    Copy-Item -LiteralPath $PassPaperProperties -Destination $DuplicatePaperProperties
    [System.IO.File]::AppendAllText(
      $DuplicatePaperProperties,
      "`r`n$($DuplicateProperty.Key)=$($DuplicateProperty.Value)`r`n",
      [System.Text.Encoding]::ASCII
    )
    $DuplicateResult = Invoke-Doctor `
      $DuplicateHome `
      $PassCandidate `
      $FakeJava `
      $DuplicatePaperGlobal `
      $DuplicatePaperProperties `
      $false
    if ($DuplicateResult.ExitCode -ne 1) {
      throw "Duplicate Paper property '$($DuplicateProperty.Key)' fixture exited $($DuplicateResult.ExitCode), expected 1`n$($DuplicateResult.Text)"
    }
    Assert-Check `
      "Duplicate Paper property '$($DuplicateProperty.Key)' fixture" `
      $DuplicateResult.Text `
      "paper_server_properties_syntax" `
      "FAIL"
    if ($DuplicateResult.Text -notmatch (
        "(?m)^CHECK name=paper_server_properties_syntax status=FAIL detail=.*duplicate_keys=" +
        [regex]::Escape($DuplicateProperty.Key) + "(?:,|\s|$)"
      )) {
      throw "Duplicate Paper property '$($DuplicateProperty.Key)' fixture did not identify the duplicate key`n$($DuplicateResult.Text)"
    }
    if ($DuplicateResult.Text.Contains($PassSecret)) {
      throw "Duplicate Paper property '$($DuplicateProperty.Key)' fixture leaked its forwarding secret"
    }
  }

  $PortRoot = Join-Path $TempRoot "paper-target-port-mismatch"
  $PortHome = Join-Path $PortRoot "velocity"
  $PortPaperGlobal = Join-Path $PortRoot "paper\paper-global.yml"
  $PortPaperProperties = Join-Path $PortRoot "paper\server.properties"
  $WrongPaperPort = if ($OpenPort -eq 25565) { 25566 } else { 25565 }
  Copy-Item -LiteralPath $PassHome -Destination $PortHome -Recurse
  Write-PaperConfig `
    $PortPaperGlobal `
    $PortPaperProperties `
    $PassSecret `
    $true `
    $false `
    $WrongPaperPort
  $PortMismatch = Invoke-Doctor `
    $PortHome `
    $PassCandidate `
    $FakeJava `
    $PortPaperGlobal `
    $PortPaperProperties `
    $false
  if ($PortMismatch.ExitCode -ne 1) {
    throw "Paper target-port mismatch fixture exited $($PortMismatch.ExitCode), expected 1`n$($PortMismatch.Text)"
  }
  Assert-Check `
    "Paper target-port mismatch fixture" `
    $PortMismatch.Text `
    "paper_target_port" `
    "FAIL"
  if ($PortMismatch.Text.Contains($PassSecret)) {
    throw "Paper target-port mismatch fixture leaked its forwarding secret"
  }

  $BindingRoot = Join-Path $TempRoot "paper-server-binding"
  $BindingHome = Join-Path $BindingRoot "velocity"
  $BindingPaperGlobal = Join-Path $BindingRoot "paper\paper-global.yml"
  $BindingPaperProperties = Join-Path $BindingRoot "paper\server.properties"
  Copy-Item -LiteralPath $PassHome -Destination $BindingHome -Recurse
  foreach ($BadAddress in @("", "0.0.0.0", "::", "203.0.113.10")) {
    Write-PaperConfig `
      $BindingPaperGlobal `
      $BindingPaperProperties `
      $PassSecret `
      $true `
      $false `
      $OpenPort `
      $BadAddress
    $BadBinding = Invoke-Doctor `
      $BindingHome `
      $PassCandidate `
      $FakeJava `
      $BindingPaperGlobal `
      $BindingPaperProperties `
      $false
    if ($BadBinding.ExitCode -ne 1) {
      throw "Paper server binding '$BadAddress' fixture exited $($BadBinding.ExitCode), expected 1`n$($BadBinding.Text)"
    }
    Assert-Check `
      "Paper server binding '$BadAddress' fixture" `
      $BadBinding.Text `
      "paper_server_binding" `
      "FAIL"
    Assert-Check `
      "Paper server binding '$BadAddress' fixture" `
      $BadBinding.Text `
      "paper_target_host" `
      "FAIL"
    if ($BadBinding.Text.Contains($PassSecret)) {
      throw "Paper server binding '$BadAddress' fixture leaked its forwarding secret"
    }
  }

  Write-PaperConfig `
    $BindingPaperGlobal `
    $BindingPaperProperties `
    $PassSecret `
    $true `
    $false `
    $OpenPort `
    "127.0.0.1"
  Write-VelocityConfig `
    (Join-Path $BindingHome "velocity.toml") `
    $OpenPort `
    "forwarding.secret" `
    $true `
    "localhost"
  $MultiAddressTarget = Invoke-Doctor `
    $BindingHome `
    $PassCandidate `
    $FakeJava `
    $BindingPaperGlobal `
    $BindingPaperProperties `
    $false
  if ($MultiAddressTarget.ExitCode -ne 1) {
    throw "Multi-address target fixture exited $($MultiAddressTarget.ExitCode), expected 1`n$($MultiAddressTarget.Text)"
  }
  Assert-Check `
    "Multi-address target fixture" `
    $MultiAddressTarget.Text `
    "paper_server_binding" `
    "PASS"
  Assert-Check `
    "Multi-address target fixture" `
    $MultiAddressTarget.Text `
    "paper_target_host" `
    "FAIL"
  if ($MultiAddressTarget.Text.Contains($PassSecret)) {
    throw "Multi-address target fixture leaked its forwarding secret"
  }

  Write-PaperConfig `
    $BindingPaperGlobal `
    $BindingPaperProperties `
    $PassSecret `
    $true `
    $false `
    $OpenPort `
    "192.168.50.10"
  $PrivateBindingMismatch = Invoke-Doctor `
    $BindingHome `
    $PassCandidate `
    $FakeJava `
    $BindingPaperGlobal `
    $BindingPaperProperties `
    $false
  if ($PrivateBindingMismatch.ExitCode -ne 1) {
    throw "Paper mismatched private binding fixture exited $($PrivateBindingMismatch.ExitCode), expected 1`n$($PrivateBindingMismatch.Text)"
  }
  Assert-Check `
    "Paper mismatched private binding fixture" `
    $PrivateBindingMismatch.Text `
    "paper_server_binding" `
    "PASS"
  Assert-Check `
    "Paper mismatched private binding fixture" `
    $PrivateBindingMismatch.Text `
    "paper_target_host" `
    "FAIL"
  if ($PrivateBindingMismatch.Text.Contains($PassSecret)) {
    throw "Paper mismatched private binding fixture leaked its forwarding secret"
  }

  Write-VelocityConfig `
    (Join-Path $BindingHome "velocity.toml") `
    $OpenPort `
    "forwarding.secret" `
    $true `
    "192.168.50.10"
  $PrivateBindingMatch = Invoke-Doctor `
    $BindingHome `
    $PassCandidate `
    $FakeJava `
    $BindingPaperGlobal `
    $BindingPaperProperties `
    $false
  if ($PrivateBindingMatch.ExitCode -ne 0) {
    throw "Paper matching private binding fixture exited $($PrivateBindingMatch.ExitCode)`n$($PrivateBindingMatch.Text)"
  }
  Assert-Check `
    "Paper matching private binding fixture" `
    $PrivateBindingMatch.Text `
    "paper_server_binding" `
    "PASS"
  Assert-Check `
    "Paper matching private binding fixture" `
    $PrivateBindingMatch.Text `
    "paper_target_host" `
    "PASS"
  if ($PrivateBindingMatch.Text.Contains($PassSecret)) {
    throw "Paper matching private binding fixture leaked its forwarding secret"
  }

  foreach ($CheckName in @(
    "java_21",
    "velocity_build_606",
    "plugin_jar_inspection",
    "starx_jar_count",
    "external_limboapi",
    "candidate_hash",
    "starx_config_syntax",
    "uworld_config",
    "velocity_config_syntax",
    "target_registered",
    "backend_reachable",
    "velocity_modern_forwarding",
    "paper_config_syntax",
    "paper_velocity_forwarding",
    "forwarding_secret_match",
    "forwarding_online_mode_match",
    "paper_server_properties_syntax",
    "paper_online_mode",
    "paper_target_port",
    "paper_target_host",
    "paper_server_binding",
    "sqlite_parent_writable"
  )) {
    Assert-Check "Passing fixture" $Pass.Text $CheckName "PASS"
  }
  if ($Pass.Text -notmatch '(?m)^UWORLD_ENVIRONMENT=PASS$') {
    throw "Passing fixture omitted its final PASS marker`n$($Pass.Text)"
  }
  if ($Pass.Text.Contains($PassSecret)) {
    throw "Passing fixture leaked its forwarding secret"
  }
  $PassLines = @($Pass.Text -split "\r?\n" | Where-Object {
    -not [string]::IsNullOrWhiteSpace($_)
  })
  if ($PassLines[-1] -cne "UWORLD_ENVIRONMENT=PASS") {
    throw "PASS marker must be the final nonempty line: $($PassLines[-1])"
  }
  $PassProbeFiles = @(Get-ChildItem -LiteralPath $PassDatabaseParent -Force `
      -Filter ".uworld-write-probe-*")
  if ($PassProbeFiles.Count -ne 0) {
    throw "Writable-parent probe left files behind: $($PassProbeFiles.FullName -join ', ')"
  }

  $WrongIdentity = Invoke-Doctor `
    $PassHome `
    $PassCandidate `
    $FakeJava `
    $PassPaperGlobal `
    $PassPaperProperties `
    $false `
    "" `
    "NT SERVICE\UworldMissingFixture"
  if ($WrongIdentity.ExitCode -ne 1) {
    throw "Wrong service identity fixture exited $($WrongIdentity.ExitCode), expected 1`n$($WrongIdentity.Text)"
  }
  Assert-Check `
    "Wrong service identity fixture" `
    $WrongIdentity.Text `
    "sqlite_parent_writable" `
    "FAIL"

  $ExternalVelocityJar = Join-Path $TempRoot "outside\velocity-606.jar"
  Write-TestJar $ExternalVelocityJar "external-velocity-build-606" "Velocity"
  $ExternalVelocity = Invoke-Doctor `
    $PassHome `
    $PassCandidate `
    $FakeJava `
    $PassPaperGlobal `
    $PassPaperProperties `
    $false `
    $ExternalVelocityJar
  if ($ExternalVelocity.ExitCode -ne 1) {
    throw "External Velocity JAR fixture exited $($ExternalVelocity.ExitCode), expected 1`n$($ExternalVelocity.Text)"
  }
  Assert-Check `
    "External Velocity JAR fixture" `
    $ExternalVelocity.Text `
    "velocity_build_606" `
    "FAIL"

  $LinkedRoot = Join-Path $TempRoot "linked-velocity"
  $LinkedHome = Join-Path $LinkedRoot "velocity"
  Copy-Item -LiteralPath $PassHome -Destination $LinkedHome -Recurse
  $LinkedVelocityJar = Join-Path $LinkedHome "velocity.jar"
  Remove-Item -LiteralPath $LinkedVelocityJar -Force
  New-FileSymbolicLink $LinkedVelocityJar $ExternalVelocityJar
  $LinkedVelocity = Invoke-Doctor `
    $LinkedHome `
    $PassCandidate `
    $FakeJava `
    $PassPaperGlobal `
    $PassPaperProperties `
    $false
  if ($LinkedVelocity.ExitCode -ne 1) {
    throw "Linked Velocity JAR fixture exited $($LinkedVelocity.ExitCode), expected 1`n$($LinkedVelocity.Text)"
  }
  Assert-Check `
    "Linked Velocity JAR fixture" `
    $LinkedVelocity.Text `
    "velocity_build_606" `
    "FAIL"

  $RenamedRoot = Join-Path $TempRoot "renamed-starx"
  $RenamedHome = Join-Path $RenamedRoot "velocity"
  Copy-Item -LiteralPath $PassHome -Destination $RenamedHome -Recurse
  Move-Item `
    -LiteralPath (Join-Path $RenamedHome "plugins\starx-velocity.jar") `
    -Destination (Join-Path $RenamedHome "plugins\runtime.jar")
  $Renamed = Invoke-Doctor `
    $RenamedHome `
    $PassCandidate `
    $FakeJava `
    $PassPaperGlobal `
    $PassPaperProperties `
    $false
  if ($Renamed.ExitCode -ne 0) {
    throw "Descriptor-identified StarX fixture exited $($Renamed.ExitCode)`n$($Renamed.Text)"
  }
  Assert-Check "Descriptor-identified StarX fixture" $Renamed.Text "starx_jar_count" "PASS"
  Assert-Check "Descriptor-identified StarX fixture" $Renamed.Text "candidate_hash" "PASS"
  if ($Renamed.Text.Contains($PassSecret)) {
    throw "Descriptor-identified StarX fixture leaked its forwarding secret"
  }

  $UrlRoot = Join-Path $TempRoot "database-url"
  $UrlHome = Join-Path $UrlRoot "velocity"
  $UrlDatabaseParent = Join-Path $UrlHome "plugins\starx\url-db"
  $SqliteUrl = "jdbc:sqlite:plugins/starx/url-db/uworld.db"
  Copy-Item -LiteralPath $PassHome -Destination $UrlHome -Recurse
  [System.IO.Directory]::CreateDirectory($UrlDatabaseParent) | Out-Null
  Write-StarxConfig `
    (Join-Path $UrlHome "plugins\starx\config.yml") `
    "plugins/starx/missing-fallback/uworld.db" `
    $true `
    $SqliteUrl
  $UrlPass = Invoke-Doctor `
    $UrlHome `
    $PassCandidate `
    $FakeJava `
    $PassPaperGlobal `
    $PassPaperProperties `
    $false
  if ($UrlPass.ExitCode -ne 0) {
    throw "SQLite URL-priority fixture exited $($UrlPass.ExitCode)`n$($UrlPass.Text)"
  }
  Assert-Check "SQLite URL-priority fixture" $UrlPass.Text "sqlite_parent_writable" "PASS"
  if ($UrlPass.Text.Contains($SqliteUrl)) {
    throw "SQLite URL-priority fixture printed a database URL"
  }

  $NonSqliteRoot = Join-Path $TempRoot "database-url-non-sqlite"
  $NonSqliteHome = Join-Path $NonSqliteRoot "velocity"
  $NonSqliteFallback = Join-Path $NonSqliteHome "plugins\starx\fallback"
  $NonSqliteUrl = "jdbc:mysql://db.example.invalid/uworld"
  Copy-Item -LiteralPath $PassHome -Destination $NonSqliteHome -Recurse
  [System.IO.Directory]::CreateDirectory($NonSqliteFallback) | Out-Null
  Write-StarxConfig `
    (Join-Path $NonSqliteHome "plugins\starx\config.yml") `
    "plugins/starx/fallback/uworld.db" `
    $true `
    $NonSqliteUrl
  $NonSqlite = Invoke-Doctor `
    $NonSqliteHome `
    $PassCandidate `
    $FakeJava `
    $PassPaperGlobal `
    $PassPaperProperties `
    $false
  if ($NonSqlite.ExitCode -ne 1) {
    throw "Non-SQLite URL fixture exited $($NonSqlite.ExitCode), expected 1`n$($NonSqlite.Text)"
  }
  Assert-Check "Non-SQLite URL fixture" $NonSqlite.Text "sqlite_parent_writable" "FAIL"
  if ($NonSqlite.Text.Contains($NonSqliteUrl)) {
    throw "Non-SQLite URL fixture printed a database URL"
  }

  $MultiDocumentRoot = Join-Path $TempRoot "multi-document-yaml"
  $MultiDocumentHome = Join-Path $MultiDocumentRoot "velocity"
  $MultiDocumentPaperGlobal = Join-Path $MultiDocumentRoot "paper\paper-global.yml"
  $MultiDocumentPaperProperties = Join-Path $MultiDocumentRoot "paper\server.properties"
  Copy-Item -LiteralPath $PassHome -Destination $MultiDocumentHome -Recurse
  [System.IO.Directory]::CreateDirectory(
    [System.IO.Path]::GetDirectoryName($MultiDocumentPaperGlobal)
  ) | Out-Null
  Copy-Item -LiteralPath $PassPaperGlobal -Destination $MultiDocumentPaperGlobal
  Copy-Item -LiteralPath $PassPaperProperties -Destination $MultiDocumentPaperProperties
  foreach ($YamlPath in @(
    (Join-Path $MultiDocumentHome "plugins\starx\config.yml"),
    $MultiDocumentPaperGlobal
  )) {
    [System.IO.File]::AppendAllText(
      $YamlPath,
      "`r`n---`r`nsecond-document: true`r`n",
      [System.Text.Encoding]::ASCII
    )
  }
  $MultiDocument = Invoke-Doctor `
    $MultiDocumentHome `
    $PassCandidate `
    $FakeJava `
    $MultiDocumentPaperGlobal `
    $MultiDocumentPaperProperties `
    $false
  if ($MultiDocument.ExitCode -ne 1) {
    throw "Multi-document YAML fixture exited $($MultiDocument.ExitCode), expected 1`n$($MultiDocument.Text)"
  }
  Assert-Check `
    "Multi-document YAML fixture" `
    $MultiDocument.Text `
    "starx_config_syntax" `
    "FAIL"
  Assert-Check `
    "Multi-document YAML fixture" `
    $MultiDocument.Text `
    "paper_config_syntax" `
    "FAIL"
  if ($MultiDocument.Text.Contains($PassSecret)) {
    throw "Multi-document YAML fixture leaked its forwarding secret"
  }

  $SyntaxRoot = Join-Path $TempRoot "syntax-fail"
  $SyntaxHome = Join-Path $SyntaxRoot "velocity"
  $SyntaxPaperGlobal = Join-Path $SyntaxRoot "paper\paper-global.yml"
  $SyntaxPaperProperties = Join-Path $SyntaxRoot "paper\server.properties"
  Copy-Item -LiteralPath $PassHome -Destination $SyntaxHome -Recurse
  [System.IO.File]::AppendAllText(
    (Join-Path $SyntaxHome "plugins\starx\config.yml"),
    "`r`nthis is not yaml`r`n",
    [System.Text.Encoding]::ASCII
  )
  Write-AsciiFile (Join-Path $SyntaxHome "velocity.toml") @"
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"

[servers]
lobby = "127.0.0.1:$OpenPort"

[servers]
lobby = "127.0.0.1:$OpenPort"
"@
  Write-AsciiFile $SyntaxPaperGlobal @"
proxies:
  velocity:
    enabled: true
    enabled: true
    online-mode: true
    secret: "$PassSecret"
"@
  Copy-Item -LiteralPath $PassPaperProperties -Destination $SyntaxPaperProperties

  $SyntaxFail = Invoke-Doctor `
    $SyntaxHome `
    $PassCandidate `
    $FakeJava `
    $SyntaxPaperGlobal `
    $SyntaxPaperProperties `
    $false
  if ($SyntaxFail.ExitCode -ne 1) {
    throw "Malformed configuration fixture exited $($SyntaxFail.ExitCode), expected 1`n$($SyntaxFail.Text)"
  }
  foreach ($CheckName in @(
    "starx_config_syntax",
    "velocity_config_syntax",
    "paper_config_syntax"
  )) {
    Assert-Check "Malformed configuration fixture" $SyntaxFail.Text $CheckName "FAIL"
  }
  if ($SyntaxFail.Text.Contains($PassSecret)) {
    throw "Malformed configuration fixture leaked its forwarding secret"
  }

  $StructuredSyntaxRoot = Join-Path $TempRoot "structured-syntax-fail"
  $StructuredSyntaxHome = Join-Path $StructuredSyntaxRoot "velocity"
  $StructuredPaperGlobal = Join-Path $StructuredSyntaxRoot "paper\paper-global.yml"
  $StructuredPaperProperties = Join-Path $StructuredSyntaxRoot "paper\server.properties"
  Copy-Item -LiteralPath $PassHome -Destination $StructuredSyntaxHome -Recurse
  [System.IO.File]::AppendAllText(
    (Join-Path $StructuredSyntaxHome "plugins\starx\config.yml"),
    "`r`ninvalid-escape: `"\q`"`r`n",
    [System.Text.Encoding]::ASCII
  )
  Write-AsciiFile (Join-Path $StructuredSyntaxHome "velocity.toml") @"
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"
invalid-array = [,,,]

[servers]
lobby = "127.0.0.1:$OpenPort"
"@
  Write-AsciiFile $StructuredPaperGlobal @"
proxies:
  velocity:
    enabled: true
    online-mode: true
    secret: "$PassSecret"
invalid-escape: "\q"
"@
  Copy-Item -LiteralPath $PassPaperProperties -Destination $StructuredPaperProperties

  $StructuredSyntaxFail = Invoke-Doctor `
    $StructuredSyntaxHome `
    $PassCandidate `
    $FakeJava `
    $StructuredPaperGlobal `
    $StructuredPaperProperties `
    $false
  if ($StructuredSyntaxFail.ExitCode -ne 1) {
    throw "Structured malformed fixture exited $($StructuredSyntaxFail.ExitCode), expected 1`n$($StructuredSyntaxFail.Text)"
  }
  foreach ($CheckName in @(
    "starx_config_syntax",
    "velocity_config_syntax",
    "paper_config_syntax"
  )) {
    Assert-Check "Structured malformed fixture" $StructuredSyntaxFail.Text $CheckName "FAIL"
  }
  if ($StructuredSyntaxFail.Text.Contains($PassSecret)) {
    throw "Structured malformed fixture leaked its forwarding secret"
  }

  $PortProbe = [System.Net.Sockets.TcpListener]::new(
    [System.Net.IPAddress]::Loopback,
    0
  )
  $PortProbe.Start()
  $ClosedPort = ([System.Net.IPEndPoint] $PortProbe.LocalEndpoint).Port
  $PortProbe.Stop()

  $FailRoot = Join-Path $TempRoot "fail"
  $FailHome = Join-Path $FailRoot "velocity"
  $FailCandidate = Join-Path $FailRoot "candidate\starx-velocity.jar"
  $FailVelocityJar = Join-Path $FailHome "velocity.jar"
  $FailPlugin = Join-Path $FailHome "plugins\starx-velocity.jar"
  $VelocitySecret = "velocity-secret-MUST-NOT-LEAK-17C2"
  $PaperSecret = "paper-secret-MUST-NOT-LEAK-8B4D"
  $FailDatabaseParent = Join-Path $FailHome "plugins\starx\read-only"
  $FailPaperGlobal = Join-Path $FailRoot "paper\paper-global.yml"
  $FailPaperProperties = Join-Path $FailRoot "paper\server.properties"

  Write-TestJar $FailCandidate "candidate-current"
  [System.IO.Directory]::CreateDirectory($FailHome) | Out-Null
  Copy-Item -LiteralPath $RuntimeVelocityJar -Destination $FailVelocityJar
  Write-TestJar $FailPlugin "deployed-stale"
  Write-TestJar `
    (Join-Path $FailHome "plugins\backup\compatibility.jar") `
    "external-limboapi" `
    "LimboClass"
  Write-VelocityConfig `
    (Join-Path $FailHome "velocity.toml") `
    $ClosedPort `
    "forwarding.secret"
  Write-AsciiFile (Join-Path $FailHome "forwarding.secret") $VelocitySecret
  Write-StarxConfig `
    (Join-Path $FailHome "plugins\starx\config.yml") `
    "plugins/starx/read-only/uworld.db" `
    $false
  [System.IO.Directory]::CreateDirectory($FailDatabaseParent) | Out-Null
  Write-PaperConfig `
    $FailPaperGlobal `
    $FailPaperProperties `
    $PaperSecret `
    $false `
    $true `
    $ClosedPort
  Deny-DirectoryWrites $FailDatabaseParent

  $Fail = Invoke-Doctor `
    $FailHome `
    $FailCandidate `
    $FakeJava `
    $FailPaperGlobal `
    $FailPaperProperties

  if ($Fail.ExitCode -ne 1) {
    throw "Failing environment fixture exited $($Fail.ExitCode), expected 1`n$($Fail.Text)"
  }
  foreach ($CheckName in @(
    "external_limboapi",
    "candidate_hash",
    "uworld_config",
    "backend_reachable",
    "paper_velocity_forwarding",
    "forwarding_secret_match",
    "paper_online_mode",
    "sqlite_parent_writable"
  )) {
    Assert-Check "Failing fixture" $Fail.Text $CheckName "FAIL"
  }
  if ($Fail.Text -notmatch '(?m)^UWORLD_ENVIRONMENT=FAIL$') {
    throw "Failing fixture omitted its final FAIL marker`n$($Fail.Text)"
  }
  foreach ($Secret in @($VelocitySecret, $PaperSecret)) {
    if ($Fail.Text.Contains($Secret)) {
      throw "Failing fixture leaked a forwarding secret"
    }
  }
  $FailProbeFiles = @(Get-ChildItem -LiteralPath $FailDatabaseParent -Force)
  if ($FailProbeFiles.Count -ne 0) {
    throw "Read-only-parent probe left files behind: $($FailProbeFiles.FullName -join ', ')"
  }
} finally {
  Restore-DirectoryWrites
  $ResolvedTempRoot = [System.IO.Path]::GetFullPath($TempRoot)
  if ($ResolvedTempRoot.StartsWith(
      $TempPrefix,
      [System.StringComparison]::OrdinalIgnoreCase
    ) -and [System.IO.Directory]::Exists($ResolvedTempRoot)) {
    [System.IO.Directory]::Delete($ResolvedTempRoot, $true)
  }
}

Write-Host "PASS: Uworld environment doctor is release-strict and secret-safe"
