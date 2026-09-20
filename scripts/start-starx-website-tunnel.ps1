[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [string]$IdentityFile,
  [string]$RemoteHost = '121.196.161.249',
  [string]$RemoteUser = 'root',
  [int]$RemotePort = 18788,
  [int]$LocalPort = 8788
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $IdentityFile -PathType Leaf)) {
  throw "SSH identity file does not exist: $IdentityFile"
}
if ($RemoteHost -notmatch '^[A-Za-z0-9.-]+$' -or $RemoteUser -notmatch '^[A-Za-z0-9._-]+$') {
  throw 'Remote SSH target contains unsupported characters'
}
if ($RemotePort -notin 1..65535 -or $LocalPort -notin 1..65535) {
  throw 'Tunnel ports must be between 1 and 65535'
}

function Resolve-SshExecutable {
  $candidates = [System.Collections.Generic.List[string]]::new()
  if (-not [string]::IsNullOrWhiteSpace($env:STARX_SSH_EXECUTABLE)) {
    $candidates.Add($env:STARX_SSH_EXECUTABLE)
  }
  if (-not [string]::IsNullOrWhiteSpace($env:ProgramFiles)) {
    $candidates.Add((Join-Path $env:ProgramFiles 'Git\usr\bin\ssh.exe'))
  }
  $command = Get-Command ssh.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($null -ne $command) {
    $candidates.Add($command.Path)
  }

  foreach ($candidate in @($candidates | Select-Object -Unique)) {
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) { continue }
    $previousErrorAction = $ErrorActionPreference
    try {
      $ErrorActionPreference = 'Continue'
      & $candidate -V 2>&1 | Out-Null
      $probeExitCode = $LASTEXITCODE
    } finally {
      $ErrorActionPreference = $previousErrorAction
    }
    if ($probeExitCode -eq 0) {
      return [IO.Path]::GetFullPath($candidate)
    }
  }
  throw 'No working OpenSSH client was found. Set STARX_SSH_EXECUTABLE to a valid ssh executable.'
}

$sshExecutable = Resolve-SshExecutable
$target = "$RemoteUser@$RemoteHost"
$reverse = "127.0.0.1:${RemotePort}:127.0.0.1:${LocalPort}"
$mutex = [Threading.Mutex]::new($false, 'Global\StarXWebsiteTunnel')
$ownsMutex = $false

try {
  try {
    $ownsMutex = $mutex.WaitOne(0)
  } catch [Threading.AbandonedMutexException] {
    $ownsMutex = $true
  }
  if (-not $ownsMutex) {
    Write-Output 'StarX website tunnel is already running'
    return
  }

  while ($true) {
    & $sshExecutable -N -R $reverse `
      -i $IdentityFile `
      -o BatchMode=yes `
      -o ExitOnForwardFailure=yes `
      -o ServerAliveInterval=20 `
      -o ServerAliveCountMax=3 `
      $target

    Write-Warning "StarX website tunnel disconnected with exit code $LASTEXITCODE; reconnecting in 5 seconds"
    Start-Sleep -Seconds 5
  }
} finally {
  if ($ownsMutex) {
    $mutex.ReleaseMutex()
  }
  $mutex.Dispose()
}
