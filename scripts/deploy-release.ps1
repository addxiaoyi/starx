param(
  [Parameter(Mandatory = $true)]
  [string]$IdentityFile,
  [string]$HostName = '121.196.161.249',
  [string]$RemoteUser = 'root',
  [string]$ProxyJump = '',
  [string]$ReleaseRoot = '',
  [string]$LocalBackupRoot = ''
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($ReleaseRoot)) {
  $ReleaseRoot = Join-Path $PSScriptRoot '..\重构\starmc\release-current'
}
if ([string]::IsNullOrWhiteSpace($LocalBackupRoot)) {
  $LocalBackupRoot = Join-Path $PSScriptRoot '..\backups\release'
}

function Assert-LocalPath([string]$Path, [string]$Label) {
  if (-not (Test-Path -LiteralPath $Path)) {
    throw "$Label 不存在: $Path"
  }
}

function Assert-RemoteToken([string]$Value, [string]$Label) {
  if ($Value -notmatch '^[A-Za-z0-9._-]+$') {
    throw "$Label 包含不允许的字符"
  }
}

function Resolve-OpenSshSuite {
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
    if ($probeExitCode -ne 0) { continue }
    $scp = Join-Path (Split-Path -Parent $candidate) 'scp.exe'
    if (Test-Path -LiteralPath $scp -PathType Leaf) {
      return [pscustomobject]@{ Ssh = [IO.Path]::GetFullPath($candidate); Scp = [IO.Path]::GetFullPath($scp) }
    }
  }
  throw 'No working OpenSSH ssh/scp suite was found. Set STARX_SSH_EXECUTABLE to a valid ssh executable.'
}

function Invoke-CommandWithRetry([scriptblock]$Command, [string]$Label, [int]$Attempts = 3) {
  if ($Attempts -lt 1) { throw '重试次数必须大于 0' }

  for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
    & $Command
    if ($LASTEXITCODE -eq 0) { return }
    if ($attempt -lt $Attempts) { Start-Sleep -Seconds 2 }
  }

  throw "$Label 失败，已重试 $Attempts 次"
}

Assert-LocalPath $IdentityFile 'SSH 身份文件'
Assert-LocalPath $ReleaseRoot '发布目录'
Assert-LocalPath (Join-Path $ReleaseRoot 'manifest.json') '发布清单'
Assert-LocalPath (Join-Path $ReleaseRoot 'frontend\index.html') '前端产物'
Assert-LocalPath (Join-Path $ReleaseRoot 'backend\src\server.js') '后端产物'
Assert-RemoteToken $HostName '主机名'
Assert-RemoteToken $RemoteUser '远端用户名'

$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$remoteStage = "/tmp/starmc-release-$timestamp"
$remoteArchive = "$remoteStage.tar.gz"
$localArchive = Join-Path $env:TEMP "starmc-release-$timestamp.tar.gz"
$remoteTarget = "$RemoteUser@$HostName"
$openSsh = Resolve-OpenSshSuite
$sshOptions = @(
  '-F', 'none',
  '-i', $IdentityFile,
  '-o', 'BatchMode=yes',
  '-o', 'StrictHostKeyChecking=yes',
  '-o', 'ProxyCommand=none',
  '-o', 'ProxyJump=none',
  '-o', 'BindAddress=192.168.0.100'
)
if (-not [string]::IsNullOrWhiteSpace($ProxyJump)) {
  if ($ProxyJump -notmatch '^[A-Za-z0-9._@:-]+$') {
    throw '跳板机包含不允许的字符'
  }
  $sshOptions += '-o'
  $sshOptions += "ProxyJump=$ProxyJump"
}

Write-Host "Checking SSH access to $remoteTarget with $($openSsh.Ssh)"
& $openSsh.Ssh @sshOptions $remoteTarget 'true'
if ($LASTEXITCODE -ne 0) { throw 'SSH 身份验证失败，未上传任何文件' }

