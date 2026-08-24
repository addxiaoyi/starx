[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [string]$IdentityFile,
  [string]$HostName = '121.196.161.249',
  [string]$RemoteUser = 'root',
  [switch]$Apply
)

$ErrorActionPreference = 'Stop'

foreach ($path in @($IdentityFile)) {
  if (-not (Test-Path -LiteralPath $path)) { throw "清理输入不存在: $path" }
}
if ($HostName -notmatch '^[A-Za-z0-9._-]+$' -or $RemoteUser -notmatch '^[A-Za-z0-9._-]+$') {
  throw '远端主机或用户名包含不允许的字符'
}

$mode = if ($Apply) { 'apply' } else { 'dry-run' }
$ssh = (Get-Command ssh.exe -CommandType Application -ErrorAction Stop).Path
$options = @(
  '-F', 'none',
  '-i', $IdentityFile,
  '-o', 'BatchMode=yes',
  '-o', 'StrictHostKeyChecking=yes',
  '-o', 'ProxyCommand=none',
  '-o', 'ProxyJump=none'
)
$target = "$RemoteUser@$HostName"

$nodeSource = @'
import fs from "node:fs"
import crypto from "node:crypto"
import supertokens from "supertokens-node"
import { initSuperTokens } from "./src/auth/supertokensInit.js"

const file = "/www/wwwroot/starmc-api/data/store.json"
const canonicalId = "b751ac87-600f-4c9a-ba19-513db245ac8f"
const staleIdentityId = "751afd6f-aed4-47b7-911d-859f24b03ac4"
const sharedDuplicateId = "2e21fdb2-fcc1-4846-ad55-1f19736a7e28"
const testUserId = "3419eecf-f4eb-48aa-a458-dce41ac28676"
const probeUserId = "6befab79-e82a-455c-b6c3-073f20ebe9bd"
const store = JSON.parse(fs.readFileSync(file, "utf8"))
if (!Array.isArray(store.users) || store.users.length !== 9) throw new Error("unexpected_user_count")

const byId = new Map(store.users.map((user) => [user.id, user]))
const requireUser = (id) => {
  const user = byId.get(id)
  if (!user) throw new Error(`missing_user:${id}`)
  return user
}
const canonical = requireUser(canonicalId)
const staleIdentity = requireUser(staleIdentityId)
const sharedDuplicate = requireUser(sharedDuplicateId)
const testUser = requireUser(testUserId)
const probeUser = requireUser(probeUserId)
const normalizedEmail = String(canonical.email || "").trim().toLowerCase()
if (normalizedEmail !== "2293237813@qq.com" || !Array.isArray(canonical.roles) || !canonical.roles.includes("superadmin")) throw new Error("canonical_user_mismatch")
if (String(staleIdentity.email || "").trim().toLowerCase() !== normalizedEmail || String(sharedDuplicate.email || "").trim().toLowerCase() !== normalizedEmail) throw new Error("duplicate_email_mismatch")
if (!canonical.supertokensUserId || canonical.supertokensUserId !== sharedDuplicate.supertokensUserId) throw new Error("shared_identity_mismatch")
if (String(testUser.email || "").trim().toLowerCase() !== "testuser@starmc.local" || String(probeUser.email || "").trim().toLowerCase() !== "testuser@starmc.local" || !probeUser.supertokensUserId) throw new Error("test_user_mismatch")

initSuperTokens()
const probeIdentities = await supertokens.listUsersByAccountInfo("public", { email: probeUser.email })
if (!Array.isArray(probeIdentities) || !probeIdentities.some((user) => user.id === probeUser.supertokensUserId)) throw new Error("probe_credential_missing")

const duplicateIds = new Set([staleIdentityId, sharedDuplicateId])
const removedIds = new Set([testUserId, probeUserId, ...duplicateIds])
const auditLogs = Array.isArray(store.securityCenter?.auditLogs) ? store.securityCenter.auditLogs : []
const checkIns = Array.isArray(store.checkInLogs) ? store.checkInLogs : []
const plan = {
  mode: process.env.CLEANUP_MODE,
  usersBefore: store.users.length,
  usersAfter: store.users.length - removedIds.size,
  keptUserId: canonicalId,
  mergedDuplicateIds: [...duplicateIds],
  removedTestUserIds: [testUserId, probeUserId],
  migratedAuditReferences: auditLogs.filter((entry) => duplicateIds.has(String(entry?.userId || ""))).length,
  migratedCheckInReferences: checkIns.filter((entry) => duplicateIds.has(String(entry?.userId || ""))).length,
  removedTestAuditEntries: auditLogs.filter((entry) => [testUserId, probeUserId].includes(String(entry?.userId || ""))).length,
}
if (process.env.CLEANUP_MODE !== "apply") {
  console.log(JSON.stringify(plan))
  process.exit(0)
}

