[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [string]$IdentityFile,
  [string]$HostName = '121.196.161.249',
  [string]$RemoteUser = 'root',
  [string]$FrontendRoot = (Join-Path $PSScriptRoot '..\重构\starmc\dist'),
  [string]$LocalBackupRoot = (Join-Path $PSScriptRoot '..\backups\frontend'),
  [switch]$UseExistingLocalBackup
)

$ErrorActionPreference = 'Stop'

foreach ($path in @($IdentityFile, $FrontendRoot, (Join-Path $FrontendRoot 'index.html'))) {
  if (-not (Test-Path -LiteralPath $path)) { throw "部署输入不存在: $path" }
}
if ($HostName -notmatch '^[A-Za-z0-9._-]+$' -or $RemoteUser -notmatch '^[A-Za-z0-9._-]+$') {
  throw '远端主机或用户名包含不允许的字符'
}

$ssh = (Get-Command ssh.exe -CommandType Application -ErrorAction Stop).Path
$scp = Join-Path (Split-Path -Parent $ssh) 'scp.exe'
if (-not (Test-Path -LiteralPath $scp)) { throw "找不到与 SSH 配套的 scp: $scp" }

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$archive = Join-Path $env:TEMP "starmc-frontend-$stamp.tar.gz"
$remoteArchive = "/tmp/starmc-frontend-$stamp.tar.gz"
$remoteStage = "/tmp/starmc-frontend-$stamp"
$remoteBackupArchive = "/tmp/starmc-frontend-rollback-$stamp.tar.gz"
$localBackupArchive = Join-Path $LocalBackupRoot "starmc-frontend-rollback-$stamp.tar.gz"
$target = "$RemoteUser@$HostName"
$options = @(
  '-F', 'none',
  '-i', $IdentityFile,
  '-o', 'BatchMode=yes',
  '-o', 'StrictHostKeyChecking=yes',
  '-o', 'ProxyCommand=none',
  '-o', 'ProxyJump=none',
  '-o', 'BindAddress=192.168.0.100'
)

& tar -czf $archive -C $FrontendRoot .
if ($LASTEXITCODE -ne 0) { throw '前端压缩失败' }

try {
  & $ssh @options $target 'true'
  if ($LASTEXITCODE -ne 0) { throw 'SSH 身份验证失败' }
  & $scp @options $archive "$target`:$remoteArchive"
  if ($LASTEXITCODE -ne 0) { throw '前端上传失败' }

  $remote = @'
set -euo pipefail
archive="$1"
stage="$2"
stamp="$3"
root=/www/wwwroot/star-web.top
backup="/www/wwwroot/star-web.top.rollback-$stamp"
mkdir -p "$stage"
tar -xzf "$archive" -C "$stage"
rm -f "$archive"
test -s "$stage/index.html"
test -d "$stage/assets"
grep -q '/assets/' "$stage/index.html"
test -d "$root/assets"
previous_entry="$(grep -o 'index-[A-Za-z0-9_-]*\.js' "$root/index.html" | head -1)"
test -n "$previous_entry"
cp -an "$root/assets/." "$stage/assets/"
test -s "$stage/assets/$previous_entry"
/www/server/nginx/sbin/nginx -t -c /www/server/nginx/conf/nginx.conf
mv "$root" "$backup"
trap 'test -d "$root" || mv "$backup" "$root"' ERR
mv "$stage" "$root"
/www/server/nginx/sbin/nginx -s reload -c /www/server/nginx/conf/nginx.conf
trap - ERR
tar -czf "/tmp/starmc-frontend-rollback-$stamp.tar.gz" -C /www/wwwroot "star-web.top.rollback-$stamp"
printf 'BACKUP=%s\n' "$backup"
'@
  ($remote -replace "`r`n", "`n") | & $ssh @options $target "bash -s -- '$remoteArchive' '$remoteStage' '$stamp'"
  if ($LASTEXITCODE -ne 0) { throw '远端前端切换失败' }

  if (-not $UseExistingLocalBackup) {
    New-Item -ItemType Directory -Force -Path $LocalBackupRoot | Out-Null
    & $scp @options "$target`:$remoteBackupArchive" $localBackupArchive
    if ($LASTEXITCODE -ne 0) { throw '回滚备份下载失败，远端副本已保留' }
    & tar -tzf $localBackupArchive | Out-Null
    if ($LASTEXITCODE -ne 0) { throw '本地回滚备份校验失败，远端副本已保留' }
  }

  $cleanup = @'
set -euo pipefail
backup='__BACKUP__'
archive='__ARCHIVE__'
test -d "$backup"
case "$backup" in
  /www/wwwroot/star-web.top.rollback-*) ;;
  *) exit 64 ;;
esac
rm -rf -- "$backup"
rm -f -- "$archive"
'@
  $cleanup = $cleanup.Replace('__BACKUP__', "/www/wwwroot/star-web.top.rollback-$stamp").Replace('__ARCHIVE__', $remoteBackupArchive)
  & $ssh @options $target $cleanup
  if ($LASTEXITCODE -ne 0) { throw '本地备份已保存，但远端回滚目录清理失败' }
  if ($UseExistingLocalBackup) {
    Write-Host 'LOCAL_BACKUP=existing verified backup retained'
  } else {
    Write-Host "LOCAL_BACKUP=$localBackupArchive"
  }
} finally {
  Remove-Item -LiteralPath $archive -Force -ErrorAction SilentlyContinue
}