Write-Host 'Packing and uploading release-current'
& tar -czf $localArchive -C $ReleaseRoot .
if ($LASTEXITCODE -ne 0) { throw '无法压缩发布包' }
try {
  & $openSsh.Scp @sshOptions $localArchive "$remoteTarget`:$remoteArchive"
  if ($LASTEXITCODE -ne 0) { throw '发布包上传失败' }
  & $openSsh.Ssh @sshOptions $remoteTarget "mkdir -p '$remoteStage' && tar -xzf '$remoteArchive' -C '$remoteStage' && rm -f '$remoteArchive'"
  if ($LASTEXITCODE -ne 0) { throw '远端发布包解压失败' }
} finally {
  Remove-Item -LiteralPath $localArchive -Force -ErrorAction SilentlyContinue
}

$remoteScript = @'
set -euo pipefail
stage="$1"
stamp="$2"
web_root='/www/wwwroot/star-web.top'
api_root='/www/wwwroot/starmc-api'
web_backup="/www/wwwroot/star-web.top.rollback-$stamp"
api_backup="/www/wwwroot/starmc-api.rollback-$stamp"

test -d "$stage/frontend"
test -f "$stage/frontend/index.html"
test -f "$stage/backend/src/server.js"
test -f "$stage/backend/src/modules/plugin-gateway/emailChallenge.js"
test -f "$stage/backend/docs/openapi/authx.yaml"
test -f "$stage/manifest.json"
test -d "$web_root"
test -d "$api_root"
/www/server/nginx/sbin/nginx -t -c /www/server/nginx/conf/nginx.conf

node - "$stage/manifest.json" "$stage" <<'NODE'
const { createHash } = require('node:crypto')
const { readFileSync } = require('node:fs')
const { resolve, sep } = require('node:path')

const [manifestPath, releaseRoot] = process.argv.slice(2)
const root = resolve(releaseRoot)
const manifest = JSON.parse(readFileSync(manifestPath, 'utf8').replace(/^\uFEFF/, ''))
for (const entry of manifest) {
  const file = resolve(root, entry.path)
  if (!file.startsWith(`${root}${sep}`)) throw new Error(`invalid manifest path: ${entry.path}`)
  const digest = createHash('sha256').update(readFileSync(file)).digest('hex')
  if (digest !== entry.sha256) throw new Error(`manifest hash mismatch: ${entry.path}`)
}
NODE

mkdir -p "$web_backup" "$api_backup"
cp -a "$web_root/." "$web_backup/"
cp -a "$api_root/src" "$api_backup/src"
cp -a "$api_root/docs" "$api_backup/docs"
cp -a "$api_root/package.json" "$api_backup/package.json"
cp -a "$api_root/package-lock.json" "$api_backup/package-lock.json"

start_api() {
  pm2 delete starmc-api >/dev/null 2>&1 || true
  pm2 start npm --name starmc-api --cwd "$api_root" -- start
}

restore() {
  status=$?
  set +e
  rm -rf "$web_root"
  cp -a "$web_backup" "$web_root"
  rm -rf "$api_root/src"
  cp -a "$api_backup/src" "$api_root/src"
  rm -rf "$api_root/docs"
  cp -a "$api_backup/docs" "$api_root/docs"
  cp "$api_backup/package.json" "$api_root/package.json"
  cp "$api_backup/package-lock.json" "$api_root/package-lock.json"
  cd "$api_root"
  npm ci --omit=dev
  start_api
  exit "$status"
}
trap 'restore' ERR

rm -rf "$web_root"
mv "$stage/frontend" "$web_root"
rm -rf "$api_root/src"
mv "$stage/backend/src" "$api_root/src"
rm -rf "$api_root/docs"
mv "$stage/backend/docs" "$api_root/docs"
cp "$stage/backend/package.json" "$api_root/package.json"
cp "$stage/backend/package-lock.json" "$api_root/package-lock.json"

cd "$api_root"
npm ci --omit=dev
start_api
api_ready=false
for i in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:8787/api/health >/dev/null 2>&1 && ss -ltnp | grep 8787 >/dev/null; then
    api_ready=true
    break
  fi
  sleep 1
done
test "$api_ready" = true

