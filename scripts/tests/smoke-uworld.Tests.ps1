$ErrorActionPreference = "Stop"

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$Root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$Smoke = Join-Path $Root "scripts\tests\smoke-uworld.ps1"
$FixtureRoot = Join-Path $Root "velocity-test\fixtures\uworld"
$VelocityFixture = Join-Path $FixtureRoot "velocity.toml"
$DefaultFixture = Join-Path $FixtureRoot "config-default.yml"
$DiagnosticsFixture = Join-Path $FixtureRoot "config-diagnostics.yml"

foreach ($RequiredFile in @($Smoke, $VelocityFixture, $DefaultFixture, $DiagnosticsFixture)) {
  if (-not (Test-Path -LiteralPath $RequiredFile -PathType Leaf)) {
    throw "Required Uworld smoke asset is missing: $RequiredFile"
  }
}

function Assert-Match([string] $Label, [string] $Text, [string] $Pattern) {
  if ($Text -notmatch $Pattern) {
    throw "$Label did not match required pattern: $Pattern"
  }
}

function Assert-PortReleased([int] $Port) {
  $Properties = [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties()
  $Listening = $Properties.GetActiveTcpListeners() |
    Where-Object { $_.Port -eq $Port }
  if ($null -ne $Listening) {
    throw "Port $Port remained occupied after the smoke process exited"
  }
}

function Assert-SmokePathBoundary([string] $ScriptPath) {
  $Tokens = $null
  $ParseErrors = $null
  $ScriptAst = [System.Management.Automation.Language.Parser]::ParseFile(
    $ScriptPath,
    [ref] $Tokens,
    [ref] $ParseErrors
  )
  if (@($ParseErrors).Count -ne 0) {
    throw "Unable to parse Uworld smoke script: $($ParseErrors -join '; ')"
  }

  $BoundaryFunction = $ScriptAst.Find(
    {
      param($Node)
      $Node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $Node.Name -ceq "Test-PathIsStrictDescendant"
    },
    $true
  )
  if ($null -eq $BoundaryFunction) {
    throw "Uworld smoke script must define Test-PathIsStrictDescendant"
  }
  $BoundarySource = $BoundaryFunction.Extent.Text
  foreach ($RequiredApi in @(
    "[System.IO.Path]::GetFullPath",
    "[System.IO.Path]::DirectorySeparatorChar",
    "[System.IO.Path]::AltDirectorySeparatorChar"
  )) {
    if ($BoundarySource.IndexOf($RequiredApi, [System.StringComparison]::Ordinal) -lt 0) {
      throw "Uworld smoke path boundary must use $RequiredApi"
    }
  }
  $ScriptText = [System.IO.File]::ReadAllText($ScriptPath)
  $JavaHomePattern = '(?s)foreach\s*\(\$Name\s+in\s+@\(' +
    '"java\.exe",\s*"java"\)\).*?' +
    'Join-Path\s+\$env:JAVA_HOME\s+"bin\\\$Name"'
  if ($ScriptText -notmatch $JavaHomePattern) {
    throw "Uworld smoke JAVA_HOME discovery must support java.exe and java"
  }
  . ([scriptblock]::Create($BoundaryFunction.Extent.Text))

  $DirectorySeparator = [System.IO.Path]::DirectorySeparatorChar
  $AlternateSeparator = [System.IO.Path]::AltDirectorySeparatorChar
  $Separators = [char[]] @($DirectorySeparator, $AlternateSeparator)
  $BoundaryRoot = [System.IO.Path]::GetFullPath(
    (Join-Path ([System.IO.Path]::GetTempPath()) (
      "starx-uworld-boundary-" + [guid]::NewGuid().ToString("N")
    ))
  )
  $RootWithAlternateSeparator = $BoundaryRoot.TrimEnd($Separators) + $AlternateSeparator
  $NormalizedChild = $RootWithAlternateSeparator +
    "nested${AlternateSeparator}..${AlternateSeparator}nested${AlternateSeparator}run"
  if (-not (Test-PathIsStrictDescendant $NormalizedChild $RootWithAlternateSeparator)) {
    throw "Normalized smoke child path was rejected: $NormalizedChild"
  }

  $PrefixCollision = $BoundaryRoot + "-collision${DirectorySeparator}run"
  if (Test-PathIsStrictDescendant $PrefixCollision $BoundaryRoot) {
    throw "Smoke boundary accepted a sibling prefix collision: $PrefixCollision"
  }

  $EscapedPath = $RootWithAlternateSeparator +
    "..${AlternateSeparator}outside${AlternateSeparator}run"
  if (Test-PathIsStrictDescendant $EscapedPath $BoundaryRoot) {
    throw "Smoke boundary accepted an escaping path: $EscapedPath"
  }
  if (Test-PathIsStrictDescendant $BoundaryRoot $BoundaryRoot) {
    throw "Smoke boundary accepted the protected root itself: $BoundaryRoot"
  }
}

function Read-YamlScalars([string] $Path) {
  $Scalars = @{}
  $Parents = @{}
  foreach ($Line in [System.IO.File]::ReadAllLines($Path)) {
    if ([string]::IsNullOrWhiteSpace($Line) -or $Line.TrimStart().StartsWith("#")) {
      continue
    }
    $Match = [regex]::Match($Line, '^(?<indent> *)(?<key>[A-Za-z0-9_.-]+):(?<tail>.*)$')
    if (-not $Match.Success) {
      throw "Unsupported YAML line in ${Path}: $Line"
    }
    $Indent = $Match.Groups["indent"].Value.Length
    if ($Indent % 2 -ne 0) {
      throw "YAML indentation must use two spaces in ${Path}: $Line"
    }
    $Depth = [int] ($Indent / 2)
    foreach ($ExistingDepth in @($Parents.Keys)) {
      if ([int] $ExistingDepth -ge $Depth) {
        $Parents.Remove($ExistingDepth)
      }
    }

    $Segments = [System.Collections.Generic.List[string]]::new()
    for ($Level = 0; $Level -lt $Depth; $Level++) {
      if (-not $Parents.ContainsKey($Level)) {
        throw "YAML parent is missing at depth $Level in ${Path}: $Line"
      }
      $Segments.Add($Parents[$Level])
    }
    $Key = $Match.Groups["key"].Value
    $Segments.Add($Key)
    $YamlPath = $Segments -join "."
    $Tail = $Match.Groups["tail"].Value.Trim()
    if ($Tail.Length -eq 0) {
      $Parents[$Depth] = $Key
      continue
    }
    if ($Tail.Length -ge 2 -and $Tail.StartsWith('"') -and $Tail.EndsWith('"')) {
      $Tail = $Tail.Substring(1, $Tail.Length - 2)
    }
    if ($Scalars.ContainsKey($YamlPath)) {
      throw "Duplicate YAML path in ${Path}: $YamlPath"
    }
    $Scalars[$YamlPath] = $Tail
  }
  return $Scalars
}

function Write-TestJar([string] $Path, [string] $Manifest) {
  $Stream = [System.IO.File]::Open(
      $Path,
      [System.IO.FileMode]::Create,
      [System.IO.FileAccess]::ReadWrite,
      [System.IO.FileShare]::None)
  try {
    $Archive = [System.IO.Compression.ZipArchive]::new(
        $Stream,
        [System.IO.Compression.ZipArchiveMode]::Create,
        $true)
    try {
      $Entry = $Archive.CreateEntry("META-INF/MANIFEST.MF")
      $Writer = [System.IO.StreamWriter]::new($Entry.Open(), [System.Text.Encoding]::ASCII)
      try {
        $Writer.Write($Manifest)
      } finally {
        $Writer.Dispose()
      }
    } finally {
      $Archive.Dispose()
    }
  } finally {
    $Stream.Dispose()
  }
}

$VelocityConfig = [System.IO.File]::ReadAllText($VelocityFixture)
Assert-Match "Velocity smoke bind" $VelocityConfig '(?m)^bind\s*=\s*"127\.0\.0\.1:25580"\s*$'
Assert-Match "Velocity lobby server" $VelocityConfig '(?ms)^\[servers\]\s*\r?\nlobby\s*=\s*"127\.0\.0\.1:25566"\s*$'
if ($VelocityConfig -match '(?m)^\s*\[\[server\]\]\s*$') {
  throw "Velocity smoke fixture uses unsupported [[server]] tables"
}
Assert-Match "Velocity empty forced hosts" $VelocityConfig '(?ms)^\[forced-hosts\]\s*$'
if ($VelocityConfig -match '(?m)^\s*try\s*=') {
  throw "Velocity smoke fixture must not configure an arbitrary try fallback"
}

$WorldKeys = @(
  "dimension", "spawn-x", "spawn-y", "spawn-z", "spawn-yaw", "spawn-pitch",
  "game-mode", "loader-type", "file-name", "offset-x", "offset-y", "offset-z",
  "view-distance", "simulation-distance", "platform-radius"
)
foreach ($ConfigPath in @($DefaultFixture, $DiagnosticsFixture)) {
  $ConfigText = [System.IO.File]::ReadAllText($ConfigPath)
  if ($ConfigText -match '(?m)^limbo:\s*$' -or
      $ConfigText -match '(?m)^  starx\.limbo:\s*$') {
    throw "Smoke fixture contains a legacy Limbo root: $ConfigPath"
  }
  Assert-Match "Uworld root in $ConfigPath" $ConfigText '(?m)^uworld:\s*$'
  Assert-Match "Uworld module in $ConfigPath" $ConfigText '(?m)^  starx\.uworld:\s*\r?\n    enabled:\s*true\s*$'
  Assert-Match "Auth module in $ConfigPath" $ConfigText '(?m)^  starx\.auth:\s*\r?\n    enabled:\s*true\s*$'
  foreach ($WorldKey in $WorldKeys) {
    $Count = ([regex]::Matches($ConfigText, "(?m)^      $([regex]::Escape($WorldKey)):\s*")).Count
    if ($Count -ne 1) {
      throw "Expected one auth world key '$WorldKey' in $ConfigPath, actual=$Count"
    }
  }

  $Scalars = Read-YamlScalars $ConfigPath
  $Expected = [ordered]@{
    "http.bind" = "127.0.0.1"
    "http.port" = "8790"
    "modules.starx.uworld.enabled" = "true"
    "modules.starx.auth.enabled" = "true"
    "uworld.enabled" = "true"
    "uworld.transfer-timeout-seconds" = "15"
    "uworld.auth.timeout-seconds" = "300"
    "uworld.auth.target-server" = "lobby"
    "uworld.auth.world.dimension" = "OVERWORLD"
    "uworld.auth.world.spawn-x" = "0.5"
    "uworld.auth.world.spawn-y" = "100.0"
    "uworld.auth.world.spawn-z" = "0.5"
    "uworld.auth.world.spawn-yaw" = "0.0"
    "uworld.auth.world.spawn-pitch" = "0.0"
    "uworld.auth.world.game-mode" = "SURVIVAL"
    "uworld.auth.world.loader-type" = "VOID"
    "uworld.auth.world.file-name" = "auth_world.schem"
    "uworld.auth.world.offset-x" = "0"
    "uworld.auth.world.offset-y" = "0"
    "uworld.auth.world.offset-z" = "0"
    "uworld.auth.world.view-distance" = "4"
    "uworld.auth.world.simulation-distance" = "4"
    "uworld.auth.world.platform-radius" = "5"
    "uworld.diagnostics.timeout-seconds" = "120"
    "uworld.diagnostics.platform-radius" = "5"
  }
  foreach ($Entry in $Expected.GetEnumerator()) {
    if (-not $Scalars.ContainsKey($Entry.Key) -or $Scalars[$Entry.Key] -cne $Entry.Value) {
      throw "Unexpected YAML value in ${ConfigPath}: $($Entry.Key) expected=$($Entry.Value) actual=$($Scalars[$Entry.Key])"
    }
  }
  $LegacyPaths = @($Scalars.Keys | Where-Object {
    $_ -eq "limbo" -or $_ -like "limbo.*" -or
    $_ -eq "modules.starx.limbo" -or $_ -like "modules.starx.limbo.*"
  })
  if ($LegacyPaths.Count -ne 0) {
    throw "Smoke fixture contains legacy Limbo paths: $($LegacyPaths -join ', ')"
  }
}

$DefaultConfig = [System.IO.File]::ReadAllText($DefaultFixture)
$DiagnosticsConfig = [System.IO.File]::ReadAllText($DiagnosticsFixture)
Assert-Match "Default diagnostics state" $DefaultConfig '(?m)^  diagnostics:\s*\r?\n    enabled:\s*false\s*$'
Assert-Match "Enabled diagnostics state" $DiagnosticsConfig '(?m)^  diagnostics:\s*\r?\n    enabled:\s*true\s*$'
Assert-SmokePathBoundary $Smoke

$SystemTemp = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$TestRoot = Join-Path $SystemTemp ("starx-uworld-smoke-test-" + [guid]::NewGuid().ToString("N"))
$SmokeTemp = Join-Path $TestRoot "temp"
$FakeJava = Join-Path $TestRoot "fake-java.exe"
$FakeJavaFile = Join-Path $TestRoot "FakeJava.cs"
$VelocityJar = Join-Path $TestRoot "velocity-3.5.0-SNAPSHOT-606.jar"
$PluginJar = Join-Path $TestRoot "starx-velocity.jar"
$FakePid = Join-Path $TestRoot "fake-java.pid"
$PowerShell = (Get-Process -Id $PID).Path
$OldTemp = $env:TEMP
$OldTmp = $env:TMP
$OldFakePid = $env:UWORLD_FAKE_PID_FILE
$HadFakePid = Test-Path Env:UWORLD_FAKE_PID_FILE
$OldHttpDelay = $env:UWORLD_FAKE_HTTP_DELAY_MS
$HadHttpDelay = Test-Path Env:UWORLD_FAKE_HTTP_DELAY_MS
$OldExitAfterHttp = $env:UWORLD_FAKE_EXIT_AFTER_HTTP
$HadExitAfterHttp = Test-Path Env:UWORLD_FAKE_EXIT_AFTER_HTTP

$FakeJavaSource = @'
using System;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;

public static class FakeJava {
  private static void Serve404(TcpListener listener) {
    while (true) {
      try {
        using (TcpClient client = listener.AcceptTcpClient()) {
          NetworkStream stream = client.GetStream();
          byte[] request = new byte[2048];
          stream.Read(request, 0, request.Length);
          byte[] response = Encoding.ASCII.GetBytes(
              "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
          stream.Write(response, 0, response.Length);
          stream.Flush();
          if (String.Equals(
              Environment.GetEnvironmentVariable("UWORLD_FAKE_EXIT_AFTER_HTTP"),
              "true",
              StringComparison.OrdinalIgnoreCase)) {
            Environment.Exit(0);
          }
        }
      } catch (SocketException) {
        return;
      } catch (ObjectDisposedException) {
        return;
      }
    }
  }

  public static int Main(string[] args) {
    if (args.Length == 1 && args[0] == "-version") {
      Console.Error.WriteLine("openjdk version \"21.0.0\"");
      return 0;
    }

    string pidFile = Environment.GetEnvironmentVariable("UWORLD_FAKE_PID_FILE");
    if (!String.IsNullOrEmpty(pidFile)) {
      File.WriteAllText(pidFile, System.Diagnostics.Process.GetCurrentProcess().Id.ToString());
    }

    string home = Environment.CurrentDirectory;
    string[] required = {
      Path.Combine(home, "velocity.jar"),
      Path.Combine(home, "velocity.toml"),
      Path.Combine(home, "plugins", "starx-velocity.jar"),
      Path.Combine(home, "plugins", "starx", "config.yml")
    };
    foreach (string path in required) {
      if (!File.Exists(path)) {
        Console.Error.WriteLine("Missing isolated smoke input: " + path);
        return 21;
      }
    }

    TcpListener proxy = new TcpListener(IPAddress.Loopback, 25580);
    TcpListener http = new TcpListener(IPAddress.Loopback, 8790);
    proxy.Start();
    int httpDelay = 0;
    Int32.TryParse(Environment.GetEnvironmentVariable("UWORLD_FAKE_HTTP_DELAY_MS"), out httpDelay);
    Thread server = new Thread(() => {
      if (httpDelay > 0) {
        Thread.Sleep(httpDelay);
      }
      http.Start();
      Serve404(http);
    });
    server.IsBackground = true;
    server.Start();

    Console.Error.WriteLine("Uworld core initialized");
    Console.Error.WriteLine("Uworld runtime ready");
    Console.Error.WriteLine("Generated a 11x11 Uworld authentication platform");
    Console.Error.WriteLine("Authentication Uworld ready");
    Console.Error.Flush();
    Thread.Sleep(15000);
    GC.KeepAlive(proxy);
    return 0;
  }
}
'@

try {
  [System.IO.Directory]::CreateDirectory($SmokeTemp) | Out-Null
  $CompilerCandidates = @(
    (Join-Path $env:WINDIR "Microsoft.NET\Framework64\v4.0.30319\csc.exe"),
    (Join-Path $env:WINDIR "Microsoft.NET\Framework\v4.0.30319\csc.exe")
  )
  $Compiler = $CompilerCandidates | Where-Object {
    Test-Path -LiteralPath $_ -PathType Leaf
  } | Select-Object -First 1
  if ($null -eq $Compiler) {
    throw "The .NET Framework C# compiler is required for the smoke process fixture"
  }
  [System.IO.File]::WriteAllText($FakeJavaFile, $FakeJavaSource, [System.Text.Encoding]::UTF8)
  $PreviousErrorAction = $ErrorActionPreference
  try {
    $ErrorActionPreference = "Continue"
    $CompileOutput = & $Compiler /nologo /target:exe "/out:$FakeJava" $FakeJavaFile 2>&1
    $CompileExitCode = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $PreviousErrorAction
  }
  if ($CompileExitCode -ne 0 -or -not (Test-Path -LiteralPath $FakeJava -PathType Leaf)) {
    throw "Unable to compile fake Java process: $($CompileOutput -join "`n")"
  }
  $PreviousErrorAction = $ErrorActionPreference
  try {
    $ErrorActionPreference = "Continue"
    $VersionOutput = & $FakeJava -version 2>&1 | Out-String
    $VersionExitCode = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $PreviousErrorAction
  }
  if ($VersionExitCode -ne 0 -or $VersionOutput -notmatch 'version "21\.0\.0"') {
    throw "Fake Java version probe is invalid: $VersionOutput"
  }
  Write-TestJar $VelocityJar @'
Manifest-Version: 1.0
Implementation-Version: 3.5.0-SNAPSHOT (git-test-b606)
Implementation-Title: Velocity
Main-Class: com.velocitypowered.proxy.Velocity

'@
  Write-TestJar $PluginJar "Manifest-Version: 1.0`r`n`r`n"

  $env:TEMP = $SmokeTemp
  $env:TMP = $SmokeTemp
  $env:UWORLD_FAKE_PID_FILE = $FakePid
  $env:UWORLD_FAKE_HTTP_DELAY_MS = "3000"

  foreach ($Profile in @("Default", "Diagnostics")) {
    $PreviousErrorAction = $ErrorActionPreference
    try {
      $ErrorActionPreference = "Continue"
      $Output = & $PowerShell -NoProfile -ExecutionPolicy Bypass -File $Smoke `
        -VelocityJar $VelocityJar `
        -PluginJar $PluginJar `
        -Profile $Profile `
        -JavaExecutable $FakeJava `
        -StartupTimeoutSeconds 5 2>&1
      $ExitCode = $LASTEXITCODE
    } finally {
      $ErrorActionPreference = $PreviousErrorAction
    }
    $Text = $Output -join "`n"
    if ($ExitCode -ne 0) {
      throw "Smoke profile $Profile failed with exit $ExitCode`n$Text"
    }
    Assert-Match "$Profile PASS marker" $Text "(?m)^UWORLD_SMOKE=PASS profile=$Profile velocity_build=606$"
    $OutputLines = @($Text -split "\r?\n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    $ExpectedPass = "UWORLD_SMOKE=PASS profile=$Profile velocity_build=606"
    if ($OutputLines[-1] -cne $ExpectedPass) {
      throw "$Profile PASS marker must be the last nonempty output line: $($OutputLines[-1])"
    }
    if ($Profile -eq "Diagnostics") {
      Assert-Match "Diagnostics client-flow status" $Text '(?m)^UWORLD_DIAGNOSTICS_CLIENT_FLOW=UNVERIFIED$'
      $UnverifiedIndex = [array]::IndexOf($OutputLines, "UWORLD_DIAGNOSTICS_CLIENT_FLOW=UNVERIFIED")
      if ($UnverifiedIndex -lt 0 -or $UnverifiedIndex -ge ($OutputLines.Count - 1)) {
        throw "Diagnostics UNVERIFIED status must precede the final PASS marker"
      }
    } elseif ($Text -match '(?m)^UWORLD_DIAGNOSTICS_CLIENT_FLOW=') {
      throw "Default profile must not print a diagnostics client-flow result"
    }
    Assert-PortReleased 25580
    Assert-PortReleased 8790
    $LeakedHomes = @(Get-ChildItem -LiteralPath $SmokeTemp -Directory -Filter "starx-uworld-smoke-*" -ErrorAction SilentlyContinue)
    if ($LeakedHomes.Count -ne 0) {
      throw "Smoke profile $Profile leaked temporary homes: $($LeakedHomes.FullName -join ', ')"
    }
  }

  $OriginalPath = $env:PATH
  $HadJavaHome = Test-Path Env:JAVA_HOME
  $OriginalJavaHome = $env:JAVA_HOME
  try {
    $JavaBinOne = Join-Path $TestRoot "java-one"
    $JavaBinTwo = Join-Path $TestRoot "java-two"
    [System.IO.Directory]::CreateDirectory($JavaBinOne) | Out-Null
    [System.IO.Directory]::CreateDirectory($JavaBinTwo) | Out-Null
    Copy-Item -LiteralPath $FakeJava -Destination (Join-Path $JavaBinOne "java.exe")
    Copy-Item `
      -LiteralPath (Join-Path $env:WINDIR "System32\cmd.exe") `
      -Destination (Join-Path $JavaBinTwo "java.exe")
    $env:JAVA_HOME = ""
    $env:PATH = "$JavaBinOne;$JavaBinTwo"

    $PreviousErrorAction = $ErrorActionPreference
    try {
      $ErrorActionPreference = "Continue"
      $PathOutput = & $PowerShell -NoProfile -ExecutionPolicy Bypass -File $Smoke `
        -VelocityJar $VelocityJar `
        -PluginJar $PluginJar `
        -Profile Default `
        -StartupTimeoutSeconds 5 2>&1
      $PathExitCode = $LASTEXITCODE
    } finally {
      $ErrorActionPreference = $PreviousErrorAction
    }
    $PathText = $PathOutput -join "`n"
    if ($PathExitCode -ne 0) {
      throw "Smoke PATH Java discovery failed with exit $PathExitCode`n$PathText"
    }
    Assert-Match "PATH Java discovery" $PathText '(?m)^UWORLD_SMOKE=PASS profile=Default velocity_build=606$'
    Assert-PortReleased 25580
    Assert-PortReleased 8790

    $PreviousErrorAction = $ErrorActionPreference
    try {
      $ErrorActionPreference = "Continue"
      $RequestedOutput = & $PowerShell -NoProfile -ExecutionPolicy Bypass -File $Smoke `
        -VelocityJar $VelocityJar `
        -PluginJar $PluginJar `
        -Profile Default `
        -JavaExecutable java.exe `
        -StartupTimeoutSeconds 5 2>&1
      $RequestedExitCode = $LASTEXITCODE
    } finally {
      $ErrorActionPreference = $PreviousErrorAction
    }
    $RequestedText = $RequestedOutput -join "`n"
    if ($RequestedExitCode -ne 0) {
      throw "Smoke named Java discovery failed with exit $RequestedExitCode`n$RequestedText"
    }
    Assert-Match "Named Java discovery" $RequestedText '(?m)^UWORLD_SMOKE=PASS profile=Default velocity_build=606$'
    Assert-PortReleased 25580
    Assert-PortReleased 8790
  } finally {
    $env:PATH = $OriginalPath
    if ($HadJavaHome) {
      $env:JAVA_HOME = $OriginalJavaHome
    } else {
      Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue
    }
  }

  $env:UWORLD_FAKE_HTTP_DELAY_MS = "0"
  $env:UWORLD_FAKE_EXIT_AFTER_HTTP = "true"
  $PreviousErrorAction = $ErrorActionPreference
  try {
    $ErrorActionPreference = "Continue"
    $ExitOutput = & $PowerShell -NoProfile -ExecutionPolicy Bypass -File $Smoke `
      -VelocityJar $VelocityJar `
      -PluginJar $PluginJar `
      -Profile Default `
      -JavaExecutable $FakeJava `
      -StartupTimeoutSeconds 5 2>&1
    $ExitAfterHttpCode = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $PreviousErrorAction
  }
  if ($ExitAfterHttpCode -eq 0 -or ($ExitOutput -join "`n") -match 'UWORLD_SMOKE=PASS') {
    throw "Smoke script reported PASS after Java exited during the HTTP gate"
  }
  Assert-PortReleased 25580
  Assert-PortReleased 8790
  $env:UWORLD_FAKE_EXIT_AFTER_HTTP = "false"

  $PreviousErrorAction = $ErrorActionPreference
  try {
    $ErrorActionPreference = "Continue"
    $MissingOutput = & $PowerShell -NoProfile -ExecutionPolicy Bypass -File $Smoke `
      -VelocityJar (Join-Path $TestRoot "missing.jar") `
      -PluginJar $PluginJar `
      -Profile Default `
      -JavaExecutable $FakeJava `
      -StartupTimeoutSeconds 5 2>&1
    $MissingExitCode = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $PreviousErrorAction
  }
  if ($MissingExitCode -eq 0 -or ($MissingOutput -join "`n") -match 'UWORLD_SMOKE=PASS') {
    throw "Smoke script accepted a missing Velocity JAR"
  }
} finally {
  if ($OldTemp) {
    $env:TEMP = $OldTemp
  } else {
    Remove-Item Env:TEMP -ErrorAction SilentlyContinue
  }
  if ($OldTmp) {
    $env:TMP = $OldTmp
  } else {
    Remove-Item Env:TMP -ErrorAction SilentlyContinue
  }
  if ($HadFakePid) {
    $env:UWORLD_FAKE_PID_FILE = $OldFakePid
  } else {
    Remove-Item Env:UWORLD_FAKE_PID_FILE -ErrorAction SilentlyContinue
  }
  if ($HadHttpDelay) {
    $env:UWORLD_FAKE_HTTP_DELAY_MS = $OldHttpDelay
  } else {
    Remove-Item Env:UWORLD_FAKE_HTTP_DELAY_MS -ErrorAction SilentlyContinue
  }
  if ($HadExitAfterHttp) {
    $env:UWORLD_FAKE_EXIT_AFTER_HTTP = $OldExitAfterHttp
  } else {
    Remove-Item Env:UWORLD_FAKE_EXIT_AFTER_HTTP -ErrorAction SilentlyContinue
  }

  if (Test-Path -LiteralPath $FakePid -PathType Leaf) {
    $FakeProcessId = 0
    if ([int]::TryParse([System.IO.File]::ReadAllText($FakePid), [ref] $FakeProcessId)) {
      $LeakedProcess = Get-Process -Id $FakeProcessId -ErrorAction SilentlyContinue
      if ($null -ne $LeakedProcess) {
        Stop-Process -Id $FakeProcessId -Force
        $LeakedProcess.WaitForExit(5000)
      }
    }
  }

  Assert-PortReleased 25580
  Assert-PortReleased 8790
  $ResolvedTestRoot = [System.IO.Path]::GetFullPath($TestRoot)
  $DirectorySeparator = [System.IO.Path]::DirectorySeparatorChar
  $AlternateSeparator = [System.IO.Path]::AltDirectorySeparatorChar
  $Separators = [char[]] @($DirectorySeparator, $AlternateSeparator)
  $TempPrefix = $SystemTemp.TrimEnd($Separators) + $DirectorySeparator
  $Comparison = if ($DirectorySeparator -ne $AlternateSeparator) {
    [System.StringComparison]::OrdinalIgnoreCase
  } else {
    [System.StringComparison]::Ordinal
  }
  if ($ResolvedTestRoot.StartsWith($TempPrefix, $Comparison) -and
      [System.IO.Directory]::Exists($ResolvedTestRoot)) {
    [System.IO.Directory]::Delete($ResolvedTestRoot, $true)
  }
}

Write-Host "PASS: Uworld runtime smoke profiles validate startup, HTTP, and cleanup"
