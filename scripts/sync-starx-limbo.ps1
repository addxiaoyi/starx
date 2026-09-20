param(
  [string]$SourceRepo = "",
  [string]$ArtifactPath = ""
)

$ErrorActionPreference = "Stop"
$UpstreamCommit = "839773cfd406458cf247fbfd64ed492926f921b7"
$ArtifactSha256 = "18AC6287D413234C4FC317267A6D5DBF978ADAE8BF3F098A1248966BF2C32CE9"
$Utf8NoBom = [System.Text.UTF8Encoding]::new($false)
$Root = Split-Path -Parent $PSScriptRoot
$VendorTarget = Join-Path $Root "starx-plugins/starx-standalone-limbo/vendor/limboapi-1.1.27-SNAPSHOT.jar"

if ([string]::IsNullOrWhiteSpace($SourceRepo)) {
  $SourceRepo = Join-Path $Root "LimboAPI-source"
}
if ([string]::IsNullOrWhiteSpace($ArtifactPath)) {
  $ArtifactPath = $VendorTarget
}

$SourceRepo = [System.IO.Path]::GetFullPath($SourceRepo)
$ArtifactPath = [System.IO.Path]::GetFullPath($ArtifactPath)
$VendorTarget = [System.IO.Path]::GetFullPath($VendorTarget)
$ApiTarget = Join-Path $Root "starx-plugins/starx-limbo-api/src/main/java/io/github/addxiaoyi/starx"
$CoreTarget = Join-Path $Root "starx-plugins/starx-standalone-limbo/src/main/java/io/github/addxiaoyi/starx/limbo"
$CoreOverrides = [System.Collections.Generic.HashSet[string]]::new(
  [System.StringComparer]::Ordinal
)
@(
  "LimboAPI.java",
  "server/LimboImpl.java",
  "server/LimboPlayerImpl.java",
  "server/CachedPackets.java",
  "injection/packet/UpsertPlayerInfoHook.java"
) | ForEach-Object { [void]$CoreOverrides.Add($_) }

function Get-UpstreamText([string]$Path) {
  $text = git -C $SourceRepo show "${UpstreamCommit}:$Path" | Out-String
  if ($LASTEXITCODE -ne 0) {
    throw "Unable to read upstream source: $Path"
  }
  return $text.Replace("`r`n", "`n").TrimEnd("`r", "`n") + "`n"
}

function Write-Source([string]$Path, [string]$Text) {
  $parent = Split-Path -Parent $Path
  [System.IO.Directory]::CreateDirectory($parent) | Out-Null
  [System.IO.File]::WriteAllText($Path, $Text, $Utf8NoBom)
}

git -C $SourceRepo cat-file -e "${UpstreamCommit}^{commit}"
if ($LASTEXITCODE -ne 0) {
  throw "Upstream commit $UpstreamCommit is not available in $SourceRepo"
}

foreach ($relative in $CoreOverrides) {
  $overridePath = Join-Path $CoreTarget $relative
  if (-not (Test-Path -LiteralPath $overridePath -PathType Leaf)) {
    throw "Embedded Limbo override is missing: $overridePath"
  }
}

if (-not (Test-Path -LiteralPath $ArtifactPath -PathType Leaf)) {
  throw "LimboAPI artifact is missing: $ArtifactPath"
}
$actualSha = (Get-FileHash -LiteralPath $ArtifactPath -Algorithm SHA256).Hash
if ($actualSha -ne $ArtifactSha256) {
  throw "Unexpected LimboAPI artifact checksum: $actualSha"
}

$apiPrefix = "api/src/main/java/net/elytrium/limboapi/api/"
$apiFiles = @(
  git -C $SourceRepo ls-tree -r --name-only $UpstreamCommit -- $apiPrefix |
    Where-Object { $_.EndsWith(".java", [System.StringComparison]::Ordinal) }
)
if ($LASTEXITCODE -ne 0 -or $apiFiles.Count -eq 0) {
  throw "Unable to list upstream API sources under $apiPrefix"
}

$corePrefix = "plugin/src/main/java/net/elytrium/limboapi/"
$coreFiles = @(
  git -C $SourceRepo ls-tree -r --name-only $UpstreamCommit -- $corePrefix |
    Where-Object { $_.EndsWith(".java", [System.StringComparison]::Ordinal) }
)
if ($LASTEXITCODE -ne 0 -or $coreFiles.Count -eq 0) {
  throw "Unable to list upstream core sources under $corePrefix"
}

$upstreamCoreFiles = [System.Collections.Generic.HashSet[string]]::new(
  [System.StringComparer]::Ordinal
)
foreach ($path in $coreFiles) {
  [void]$upstreamCoreFiles.Add($path.Substring($corePrefix.Length))
}
foreach ($relative in $CoreOverrides) {
  if (-not $upstreamCoreFiles.Contains($relative)) {
    throw "Embedded Limbo override no longer exists upstream: $relative"
  }
}

foreach ($path in $apiFiles) {
  $relative = $path.Substring($apiPrefix.Length)
  $text = (Get-UpstreamText $path).Replace(
    "net.elytrium.limboapi.api",
    "io.github.addxiaoyi.starx"
  )
  Write-Source (Join-Path $ApiTarget $relative) $text
}

$syncedCoreCount = 0
foreach ($path in $coreFiles) {
  $relative = $path.Substring($corePrefix.Length)
  if ($CoreOverrides.Contains($relative)) {
    continue
  }
  $text = Get-UpstreamText $path
  $text = $text.Replace("net.elytrium.limboapi.api", "io.github.addxiaoyi.starx")
  $text = $text.Replace("net.elytrium.limboapi", "io.github.addxiaoyi.starx.limbo")
  $text = $text.Replace("BuildConstants.LIMBO_VERSION", '"1.1.27-SNAPSHOT"')
  Write-Source (Join-Path $CoreTarget $relative) $text
  $syncedCoreCount++
}

[System.IO.Directory]::CreateDirectory((Split-Path -Parent $VendorTarget)) | Out-Null
if (-not [string]::Equals($ArtifactPath, $VendorTarget, [System.StringComparison]::OrdinalIgnoreCase)) {
  Copy-Item -LiteralPath $ArtifactPath -Destination $VendorTarget -Force
}

Write-Host "Synced $($apiFiles.Count) API files and $syncedCoreCount core files from $UpstreamCommit; preserved $($CoreOverrides.Count) embedded overrides."
