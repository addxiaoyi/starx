[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [string] $VelocityJar,

  [Parameter(Mandatory = $true)]
  [string] $PluginJar,

  [Parameter(Mandatory = $true)]
  [ValidateSet("Default", "Diagnostics")]
  [string] $Profile,

  [string] $JavaExecutable,

  [ValidateRange(1024, 65535)]
  [int] $ProxyPort = 25580,

  [ValidateRange(1024, 65535)]
  [int] $HttpPort = 8790,

  [ValidateRange(1, 45)]
  [int] $StartupTimeoutSeconds = 45
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$PortReleaseSeconds = 10
$RequiredLogs = @(
  "Uworld core initialized",
  "Uworld runtime ready",
  "Generated a 11x11 Uworld authentication platform",
  "Authentication Uworld ready"
)
$Root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$FixtureRoot = Join-Path $Root "velocity-test\fixtures\uworld"
$ProfileName = if ($Profile -ieq "Diagnostics") { "Diagnostics" } else { "Default" }
$ConfigName = if ($ProfileName -eq "Diagnostics") {
  "config-diagnostics.yml"
} else {
  "config-default.yml"
}

function Resolve-RequiredFile([string] $Path, [string] $Label) {
  if ([string]::IsNullOrWhiteSpace($Path)) {
    throw "$Label path is empty"
  }
  $Resolved = [System.IO.Path]::GetFullPath($Path)
  if (-not (Test-Path -LiteralPath $Resolved -PathType Leaf)) {
    throw "$Label is missing: $Resolved"
  }
  return $Resolved
}

function Test-PathIsStrictDescendant([string] $Path, [string] $Root) {
  if ([string]::IsNullOrWhiteSpace($Path) -or [string]::IsNullOrWhiteSpace($Root)) {
    throw "Path boundary inputs must not be empty"
  }

  $ResolvedPath = [System.IO.Path]::GetFullPath($Path)
  $ResolvedRoot = [System.IO.Path]::GetFullPath($Root)
  $DirectorySeparator = [System.IO.Path]::DirectorySeparatorChar
  $AlternateSeparator = [System.IO.Path]::AltDirectorySeparatorChar
  $Separators = [char[]] @($DirectorySeparator, $AlternateSeparator)
  $RootPrefix = $ResolvedRoot.TrimEnd($Separators) + $DirectorySeparator
  $Comparison = if ($DirectorySeparator -ne $AlternateSeparator) {
    [System.StringComparison]::OrdinalIgnoreCase
  } else {
    [System.StringComparison]::Ordinal
  }
  return $ResolvedPath.StartsWith($RootPrefix, $Comparison)
}

function Resolve-Java([string] $Requested) {
  if (-not [string]::IsNullOrWhiteSpace($Requested)) {
    if (Test-Path -LiteralPath $Requested -PathType Leaf) {
      return [System.IO.Path]::GetFullPath($Requested)
    }
    $RequestedCommand = Get-Command $Requested -CommandType Application -ErrorAction SilentlyContinue |
      Select-Object -First 1
    if ($null -ne $RequestedCommand) {
      return $RequestedCommand.Path
    }
    throw "Java executable is missing: $Requested"
  }

  if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    foreach ($Name in @("java.exe", "java")) {
      $JavaHomeExecutable = Join-Path $env:JAVA_HOME "bin\$Name"
      if (Test-Path -LiteralPath $JavaHomeExecutable -PathType Leaf) {
        return [System.IO.Path]::GetFullPath($JavaHomeExecutable)
      }
    }
  }

  $JavaCommand = Get-Command "java.exe" -CommandType Application -ErrorAction SilentlyContinue |
    Select-Object -First 1
  if ($null -eq $JavaCommand) {
    $JavaCommand = Get-Command "java" -CommandType Application -ErrorAction SilentlyContinue |
      Select-Object -First 1
  }
  if ($null -eq $JavaCommand) {
    throw "Java 21 was not found in JAVA_HOME or PATH"
  }
  return $JavaCommand.Path
}

