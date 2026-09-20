import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

test('backend-only deployment preserves the frontend and verifies the email gateway', () => {
  const source = readFileSync(new URL('./deploy-backend.ps1', import.meta.url), 'utf8')

  assert.match(source, /starmc-api\.rollback-backend-/)
  assert.match(source, /emailChallenge\.js/)
  assert.match(source, /npm ci --omit=dev/)
  assert.match(source, /pm2 restart starmc-api --update-env/)
  assert.match(source, /api\/health/)
  assert.match(source, /seq 1 90/)
  assert.match(source, /email-challenge\/send/)
  assert.match(source, /UnauthorizedStatus/)
  assert.match(source, /401\|403/)
  assert.doesNotMatch(source, /star-web\.top(?:'|")?\s*\)/)
})

test('backend deployment bypasses local SSH proxy configuration', () => {
  const source = readFileSync(new URL('./deploy-backend.ps1', import.meta.url), 'utf8')

  assert.match(source, /'-F', 'none'/)
  assert.match(source, /'ProxyCommand=none'/)
  assert.match(source, /'ProxyJump=none'/)
})
