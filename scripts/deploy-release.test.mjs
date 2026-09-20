import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { existsSync, readFileSync } from 'node:fs'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

test('release deploy script verifies core readiness without requiring optional integrations', () => {
  const path = new URL('./deploy-release.ps1', import.meta.url)
  assert.equal(existsSync(path), true)
  const bytes = readFileSync(path)
  assert.deepEqual([...bytes.subarray(0, 3)], [0xef, 0xbb, 0xbf])
  const source = bytes.toString('utf8')
  assert.match(source, /\[Parameter\(Mandatory = \$true\)\]\s*\[string\]\$IdentityFile/)
  assert.match(source, /\[string\]\$ProxyJump = ''/)
  assert.match(source, /'-F', 'none'/)
  assert.match(source, /'ProxyCommand=none'/)
  assert.match(source, /'ProxyJump=none'/)
  assert.match(source, /'BindAddress=192\.168\.0\.100'/)
  assert.match(source, /ProxyJump=\$ProxyJump/)
  assert.match(source, /star-web\.top\.rollback-/)
  assert.match(source, /starmc-api\.rollback-/)
  assert.match(source, /pm2 start npm --name starmc-api[^\n]+-- start/)
  assert.match(source, /npm run doctor:simple --silent/)
  assert.match(source, /json\?\.ok !== true/)
  assert.doesNotMatch(source, /json\?\.pluginBridge\?\.networkStatusHealthy !== true/)
  assert.doesNotMatch(source, /json\?\.coreReachable !== true/)
  assert.doesNotMatch(source, /json\?\.auth\?\.supertokensConfigured !== true/)
  assert.match(source, /createHash\('sha256'\)/)
  assert.match(source, /trap 'restore' ERR/)
  assert.match(source, /test -f "\$stage\/backend\/docs\/openapi\/authx\.yaml"/)
  assert.match(source, /test -f "\$stage\/backend\/src\/modules\/plugin-gateway\/emailChallenge\.js"/)
  assert.match(source, /cp -a "\$api_root\/docs" "\$api_backup\/docs"/)
  assert.match(source, /cp -a "\$api_backup\/docs" "\$api_root\/docs"/)
  assert.match(source, /mv "\$stage\/backend\/docs" "\$api_root\/docs"/)
  assert.match(source, /\/api\/health/)
  assert.match(source, /ss -ltnp \| grep 8787/)
  assert.match(source, /\/www\/server\/nginx\/sbin\/nginx -t -c \/www\/server\/nginx\/conf\/nginx\.conf/)
  assert.match(source, /curl -fsS[^\n]+https:\/\/star-web\.top\/favicon\.ico/)
  assert.match(source, /\$remoteScript\s*=\s*\$remoteScript\s*-replace/)
  assert.match(source, /function Invoke-CommandWithRetry/)
  assert.match(source, /Invoke-CommandWithRetry -Label '远端回滚副本打包'/)
  assert.match(source, /Invoke-CommandWithRetry -Label '回滚副本下载'/)
  assert.match(source, /Invoke-CommandWithRetry -Label '服务器回滚副本清理'/)
  assert.doesNotMatch(source, /systemctl (?:reload|restart) nginx/)
})

test('skin texture routing leaves SPA detail links reachable', () => {
  const path = new URL('../docs/nginx/star-web-skins.conf', import.meta.url)
  assert.equal(existsSync(path), true)
  const source = readFileSync(path, 'utf8')

  assert.match(source, /location ~ \^\/skins\/\(\?<skin_asset>\[\^\/\]\+\\\.png\)\$/)
  assert.match(source, /alias \/www\/wwwroot\/starmc-api\/public\/skins\/\$skin_asset;/)
  assert.doesNotMatch(source, /location \/skins\//)
})

test('release deploy script binds defaults under Windows PowerShell 5.1', () => {
  if (process.platform !== 'win32') return

  const scriptPath = fileURLToPath(new URL('./deploy-release.ps1', import.meta.url))
  assert.throws(
    () => execFileSync('powershell.exe', [
      '-NoProfile',
      '-File',
      scriptPath,
      '-IdentityFile',
      'C:\\starmc-missing-deploy-key'
    ], { encoding: 'utf8', stdio: 'pipe' }),
    (error) => {
      const output = `${error.stdout || ''}${error.stderr || ''}`
      return output.includes('SSH 身份文件') && !output.includes('Join-Path')
    }
  )
})
