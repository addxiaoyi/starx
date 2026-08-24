[CmdletBinding(SupportsShouldProcess)]
param(
  [Parameter(Mandatory)][string] $ConfigPath,
  [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9._-]{1,80}$')][string] $ReleaseId,
  [Parameter(Mandatory)][string] $VelocityJar,
  [Parameter(Mandatory)][string] $PaperJar,
  [Parameter(Mandatory)][string] $UniversalPlugin,
  [Parameter(Mandatory)][string] $PaperGlobalTemplate,
  [string[]] $AdditionalArtifact = @()
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'StarX.Production.psm1') -Force
$config = Import-StarXProductionConfig $ConfigPath
Initialize-StarXProductionDirectories $config

function Assert-ReleasePath([string] $Path) {
  $normalized = $Path.Replace('\','/').TrimStart('/')
  if ($normalized -notmatch '^(velocity|paper)/') { throw "Release target must start with velocity/ or paper/: $Path" }
  if ($normalized -match '(^|/)\.\.(/|$)' -or [IO.Path]::IsPathRooted($Path)) { throw "Unsafe release target: $Path" }
  return $normalized
}

$mutex = Enter-StarXProductionLock $config
try {
  $entries = New-Object System.Collections.Generic.List[object]
  foreach ($fixed in @(
    @{ source=$VelocityJar; target='velocity/velocity.jar'; mutable=$false },
    @{ source=$PaperJar; target='paper/paper.jar'; mutable=$false },
    @{ source=$UniversalPlugin; target='velocity/plugins/starx-universal.jar'; mutable=$false },
    @{ source=$UniversalPlugin; target='paper/plugins/starx-universal.jar'; mutable=$false },
    @{ source=$PaperGlobalTemplate; target='paper/config/paper-global.yml'; mutable=$true }
  )) {
    $entries.Add([pscustomobject]@{ source=[IO.Path]::GetFullPath([string]$fixed.source); target=Assert-ReleasePath ([string]$fixed.target); mutable=[bool]$fixed.mutable })
  }
  foreach ($definition in $AdditionalArtifact) {
    $parts = $definition.Split('|')
    if ($parts.Count -lt 2 -or $parts.Count -gt 3 -or -not $parts[0].Trim() -or -not $parts[1].Trim()) { throw "AdditionalArtifact must use source|target or source|target|mutable syntax: $definition" }
    $mutable = $parts.Count -eq 3 -and [string]::Equals($parts[2].Trim(),'mutable',[StringComparison]::OrdinalIgnoreCase)
    if ($parts.Count -eq 3 -and -not $mutable) { throw "Unknown AdditionalArtifact mode: $($parts[2])" }
    $entries.Add([pscustomobject]@{ source=[IO.Path]::GetFullPath($parts[0].Trim()); target=Assert-ReleasePath $parts[1].Trim(); mutable=$mutable })
  }

  $releaseFiles = New-Object System.Collections.Generic.List[object]
  $seenTargets = @{}
  foreach ($entry in $entries) {
    if (-not (Test-Path -LiteralPath $entry.source)) { throw "Release source not found: $($entry.source)" }
    $item = Get-Item -LiteralPath $entry.source
    if ($item.PSIsContainer) {
      foreach ($file in Get-ChildItem -LiteralPath $entry.source -File -Recurse) {
        $relative = $file.FullName.Substring($entry.source.Length).TrimStart('\','/').Replace('\','/')
        $target = Assert-ReleasePath (($entry.target.TrimEnd('/')) + '/' + $relative)
        if ($seenTargets.ContainsKey($target)) { throw "Duplicate release target: $target" }
        $seenTargets[$target] = $true
        $releaseFiles.Add([pscustomobject]@{ source=$file.FullName; target=$target; mutable=[bool]$entry.mutable })
      }
    } else {
      if ($seenTargets.ContainsKey($entry.target)) { throw "Duplicate release target: $($entry.target)" }
      $seenTargets[$entry.target] = $true
      $releaseFiles.Add([pscustomobject]@{ source=$entry.source; target=$entry.target; mutable=[bool]$entry.mutable })
    }
  }

  $releaseRoot = Join-Path $config.paths.releaseRoot $ReleaseId
  if (Test-Path -LiteralPath $releaseRoot) { throw "Release already exists and is immutable: $releaseRoot" }
  $staging = "$releaseRoot.staging-$([Guid]::NewGuid().ToString('N'))"
  if ($PSCmdlet.ShouldProcess($releaseRoot,'Create immutable StarX release')) {
    try {
      [IO.Directory]::CreateDirectory($staging) | Out-Null
      $mutableByTarget = @{}
      foreach ($file in $releaseFiles) {
        $mutableByTarget[[string]$file.target] = [bool]$file.mutable
        $destination = Join-Path $staging $file.target
        [IO.Directory]::CreateDirectory((Split-Path -Parent $destination)) | Out-Null
        Copy-Item -LiteralPath $file.source -Destination $destination
      }
      $manifestFiles = @(Get-ChildItem -LiteralPath $staging -File -Recurse | Sort-Object FullName | ForEach-Object {
        [ordered]@{
          path=$_.FullName.Substring($staging.Length + 1).Replace('\','/')
          sha256=Get-StarXSha256 $_.FullName
          bytes=$_.Length
          mutable=[bool]$mutableByTarget[$_.FullName.Substring($staging.Length + 1).Replace('\','/')]
        }
      })
      $manifest = [ordered]@{
        schemaVersion=1
        releaseId=$ReleaseId
        createdAt=[DateTimeOffset]::UtcNow.ToString('o')
        files=$manifestFiles
      }
      Write-StarXJsonAtomic (Join-Path $staging 'manifest.json') $manifest
      Move-Item -LiteralPath $staging -Destination $releaseRoot

      $protected = @{}
      foreach ($stateName in @('current-release.json','previous-release.json')) {
        $state = Read-StarXJson (Join-Path $config.paths.stateRoot $stateName) -Optional
        if ($state -and $state.releaseId) { $protected[[string]$state.releaseId] = $true }
      }
      $all = @(Get-ChildItem -LiteralPath $config.paths.releaseRoot -Directory | Where-Object { $_.Name -notmatch '\.staging-' } | Sort-Object LastWriteTimeUtc -Descending)
      $kept = 0
      foreach ($directory in $all) {
        if ($protected.ContainsKey($directory.Name) -or $kept -lt [int]$config.backup.releaseRetentionCount) { $kept++; continue }
        Remove-Item -LiteralPath $directory.FullName -Recurse -Force
      }

      Add-StarXProductionEvent $config 'release-created' "Release $ReleaseId created" @{ releaseId=$ReleaseId; files=$manifestFiles.Count }
      Write-Output ("RELEASE_ROOT=$releaseRoot")
      Write-Output ("RELEASE_FILES=$($manifestFiles.Count)")
      Write-Output 'STARX_RELEASE_CREATED=PASS'
    } catch {
      Remove-Item -LiteralPath $staging -Recurse -Force -ErrorAction SilentlyContinue
      throw
    }
  }
} finally {
  Exit-StarXProductionLock $mutex
}