runtime_ready=false
for i in $(seq 1 90); do
  if curl -fsS http://127.0.0.1:8787/api/health 2>/dev/null \
      | node -e 'let body=""; process.stdin.on("data", chunk => body += chunk).on("end", () => { const json = JSON.parse(body); if (json?.ok !== true) process.exit(1) })' \
    && curl -fsS -A 'Mozilla/5.0 StarMCDeployCheck' http://127.0.0.1:8787/api/auth/status 2>/dev/null \
      | node -e 'let body=""; process.stdin.on("data", chunk => body += chunk).on("end", () => { const json = JSON.parse(body); if (json?.ok !== true || typeof json?.authenticated !== "boolean") process.exit(1) })' \
    && curl -fsS -A 'Mozilla/5.0 StarMCDeployCheck' http://127.0.0.1:8787/api/server/player-stats 2>/dev/null \
      | node -e 'let body=""; process.stdin.on("data", chunk => body += chunk).on("end", () => { const json = JSON.parse(body); if (json?.ok !== true || !Array.isArray(json?.servers)) process.exit(1) })'; then
    runtime_ready=true
    break
  fi
  sleep 1
done
test "$runtime_ready" = true

npm run doctor:simple --silent
curl -fsS -A 'Mozilla/5.0 StarMCDeployCheck' http://127.0.0.1:8787/api/public/bootstrap \
  | node -e 'let body=""; process.stdin.on("data", chunk => body += chunk).on("end", () => { const json = JSON.parse(body); if (json?.ok !== true || typeof json?.auth !== "object") process.exit(1) })'
curl -fsS http://127.0.0.1:8787/api/docs/openapi/authx >/dev/null
curl -fsS -A 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/150.0.0.0 Safari/537.36' https://star-web.top/ >/dev/null
curl -fsS -A 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/150.0.0.0 Safari/537.36' https://star-web.top/favicon.ico >/dev/null
pm2 save >/dev/null
trap - ERR
rm -rf "$stage"
printf 'deployment complete; rollback copies: %s %s\n' "$web_backup" "$api_backup"
'@

Write-Host 'Creating remote rollback copies and activating release'
$remoteScript = $remoteScript -replace "`r`n", "`n"
$remoteScript | & $openSsh.Ssh @sshOptions $remoteTarget "bash -s -- '$remoteStage' '$timestamp'"
if ($LASTEXITCODE -ne 0) {
  throw "远端部署失败。回滚副本保留在 /www/wwwroot/star-web.top.rollback-$timestamp 和 /www/wwwroot/starmc-api.rollback-$timestamp"
}

$remoteRollbackArchive = "/tmp/starmc-release-rollback-$timestamp.tar.gz"
$localRollbackArchive = Join-Path $LocalBackupRoot "starmc-release-rollback-$timestamp.tar.gz"
$packRollback = "set -euo pipefail; test -d '/www/wwwroot/star-web.top.rollback-$timestamp'; test -d '/www/wwwroot/starmc-api.rollback-$timestamp'; tar -czf '$remoteRollbackArchive' -C /www/wwwroot 'star-web.top.rollback-$timestamp' 'starmc-api.rollback-$timestamp'"
Invoke-CommandWithRetry -Label '远端回滚副本打包' -Command { & $openSsh.Ssh @sshOptions $remoteTarget $packRollback }

New-Item -ItemType Directory -Force -Path $LocalBackupRoot | Out-Null
Invoke-CommandWithRetry -Label '回滚副本下载' -Command {
  Remove-Item -LiteralPath $localRollbackArchive -Force -ErrorAction SilentlyContinue
  & $openSsh.Scp @sshOptions "$remoteTarget`:$remoteRollbackArchive" $localRollbackArchive
}
& tar -tzf $localRollbackArchive | Out-Null
if ($LASTEXITCODE -ne 0) { throw '本地回滚副本校验失败，副本已保留在服务器' }

$cleanupRollback = "set -euo pipefail; test -d '/www/wwwroot/star-web.top.rollback-$timestamp'; test -d '/www/wwwroot/starmc-api.rollback-$timestamp'; rm -rf -- '/www/wwwroot/star-web.top.rollback-$timestamp' '/www/wwwroot/starmc-api.rollback-$timestamp'; rm -f -- '$remoteRollbackArchive'"
try {
  Invoke-CommandWithRetry -Label '服务器回滚副本清理' -Command { & $openSsh.Ssh @sshOptions $remoteTarget $cleanupRollback }
} catch {
  throw "本地回滚副本已保存，但服务器清理失败: $($_.Exception.Message)"
}
Write-Host "LOCAL_BACKUP=$localRollbackArchive"