function Assert-Java21([string] $JavaPath) {
  $PreviousErrorAction = $ErrorActionPreference
  try {
    $ErrorActionPreference = "Continue"
    $VersionOutput = & $JavaPath -version 2>&1 | Out-String
    $VersionExitCode = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $PreviousErrorAction
  }
  if ($VersionExitCode -ne 0) {
    throw "Unable to execute Java version check: $JavaPath"
  }
  $Version = [regex]::Match($VersionOutput, 'version\s+"(?<major>\d+)')
  if (-not $Version.Success -or $Version.Groups["major"].Value -ne "21") {
    throw "Uworld smoke requires Java 21; reported version:`n$($VersionOutput.Trim())"
  }
}

function Get-VelocityBuild([string] $JarPath) {
  Add-Type -AssemblyName System.IO.Compression
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $Archive = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
  try {
    $Entry = $Archive.GetEntry("META-INF/MANIFEST.MF")
    if ($null -eq $Entry) {
      throw "Velocity JAR has no META-INF/MANIFEST.MF: $JarPath"
    }
    $Reader = [System.IO.StreamReader]::new($Entry.Open(), [System.Text.Encoding]::UTF8)
    try {
      $Manifest = $Reader.ReadToEnd()
    } finally {
      $Reader.Dispose()
    }
  } finally {
    $Archive.Dispose()
  }

  $Version = [regex]::Match(
      $Manifest,
      '(?m)^Implementation-Version:\s*3\.5\.0-SNAPSHOT\s+\([^\r\n)]*-b(?<build>\d+)\)\s*$')
  if (-not $Version.Success) {
    throw "Velocity JAR is not a 3.5.0-SNAPSHOT build: $JarPath"
  }
  $Build = $Version.Groups["build"].Value
  if ($Build -ne "606") {
    throw "Uworld smoke requires Velocity build 606; actual build=$Build"
  }
  return $Build
}

