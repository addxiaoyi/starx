import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

test('production user cleanup runs the remote wrapper from a temporary file', () => {
  const script = readFileSync(new URL('./cleanup-production-users.ps1', import.meta.url), 'utf8')

  assert.match(script, /\$remoteScriptBase64\s*=/)
  assert.match(script, /base64 -d > "\$runner"/)
  assert.match(script, /bash "\$runner" "\$mode" "\$nodeBase64"/)
  assert.match(script, /rm -f "\$runner"/)
  assert.match(script, /GetBytes\(\(\$remoteScript\s+-replace/)
  assert.match(script, /for i in \$\(seq 1 30\); do/)
  assert.match(script, /api_ready=true/)
  assert.match(script, /curl -fsS http:\/\/127\.0\.0\.1:8787\/api\/health >\/dev\/null 2>&1/)
  assert.doesNotMatch(script, /\|\s*& \$ssh @options \$target "bash -s/)
})
