$ErrorActionPreference = "Stop"

$Root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$VelocityConfig = Join-Path $Root "velocity-test\velocity.toml"
$PaperConfig = Join-Path $Root "velocity-test\.paper-runtime\instances\factions\config\paper-global.yml"
$SpigotConfig = Join-Path $Root "velocity-test\.paper-runtime\instances\factions\spigot.yml"

function Require-Match([string] $Path, [string] $Pattern, [string] $Name) {
  $content = Get-Content -LiteralPath $Path -Raw
  if ($content -notmatch $Pattern) {
    throw "$Name is not configured as required: $Path"
  }
}

Require-Match $VelocityConfig '(?m)^player-info-forwarding-mode\s*=\s*"modern"\s*$' "Velocity modern forwarding"
Require-Match $PaperConfig '(?ms)^proxies:\s*.*?^  velocity:\s*.*?^    enabled:\s*true\s*$' "Paper Velocity forwarding"
Require-Match $SpigotConfig '(?m)^  bungeecord:\s*false\s*$' "Legacy BungeeCord forwarding disabled"

Write-Output "VELOCITY_PAPER_FORWARDING=PASS"