function Test-PortListening([int] $Port) {
  $Properties = [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties()
  $Listeners = @($Properties.GetActiveTcpListeners() | Where-Object { $_.Port -eq $Port })
  return $Listeners.Count -gt 0
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

function Assert-ProfileConfig([string] $ConfigPath, [string] $SelectedProfile) {
  $ConfigText = [System.IO.File]::ReadAllText($ConfigPath)
  if ($ConfigText -match '(?m)^limbo:\s*$' -or
      $ConfigText -match '(?m)^  starx\.limbo:\s*$') {
    throw "Smoke fixture contains a legacy Limbo root: $ConfigPath"
  }
  $DiagnosticsEnabled = if ($SelectedProfile -eq "Diagnostics") { "true" } else { "false" }
  $Expected = [ordered]@{
    "http.bind" = "127.0.0.1"
    "http.port" = $HttpPort.ToString()
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
    "uworld.diagnostics.enabled" = $DiagnosticsEnabled
    "uworld.diagnostics.timeout-seconds" = "120"
    "uworld.diagnostics.platform-radius" = "5"
  }
  $Scalars = Read-YamlScalars $ConfigPath
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

function Receive-ProcessLines(
    [ref] $TaskReference,
    [System.IO.StreamReader] $Reader,
    [System.IO.StreamWriter] $Writer,
    [System.Text.StringBuilder] $Combined
) {
  $Task = $TaskReference.Value
  while ($null -ne $Task -and $Task.IsCompleted) {
    $Line = $Task.GetAwaiter().GetResult()
    if ($null -eq $Line) {
      $Task = $null
      break
    }
    $Writer.WriteLine($Line)
    [void] $Combined.AppendLine($Line)
    $Task = $Reader.ReadLineAsync()
  }
  $TaskReference.Value = $Task
}

function Get-LogTail([string] $Text) {
  if ([string]::IsNullOrWhiteSpace($Text)) {
    return "<no Velocity output>"
  }
  $Lines = @($Text -split "\r?\n")
  $Start = [Math]::Max(0, $Lines.Count - 80)
  return ($Lines[$Start..($Lines.Count - 1)] -join "`n")
}

function Get-MissingLogs([string] $Text) {
  return @($RequiredLogs | Where-Object {
    $Text.IndexOf($_, [System.StringComparison]::Ordinal) -lt 0
  })
}

function Wait-ForReleasedPorts([int[]] $Ports) {
  $Deadline = [System.DateTime]::UtcNow.AddSeconds($PortReleaseSeconds)
  do {
    $Occupied = @($Ports | Where-Object { Test-PortListening $_ })
    if ($Occupied.Count -eq 0) {
      return @()
    }
    Start-Sleep -Milliseconds 100
  } while ([System.DateTime]::UtcNow -lt $Deadline)
  return @($Ports | Where-Object { Test-PortListening $_ })
}

$ResolvedVelocityJar = Resolve-RequiredFile $VelocityJar "Velocity JAR"
$ResolvedPluginJar = Resolve-RequiredFile $PluginJar "StarX plugin JAR"
$VelocityFixture = Resolve-RequiredFile (Join-Path $FixtureRoot "velocity.toml") "Velocity fixture"
$ConfigFixture = Resolve-RequiredFile (Join-Path $FixtureRoot $ConfigName) "Uworld profile fixture"
$JavaPath = Resolve-Java $JavaExecutable
Assert-Java21 $JavaPath
$VelocityBuild = Get-VelocityBuild $ResolvedVelocityJar

$SystemTemp = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$TempRoot = Join-Path $SystemTemp ("starx-uworld-smoke-" + [guid]::NewGuid().ToString("N"))
$Process = $null
$StdoutWriter = $null
$StderrWriter = $null
$StdoutTask = $null
$StderrTask = $null
$LogBuilder = [System.Text.StringBuilder]::new()
$Failure = $null
$CleanupFailures = [System.Collections.Generic.List[string]]::new()
$Stdout = Join-Path $TempRoot "velocity-stdout.log"
$Stderr = Join-Path $TempRoot "velocity-stderr.log"
$LastLog = ""
$LastHttpStatus = $null

try {
  foreach ($Port in @($ProxyPort, $HttpPort)) {
    if (Test-PortListening $Port) {
      throw "Smoke port $Port is already occupied"
    }
  }

  $PluginDirectory = Join-Path $TempRoot "plugins"
  $StarxDirectory = Join-Path $PluginDirectory "starx"
  [System.IO.Directory]::CreateDirectory($StarxDirectory) | Out-Null
  $RuntimeVelocityConfig = Join-Path $TempRoot "velocity.toml"
  $RuntimeStarxConfig = Join-Path $StarxDirectory "config.yml"
  Copy-Item -LiteralPath $ResolvedVelocityJar -Destination (Join-Path $TempRoot "velocity.jar")
  Copy-Item -LiteralPath $VelocityFixture -Destination $RuntimeVelocityConfig
  Copy-Item -LiteralPath $ResolvedPluginJar -Destination (Join-Path $PluginDirectory "starx-velocity.jar")
  Copy-Item -LiteralPath $ConfigFixture -Destination $RuntimeStarxConfig

  $VelocityText = [IO.File]::ReadAllText($RuntimeVelocityConfig)
  $VelocityText = [regex]::Replace(
      $VelocityText,
      '(?m)^bind\s*=\s*"[^"]+"\s*$',
      "bind = `"127.0.0.1:$ProxyPort`"")
  [IO.File]::WriteAllText($RuntimeVelocityConfig, $VelocityText)

  $StarxText = [IO.File]::ReadAllText($RuntimeStarxConfig)
  $StarxText = [regex]::Replace(
      $StarxText,
      '(?m)^(  port:)\s*\d+\s*$',
      "`$1 $HttpPort")
  [IO.File]::WriteAllText($RuntimeStarxConfig, $StarxText)
  Assert-ProfileConfig $RuntimeStarxConfig $ProfileName

  $StartInfo = [System.Diagnostics.ProcessStartInfo]::new()
  $StartInfo.FileName = $JavaPath
  $StartInfo.Arguments = "-Djava.awt.headless=true -Dterminal.jline=false -Dterminal.ansi=false -jar velocity.jar"
  $StartInfo.WorkingDirectory = $TempRoot
  $StartInfo.UseShellExecute = $false
  $StartInfo.CreateNoWindow = $true
  $StartInfo.WindowStyle = [System.Diagnostics.ProcessWindowStyle]::Hidden
  $StartInfo.RedirectStandardOutput = $true
  $StartInfo.RedirectStandardError = $true

  $StdoutWriter = [System.IO.StreamWriter]::new($Stdout, $false, [System.Text.Encoding]::UTF8)
  $StderrWriter = [System.IO.StreamWriter]::new($Stderr, $false, [System.Text.Encoding]::UTF8)
  $StdoutWriter.AutoFlush = $true
  $StderrWriter.AutoFlush = $true

  $Process = [System.Diagnostics.Process]::new()
  $Process.StartInfo = $StartInfo
  if (-not $Process.Start()) {
    throw "Java process did not start: $JavaPath"
  }
  $StdoutTask = $Process.StandardOutput.ReadLineAsync()
  $StderrTask = $Process.StandardError.ReadLineAsync()

  Add-Type -AssemblyName System.Net.Http
  $Client = [System.Net.Http.HttpClient]::new()
  $Client.Timeout = [System.Threading.Timeout]::InfiniteTimeSpan
  try {
    $Deadline = [System.DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
    $Ready = $false
    while ([System.DateTime]::UtcNow -lt $Deadline) {
      $Process.Refresh()
      Receive-ProcessLines ([ref] $StdoutTask) $Process.StandardOutput $StdoutWriter $LogBuilder
      Receive-ProcessLines ([ref] $StderrTask) $Process.StandardError $StderrWriter $LogBuilder
      $LastLog = $LogBuilder.ToString()
      if ($Process.HasExited) {
        break
      }

      $MissingLogs = @(Get-MissingLogs $LastLog)
      if ($MissingLogs.Count -eq 0 -and (Test-PortListening $ProxyPort)) {
        $Remaining = $Deadline - [System.DateTime]::UtcNow
        if ($Remaining.TotalMilliseconds -gt 0) {
          $RequestTimeout = [int] [Math]::Ceiling(
              [Math]::Min(1000, [Math]::Max(1, $Remaining.TotalMilliseconds)))
          $Cancellation = [System.Threading.CancellationTokenSource]::new()
          try {
            $Cancellation.CancelAfter($RequestTimeout)
            $Response = $Client.GetAsync(
                "http://127.0.0.1:$HttpPort/",
                $Cancellation.Token).GetAwaiter().GetResult()
            try {
              $LastHttpStatus = [int] $Response.StatusCode
            } finally {
              $Response.Dispose()
            }
          } catch [System.Net.Http.HttpRequestException] {
            $LastHttpStatus = $null
          } catch [System.Threading.Tasks.TaskCanceledException] {
            $LastHttpStatus = $null
          } finally {
            $Cancellation.Dispose()
          }
          if ($LastHttpStatus -eq 404) {
            $LivenessRemaining = $Deadline - [System.DateTime]::UtcNow
            $LivenessWait = [int] [Math]::Min(
                100,
                [Math]::Max(0, $LivenessRemaining.TotalMilliseconds))
            $ExitedDuringGate = $Process.WaitForExit($LivenessWait)
            $Process.Refresh()
            if (-not $ExitedDuringGate -and -not $Process.HasExited) {
              $Ready = $true
              break
            }
          }
        }
      }
      Start-Sleep -Milliseconds 200
    }

    if (-not $Ready) {
      $Process.Refresh()
      Receive-ProcessLines ([ref] $StdoutTask) $Process.StandardOutput $StdoutWriter $LogBuilder
      Receive-ProcessLines ([ref] $StderrTask) $Process.StandardError $StderrWriter $LogBuilder
      $LastLog = $LogBuilder.ToString()
      $MissingLogs = @(Get-MissingLogs $LastLog)
      $ProcessState = if ($Process.HasExited) {
        "exited=$($Process.ExitCode)"
      } else {
        "running"
      }
      $ProxyState = Test-PortListening $ProxyPort
      throw "Velocity startup gate failed after ${StartupTimeoutSeconds}s; process=$ProcessState proxy_listening=$ProxyState http_status=$LastHttpStatus missing_logs=$($MissingLogs -join ', ')`n$(Get-LogTail $LastLog)"
    }
  } finally {
    if ($null -ne $Client) {
      $Client.Dispose()
    }
  }
} catch {
  $Failure = $_.Exception
} finally {
  if ($null -ne $Process) {
    try {
      $Process.Refresh()
      if ($null -eq $Failure -and $Process.HasExited) {
        $CleanupFailures.Add(
            "Java process $($Process.Id) exited before the controlled smoke shutdown")
      }
      if (-not $Process.HasExited) {
        $Process.Kill()
      }
      if (-not $Process.WaitForExit(10000)) {
        $CleanupFailures.Add("Java process $($Process.Id) did not exit within 10 seconds")
      }
      Receive-ProcessLines ([ref] $StdoutTask) $Process.StandardOutput $StdoutWriter $LogBuilder
      Receive-ProcessLines ([ref] $StderrTask) $Process.StandardError $StderrWriter $LogBuilder
    } catch {
      $CleanupFailures.Add("Unable to terminate Java process $($Process.Id): $($_.Exception.Message)")
    } finally {
      $Process.Dispose()
    }
  }

  if ($null -ne $StdoutWriter) {
    $StdoutWriter.Dispose()
  }
  if ($null -ne $StderrWriter) {
    $StderrWriter.Dispose()
  }

  $OccupiedPorts = @(Wait-ForReleasedPorts @($ProxyPort, $HttpPort))
  if ($OccupiedPorts.Count -ne 0) {
    $CleanupFailures.Add("Smoke ports remained occupied: $($OccupiedPorts -join ', ')")
  }

  $ResolvedTempRoot = [System.IO.Path]::GetFullPath($TempRoot)
  if (-not (Test-PathIsStrictDescendant $ResolvedTempRoot $SystemTemp)) {
    $CleanupFailures.Add("Refused to remove smoke directory outside system temp: $ResolvedTempRoot")
  } elseif ([System.IO.Directory]::Exists($ResolvedTempRoot)) {
    try {
      [System.IO.Directory]::Delete($ResolvedTempRoot, $true)
    } catch {
      $CleanupFailures.Add("Unable to remove smoke directory ${ResolvedTempRoot}: $($_.Exception.Message)")
    }
  }
}

if ($null -ne $Failure) {
  $CleanupText = if ($CleanupFailures.Count -eq 0) {
    ""
  } else {
    "`nCleanup failures: $($CleanupFailures -join '; ')"
  }
  throw [System.InvalidOperationException]::new($Failure.Message + $CleanupText, $Failure)
}
if ($CleanupFailures.Count -ne 0) {
  throw "Uworld smoke cleanup failed: $($CleanupFailures -join '; ')"
}

if ($ProfileName -eq "Diagnostics") {
  Write-Host "UWORLD_DIAGNOSTICS_CLIENT_FLOW=UNVERIFIED"
}
Write-Host "UWORLD_SMOKE=PASS profile=$ProfileName velocity_build=$VelocityBuild"
