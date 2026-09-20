[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [ValidatePattern('^[A-Fa-f0-9]{64}$')]
  [string] $ExpectedVelocitySha256,

  [Parameter(Mandatory = $true)]
  [ValidatePattern('^[A-Fa-f0-9]{64}$')]
  [string] $ExpectedServerSha256,

  [string] $RunRoot,
  [int] $ProtectedVelocityPid = 82260,
  [int] $ProtectedPaperPid = 134572
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$RepoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$ProbeRoot = Join-Path $RepoRoot 'tmp\uworld-real-client-probe'
$VelocityJar = Join-Path $RepoRoot 'starx-plugins\starx-velocity\build\libs\starx-velocity.jar'
$ServerJar = Join-Path $RepoRoot 'starx-plugins\starx-server\build\libs\starx-server.jar'
$Client = Join-Path $ProbeRoot 'probe.mjs'
$Ports = @(25581, 8791, 25583, 25584)

if ([string]::IsNullOrWhiteSpace($RunRoot)) {
  $RunRoot = Join-Path $ProbeRoot 'runs\20260716-090951-039'
}
$RunRoot = [IO.Path]::GetFullPath($RunRoot)
$PaperRoot = Join-Path $RunRoot 'paper'
$VelocityRoot = Join-Path $RunRoot 'velocity'

function Resolve-File([string] $Path, [string] $Label) {
  $Resolved = [IO.Path]::GetFullPath($Path)
  if (-not (Test-Path -LiteralPath $Resolved -PathType Leaf)) {
    throw "$Label is missing: $Resolved"
  }
  return $Resolved
}

function Assert-Hash([string] $Path, [string] $Expected, [string] $Label) {
  $Actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
  if ($Actual -cne $Expected.ToUpperInvariant()) {
    throw "$Label SHA-256 mismatch: expected=$Expected actual=$Actual path=$Path"
  }
}

function Test-PortListening([int] $Port) {
  $Listeners = [Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
  return @($Listeners | Where-Object { $_.Port -eq $Port }).Count -gt 0
}

function Wait-Log(
    [Diagnostics.Process] $Process,
    [string] $Path,
    [string] $Pattern,
    [int] $TimeoutSeconds,
    [string] $Label
) {
  $Deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
  while ([DateTime]::UtcNow -lt $Deadline) {
    $Process.Refresh()
    if ($Process.HasExited) {
      $Tail = if (Test-Path -LiteralPath $Path) {
        (Get-Content -LiteralPath $Path -Tail 80) -join [Environment]::NewLine
      } else {
        '<log missing>'
      }
      throw "$Label exited with code $($Process.ExitCode):`n$Tail"
    }
    if (Test-Path -LiteralPath $Path) {
      $Text = Get-Content -Raw -LiteralPath $Path -ErrorAction SilentlyContinue
      if ($Text -match $Pattern) {
        return
      }
    }
    Start-Sleep -Milliseconds 500
  }
  throw "$Label did not emit '$Pattern' within ${TimeoutSeconds}s"
}

function Start-LoggedProcess(
    [string] $WorkingDirectory,
    [string] $Command,
    [string] $LogPath
) {
  $QuotedLog = $LogPath.Replace('"', '""')
  $Start = [Diagnostics.ProcessStartInfo]::new()
  $Start.FileName = $env:ComSpec
  $Start.WorkingDirectory = $WorkingDirectory
  $Start.Arguments = "/d /s /c `"$Command > `"`"$QuotedLog`"`" 2>&1`""
  $Start.UseShellExecute = $false
  $Start.RedirectStandardInput = $true
  $Start.CreateNoWindow = $true

  $Process = [Diagnostics.Process]::new()
  $Process.StartInfo = $Start
  if (-not $Process.Start()) {
    throw "Failed to start command in $WorkingDirectory"
  }
  return $Process
}

function Resolve-JavaChild(
    [Diagnostics.Process] $HostProcess,
    [string] $JarName
) {
  $Deadline = [DateTime]::UtcNow.AddSeconds(10)
  while ([DateTime]::UtcNow -lt $Deadline) {
    $Java = $null
    try {
      $Child = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" |
        Where-Object { [int] $_.ParentProcessId -eq $HostProcess.Id } |
        Select-Object -First 1
      if ($null -ne $Child) {
        $Java = Get-Process -Id ([int] $Child.ProcessId) -ErrorAction SilentlyContinue
      }
    } catch {
      $Java = Get-Process -Name 'java' -ErrorAction SilentlyContinue | Where-Object {
        try {
          $null -ne $_.Parent -and $_.Parent.Id -eq $HostProcess.Id
        } catch {
          $false
        }
      } | Select-Object -First 1
    }
    if ($null -ne $Java) {
      return $Java
    }

    $HostProcess.Refresh()
    if ($HostProcess.HasExited) {
      throw "Host PID $($HostProcess.Id) exited before $JarName ownership was recorded"
    }
    Start-Sleep -Milliseconds 250
  }
  Write-Warning "Could not resolve the Java child for $JarName under PID $($HostProcess.Id); host tree cleanup remains authoritative"
  return $null
}

function Stop-ProcessTreeId([int] $ProcessId) {
  if ($ProcessId -le 0) {
    return
  }
  if ($null -eq (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)) {
    return
  }

  & taskkill.exe /PID $ProcessId /T /F 2>$null | Out-Null
  $TaskkillExitCode = $LASTEXITCODE
  $Deadline = [DateTime]::UtcNow.AddSeconds(10)
  while ([DateTime]::UtcNow -lt $Deadline) {
    if ($null -eq (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)) {
      return
    }
    Start-Sleep -Milliseconds 100
  }
  if ($TaskkillExitCode -ne 0) {
    throw "Failed to clean up PID ${ProcessId}: taskkill exit=$TaskkillExitCode"
  }
  throw "PID ${ProcessId} did not exit after process-tree cleanup"
}

function Stop-SingleProcessId([int] $ProcessId) {
  if ($ProcessId -le 0) {
    return
  }
  if ($null -eq (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)) {
    return
  }
  Stop-Process -Id $ProcessId -Force -ErrorAction Stop
  $Deadline = [DateTime]::UtcNow.AddSeconds(10)
  while ([DateTime]::UtcNow -lt $Deadline) {
    if ($null -eq (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)) {
      return
    }
    Start-Sleep -Milliseconds 100
  }
  throw "PID ${ProcessId} did not exit after direct cleanup"
}

function Stop-ProcessTree([Diagnostics.Process] $Process) {
  if ($null -eq $Process) {
    return
  }
  try {
    Stop-ProcessTreeId $Process.Id
  } catch [System.InvalidOperationException] {
    return
  }
}

function Invoke-Cleanup([string] $Label, [scriptblock] $Action) {
  try {
    & $Action
  } catch {
    [void] $CleanupWarnings.Add("${Label}: $($_.Exception.Message)")
  }
}

function Stop-OwnedProcess([Diagnostics.Process] $Process, [string] $Command) {
  if ($null -eq $Process) {
    return
  }
  $Process.Refresh()
  if ($Process.HasExited) {
    return
  }
  try {
    $Process.StandardInput.WriteLine($Command)
    $Process.StandardInput.Flush()
    if ($Process.WaitForExit(30000)) {
      return
    }
  } catch {
    Write-Warning "Graceful stop failed for PID $($Process.Id): $($_.Exception.Message)"
  }
  Stop-ProcessTree $Process
}

$VelocityJar = Resolve-File $VelocityJar 'Velocity plugin'
$ServerJar = Resolve-File $ServerJar 'Server plugin'
$Client = Resolve-File $Client 'Mineflayer probe'
Assert-Hash $VelocityJar $ExpectedVelocitySha256 'Velocity plugin'
Assert-Hash $ServerJar $ExpectedServerSha256 'Server plugin'

foreach ($Port in $Ports) {
  if (Test-PortListening $Port) {
    throw "Bridge smoke port is already occupied: $Port"
  }
}

$ProtectedBefore = @{}
foreach ($ProtectedId in @($ProtectedVelocityPid, $ProtectedPaperPid)) {
  $Process = Get-Process -Id $ProtectedId -ErrorAction SilentlyContinue
  if ($null -ne $Process) {
    $ProtectedBefore[$ProtectedId] = $Process.StartTime
  }
}

$PaperPluginDir = Join-Path $PaperRoot 'plugins'
[IO.Directory]::CreateDirectory($PaperPluginDir) | Out-Null
Copy-Item -LiteralPath $VelocityJar -Destination (
  Join-Path $VelocityRoot 'plugins\starx-velocity.jar') -Force
Copy-Item -LiteralPath $ServerJar -Destination (
  Join-Path $PaperPluginDir 'starx-server.jar') -Force

$Stamp = [DateTimeOffset]::Now.ToString('yyyyMMdd-HHmmss-fff')
$PaperLog = Join-Path $RunRoot "bridge-$Stamp-paper.log"
$VelocityLog = Join-Path $RunRoot "bridge-$Stamp-velocity.log"
$ClientLog = Join-Path $RunRoot "bridge-$Stamp-client.log"
$ClientError = Join-Path $RunRoot "bridge-$Stamp-client-error.log"
$PaperProcess = $null
$PaperJavaProcess = $null
$VelocityProcess = $null
$VelocityJavaProcess = $null
$ClientProcess = $null
$ClientProcessId = 0
$CleanupWarnings = [Collections.Generic.List[string]]::new()
$CleanupFailures = [Collections.Generic.List[string]]::new()

try {
  $PaperProcess = Start-LoggedProcess `
    $PaperRoot `
    'java -Xms256M -Xmx512M -Dcom.mojang.eula.agree=true -jar paper.jar --nogui' `
    $PaperLog
  Wait-Log $PaperProcess $PaperLog 'Done \([0-9.]+s\)' 180 'Paper'
  $PaperJavaProcess = Resolve-JavaChild $PaperProcess 'paper.jar'

  $VelocityProcess = Start-LoggedProcess `
    $VelocityRoot `
    'java -Xms256M -Xmx512M -Djava.awt.headless=true -Dterminal.jline=false -Dterminal.ansi=false -jar velocity.jar' `
    $VelocityLog
  Wait-Log $VelocityProcess $VelocityLog 'Uworld runtime ready' 90 'Velocity'
  $VelocityJavaProcess = Resolve-JavaChild $VelocityProcess 'velocity.jar'

  $ClientProcess = Start-Process -FilePath 'node' -ArgumentList @($Client) `
    -WorkingDirectory $ProbeRoot -RedirectStandardOutput $ClientLog `
    -RedirectStandardError $ClientError -WindowStyle Hidden -PassThru
  $ClientProcessId = $ClientProcess.Id
  Wait-Log $ClientProcess $ClientLog 'CLIENT_SPAWN count=1' 60 'Mineflayer'
  Wait-Log $PaperProcess $PaperLog 'StarX backend ready: node=backend platform=PAPER bridge=true' 30 'Paper bridge'
  Wait-Log $PaperProcess $PaperLog 'UworldProbe joined the game' 60 'Paper player carrier'

  Start-Sleep -Seconds 2
  $VelocityProcess.StandardInput.WriteLine('sxnodes status lobby')
  $VelocityProcess.StandardInput.Flush()
  Wait-Log $VelocityProcess $VelocityLog 'Platform: PAPER' 30 'Velocity bridge status'
  Wait-Log $VelocityProcess $VelocityLog 'Players: 1/4' 30 'Velocity bridge players'
  Wait-Log $VelocityProcess $VelocityLog 'Capabilities: .*bridge\.v1' 30 'Velocity bridge capabilities'

  $PaperProcess.StandardInput.WriteLine('starxserver status')
  $PaperProcess.StandardInput.Flush()
  Wait-Log $PaperProcess $PaperLog 'Last proxy contact: (?!not-seen\b)\d{4}-' 30 `
    'Paper proxy contact'

} finally {
  Invoke-Cleanup 'Mineflayer' {
    Stop-SingleProcessId $ClientProcessId
  }
  Invoke-Cleanup 'Velocity host' { Stop-OwnedProcess $VelocityProcess 'shutdown' }
  Invoke-Cleanup 'Velocity Java' { Stop-ProcessTree $VelocityJavaProcess }
  Invoke-Cleanup 'Velocity host retry' { Stop-ProcessTree $VelocityProcess }
  Invoke-Cleanup 'Paper host' { Stop-OwnedProcess $PaperProcess 'stop' }
  Invoke-Cleanup 'Paper Java' { Stop-ProcessTree $PaperJavaProcess }
  Invoke-Cleanup 'Paper host retry' { Stop-ProcessTree $PaperProcess }

  foreach ($Entry in $ProtectedBefore.GetEnumerator()) {
    $Process = Get-Process -Id $Entry.Key -ErrorAction SilentlyContinue
    if ($null -eq $Process -or $Process.StartTime -ne $Entry.Value) {
      [void] $CleanupFailures.Add(
        "Protected process PID $($Entry.Key) changed during bridge smoke")
    }
  }
  if ($ClientProcessId -gt 0 -and
      $null -ne (Get-Process -Id $ClientProcessId -ErrorAction SilentlyContinue)) {
    [void] $CleanupFailures.Add("Tracked PID $ClientProcessId is still running")
  }
  foreach ($Tracked in @(
    $VelocityProcess,
    $VelocityJavaProcess,
    $PaperProcess,
    $PaperJavaProcess
  )) {
    if ($null -eq $Tracked) {
      continue
    }
    if ($null -ne (Get-Process -Id $Tracked.Id -ErrorAction SilentlyContinue)) {
      [void] $CleanupFailures.Add("Tracked PID $($Tracked.Id) is still running")
    }
  }
  $Listening = @($Ports | Where-Object { Test-PortListening $_ })
  if ($Listening.Count -ne 0) {
    [void] $CleanupFailures.Add(
      "Bridge smoke left ports listening: $($Listening -join ', ')")
  }
}

if ($CleanupFailures.Count -ne 0) {
  $Details = @($CleanupFailures)
  if ($CleanupWarnings.Count -ne 0) {
    $Details += @($CleanupWarnings | ForEach-Object { "recovered cleanup error: $_" })
  }
  throw "Bridge smoke cleanup failed:`n - $($Details -join "`n - ")"
}

Write-Host "VELOCITY_LOG=$VelocityLog"
Write-Host "PAPER_LOG=$PaperLog"
Write-Host "VELOCITY_SHA256=$($ExpectedVelocitySha256.ToUpperInvariant())"
Write-Host "SERVER_SHA256=$($ExpectedServerSha256.ToUpperInvariant())"
Write-Host 'STARX_BRIDGE_SMOKE=PASS platform=PAPER players=1/4 capability=bridge.v1'
$global:LASTEXITCODE = 0
