[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$Root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$ArtifactPath = Join-Path $Root "vendor\velocity\velocity-3.5.0-SNAPSHOT-606.jar"
$ExpectedHash = "F763B42B951892C62ECDEE2E532A7788C9929A4468068227DAEA71D84F2B39F2"
$ExpectedVersion = "3.5.0-SNAPSHOT (git-1edab141-b606)"
$BuildFiles = @(
  "starx-plugins\starx-limbo-api\build.gradle.kts",
  "starx-plugins\starx-standalone-limbo\build.gradle.kts",
  "starx-plugins\starx-velocity\build.gradle.kts"
)

foreach ($RelativePath in $BuildFiles) {
  $BuildPath = Join-Path $Root $RelativePath
  $BuildText = [System.IO.File]::ReadAllText($BuildPath)
  if ($BuildText -match 'com\.velocitypowered:velocity-(?:api|proxy|native):[^"'']*SNAPSHOT') {
    throw "Floating Velocity snapshot dependency remains in $RelativePath"
  }
  if (-not $BuildText.Contains('velocityBuild606Compile')) {
    throw "Build 606 compile input is missing from $RelativePath"
  }
}

$RootBuild = [System.IO.File]::ReadAllText((Join-Path $Root "build.gradle.kts"))
foreach ($Required in @(
    'vendor/velocity/velocity-3.5.0-SNAPSHOT-606.jar',
    $ExpectedHash,
    $ExpectedVersion,
    'verifyVelocityBuild606',
    'prepareVelocityBuild606Compile',
    'include("com/velocitypowered/**")',
    'include("com/mojang/brigadier/**")',
    'include("com/google/common/**")')) {
  if (-not $RootBuild.Contains($Required)) {
    throw "Root build is missing the build 606 lock marker: $Required"
  }
}

if (-not (Test-Path -LiteralPath $ArtifactPath -PathType Leaf)) {
  throw "Vendored Velocity build 606 is missing: $ArtifactPath"
}
$ActualHash = (Get-FileHash -LiteralPath $ArtifactPath -Algorithm SHA256).Hash
if ($ActualHash -cne $ExpectedHash) {
  throw "Vendored Velocity build 606 hash mismatch expected=$ExpectedHash actual=$ActualHash"
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$Archive = [System.IO.Compression.ZipFile]::OpenRead($ArtifactPath)
try {
  $ManifestEntry = $Archive.GetEntry("META-INF/MANIFEST.MF")
  if ($null -eq $ManifestEntry) {
    throw "Vendored Velocity build 606 has no manifest"
  }
  $Reader = [System.IO.StreamReader]::new($ManifestEntry.Open())
  try {
    $Manifest = $Reader.ReadToEnd()
  } finally {
    $Reader.Dispose()
  }
} finally {
  $Archive.Dispose()
}

if ($Manifest -notmatch ('(?m)^Implementation-Version:\s*' + [regex]::Escape($ExpectedVersion) + '\r?$')) {
  throw "Vendored Velocity manifest does not identify build 606"
}

Write-Host "PASS: Velocity build 606 is the exact verified compile and test input"
