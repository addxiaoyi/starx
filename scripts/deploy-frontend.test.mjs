import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

test('frontend deploy keeps previous hashed assets for stale clients', () => {
  const source = readFileSync(new URL('./deploy-frontend.ps1', import.meta.url), 'utf8')

  assert.match(source, /cp -an "\$root\/assets\/\." "\$stage\/assets\/"/)
  assert.match(source, /test -d "\$root\/assets"/)
  assert.match(source, /test -s "\$stage\/assets\/\$previous_entry"/)
})

test('frontend deployment bypasses local SSH proxy configuration by default', () => {
  const source = readFileSync(new URL('./deploy-frontend.ps1', import.meta.url), 'utf8')

  assert.match(source, /'-F', 'none'/)
  assert.match(source, /'ProxyCommand=none'/)
  assert.match(source, /'ProxyJump=none'/)
})

test('frontend deployment uses the system OpenSSH suite that accepts the configured key', () => {
  const source = readFileSync(new URL('./deploy-frontend.ps1', import.meta.url), 'utf8')

  assert.match(source, /Get-Command ssh\.exe -CommandType Application/)
  assert.doesNotMatch(source, /STARX_SSH_EXECUTABLE/)
})

test('frontend deployment builds cleanup commands without nested PowerShell quote escapes', () => {
  const source = readFileSync(new URL('./deploy-frontend.ps1', import.meta.url), 'utf8')

  assert.match(source, /\$cleanup = @'/)
  assert.match(source, /\.Replace\('__BACKUP__'/)
  assert.match(source, /\.Replace\('__ARCHIVE__'/)
})
