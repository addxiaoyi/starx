import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

test('StarX website tunnel binds only on the remote loopback and reconnects', () => {
  const source = readFileSync(new URL('./start-starx-website-tunnel.ps1', import.meta.url), 'utf8')
  assert.match(source, /127\.0\.0\.1:\$\{RemotePort\}:127\.0\.0\.1:\$\{LocalPort\}/)
  assert.match(source, /ServerAliveInterval=20/)
  assert.match(source, /while \(\$true\)/)
  assert.match(source, /Global\\StarXWebsiteTunnel/)
  assert.match(source, /WaitOne\(0\)/)
  assert.match(source, /already running/)
  assert.match(source, /ReleaseMutex\(\)/)
  assert.doesNotMatch(source, /GatewayPorts=yes|0\.0\.0\.0:\$RemotePort/)
})
