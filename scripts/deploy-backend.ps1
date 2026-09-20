[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [string]$IdentityFile,
  [string]$HostName = '121.196.161.249',
  [string]$RemoteUser = 'root',
  [string]$BackendRoot = (Join-Path $PSScriptRoot '..\重构\backend')
)

$ErrorActionPreference = 'Stop'

foreach ($path in @($IdentityFile, $BackendRoot)) {
  if (-not (Test-Path -LiteralPath $path)) { throw "部署输入不存在: $path" }
}
if ($HostName -notmatch '^[A-Za-z0-9._-]+$' -or $RemoteUser -notmatch '^[A-Za-z0-9._-]+$') {
  throw '远端主机或用户名包含不允许的字符'
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$archive = Join-Path $env:TEMP "starmc-backend-$stamp.tar.gz"
$remoteArchive = "/tmp/starmc-backend-$stamp.tar.gz"
$target = "$RemoteUser@$HostName"
$sshArgs = @(
  '-F', 'none',
  '-i', $IdentityFile,
  '-o', 'BatchMode=yes',
  '-o', 'StrictHostKeyChecking=yes',
  '-o', 'ProxyCommand=none',
  '-o', 'ProxyJump=none',
  '-o', 'BindAddress=192.168.0.100'
)

try {
  & tar -czf $archive -C $BackendRoot src docs package.json package-lock.json
  if ($LASTEXITCODE -ne 0) { throw '后端发布包创建失败' }
  & scp @sshArgs $archive "$target`:$remoteArchive"
  if ($LASTEXITCODE -ne 0) { throw '后端发布包上传失败' }

  $remoteScript = @'
set -euo pipefail
archive="$1"
stamp="$2"
root='/www/wwwroot/starmc-api'
stage="/tmp/starmc-api-stage-$stamp"
backup="/www/wwwroot/starmc-api.rollback-backend-$stamp"

mkdir -p "$stage" "$backup"
tar -xzf "$archive" -C "$stage"
test -f "$stage/src/server.js"
test -f "$stage/src/modules/plugin-gateway/emailChallenge.js"
test -f "$stage/package.json"

cp -a "$root/src" "$backup/src"
cp -a "$root/docs" "$backup/docs"
cp -a "$root/package.json" "$backup/package.json"
cp -a "$root/package-lock.json" "$backup/package-lock.json"

restore() {
  status=$?
  set +e
  rm -rf "$root/src" "$root/docs"
  cp -a "$backup/src" "$root/src"
  cp -a "$backup/docs" "$root/docs"
  cp "$backup/package.json" "$root/package.json"
  cp "$backup/package-lock.json" "$root/package-lock.json"
  cd "$root"
  NPM_CONFIG_REGISTRY=https://registry.npmjs.org NPM_CONFIG_REPLACE_REGISTRY_HOST=always npm ci --omit=dev
  pm2 restart starmc-api --update-env
  exit "$status"
}
trap 'restore' ERR

rm -rf "$root/src" "$root/docs"
mv "$stage/src" "$root/src"
mv "$stage/docs" "$root/docs"
cp "$stage/package.json" "$root/package.json"
cp "$stage/package-lock.json" "$root/package-lock.json"
cd "$root"
NPM_CONFIG_REGISTRY=https://registry.npmjs.org NPM_CONFIG_REPLACE_REGISTRY_HOST=always npm ci --omit=dev
pm2 restart starmc-api --update-env

for i in $(seq 1 90); do
  curl -fsS http://127.0.0.1:8787/api/health >/dev/null && break
  sleep 1
done
curl -fsS http://127.0.0.1:8787/api/health >/dev/null
UnauthorizedStatus=$(curl -sS -o /tmp/starmc-email-probe.json -w '%{http_code}' \
  -H 'Content-Type: application/json' \
  -d '{"email":"probe@example.com","code":"123456"}' \
  http://127.0.0.1:8787/api/v1/plugin/email-challenge/send)
case "$UnauthorizedStatus" in
  401|403) ;;
  *) exit 1 ;;
esac

pm2 save >/dev/null
trap - ERR
rm -rf "$stage" "$archive"
printf 'health=ok email_gateway_unauthorized_status=%s backup=%s\n' "$UnauthorizedStatus" "$backup"
'@

  $remoteScript | & ssh @sshArgs $target "bash -s -- '$remoteArchive' '$stamp'"
  if ($LASTEXITCODE -ne 0) { throw '远端后端部署失败，已尝试自动回滚' }
} finally {
  Remove-Item -LiteralPath $archive -Force -ErrorAction SilentlyContinue
}