const credentialDeletion = await supertokens.deleteUser(probeUser.supertokensUserId)
if (credentialDeletion.status !== "OK") throw new Error(`probe_credential_delete_failed:${credentialDeletion.status || "unknown"}`)

const remap = (value, topLevelKey = "") => {
  if (typeof value === "string") return duplicateIds.has(value) ? canonicalId : value
  if (Array.isArray(value)) return value.map((entry) => remap(entry, topLevelKey))
  if (!value || typeof value !== "object") return value
  const copy = {}
  for (const [key, entry] of Object.entries(value)) {
    copy[key] = topLevelKey === "users" ? entry : remap(entry, key)
  }
  return copy
}
const migrated = remap(store)
if (migrated.securityCenter) migrated.securityCenter.auditLogs = auditLogs.filter((entry) => ![testUserId, probeUserId].includes(String(entry?.userId || "")))
migrated.users = migrated.users.filter((user) => !removedIds.has(user.id))
if (migrated.users.length !== 5 || migrated.users.filter((user) => String(user.email || "").trim().toLowerCase() === normalizedEmail).length !== 1) throw new Error("post_cleanup_invariant_failed")

const tmp = `${file}.cleanup-${process.pid}-${crypto.randomUUID()}`
fs.writeFileSync(tmp, JSON.stringify(migrated, null, 2), "utf8")
fs.renameSync(tmp, file)
console.log(JSON.stringify({ ...plan, backup: process.env.BACKUP_PATH }))
'@
$nodeBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($nodeSource))

$remoteScript = @'
set -euo pipefail
mode="$1"
root="/www/wwwroot/starmc-api"
store="$root/data/store.json"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
backup="$root/data/backups/store-before-user-cleanup-$stamp.json"

test -f "$store"
if [ "$mode" = "apply" ]; then
  cp -p "$store" "$backup"
  pm2 stop starmc-api >/dev/null
  trap "pm2 start starmc-api --update-env >/dev/null 2>&1 || true" EXIT
fi

cd "$root"
set -a
. ./.env.generated
set +a
export BACKUP_PATH="$backup"
export CLEANUP_MODE="$mode"
node_file="$root/.user-cleanup-$stamp.mjs"
printf "%s" "$2" | base64 -d > "$node_file"
node "$node_file"
rm -f "$node_file"

if [ "$mode" = "apply" ]; then
  pm2 start starmc-api --update-env >/dev/null
  pm2 save >/dev/null
  api_ready=false
  for i in $(seq 1 30); do
    if curl -fsS http://127.0.0.1:8787/api/health >/dev/null 2>&1; then
      api_ready=true
      break
    fi
    sleep 1
  done
  if [ "$api_ready" != "true" ]; then
    echo "starmc-api did not become ready after cleanup" >&2
    exit 1
  fi
  trap - EXIT
  curl -fsS http://127.0.0.1:8787/api/health
  printf "\nstore-backup=%s\n" "$backup"
fi
'@

$remoteScriptBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($remoteScript -replace "`r`n", "`n")))
$remoteCommand = @'
set -eu
mode="__MODE__"
nodeBase64="__NODE_BASE64__"
runner="$(mktemp /tmp/starmc-user-cleanup.XXXXXX)"
trap 'rm -f "$runner"' EXIT
printf "%s" "__REMOTE_SCRIPT__" | base64 -d > "$runner"
bash "$runner" "$mode" "$nodeBase64"
'@
$remoteCommand = $remoteCommand.Replace('__MODE__', $mode)
$remoteCommand = $remoteCommand.Replace('__NODE_BASE64__', $nodeBase64)
$remoteCommand = $remoteCommand.Replace('__REMOTE_SCRIPT__', $remoteScriptBase64)
$remoteCommand = $remoteCommand -replace "`r`n", "`n"

& $ssh @options $target $remoteCommand
if ($LASTEXITCODE -ne 0) { throw "用户清理 $mode 失败" }
