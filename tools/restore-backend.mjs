#!/usr/bin/env node
/**
 * Manual restore of 重构/backend missing modules (backup lost).
 * Run: node tools/restore-backend.mjs
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const ROOT = path.resolve(__dirname, '..', '重构', 'backend')

/** @param {string} rel @param {string} content */
function write(rel, content) {
  const target = path.join(ROOT, rel)
  fs.mkdirSync(path.dirname(target), { recursive: true })
  if (fs.existsSync(target)) {
    const cur = fs.readFileSync(target, 'utf8')
    if (cur === content) {
      console.log('skip', rel)
      return
    }
  }
  fs.writeFileSync(target, content, 'utf8')
  console.log('wrote', rel)
}

const files = {}

files['package.json'] = JSON.stringify({
  name: 'starmc-backend',
  private: true,
  version: '0.3.0',
  type: 'module',
  engines: { node: '>=20.19.0' },
  scripts: {
    dev: 'node --watch src/server.js',
    'dev:simple': 'node src/envLayering.js --run src/server.js',
    start: 'node src/envLayering.js --run src/server.js',
    'start:simple': 'node src/envLayering.js --run src/server.js',
    lint: 'eslint src tests scripts --max-warnings 0',
    test: 'node --test tests/**/*.test.js tests/**/*.spec.js tests/unit/**/*.js',
    'test:modules': 'node --test tests/*-module-smoke.test.js tests/module-routes-register-smoke.test.js',
    verify: 'npm run lint && npm test',
    'doctor:simple': 'node src/envLayering.js --run scripts/doctor-simple.mjs',
    'probe:bridge': 'node scripts/check-plugin-bridge.mjs',
    'db:migrate': 'node src/envLayering.js --run scripts/db-migrate.mjs',
  },
  dependencies: {
    cors: '^2.8.5',
    express: '^4.21.2',
    'express-rate-limit': '^7.5.0',
    helmet: '^8.0.0',
    multer: '^1.4.5-lts.2',
    nodemailer: '^6.10.0',
    otpauth: '^9.4.0',
    pg: '^8.13.3',
    'supertokens-node': '^20.1.6',
  },
  devDependencies: {
    eslint: '^9.22.0',
  },
}, null, 2) + '\n'

files['eslint.config.js'] = `export default [
  { ignores: ['data/**', 'node_modules/**'] },
  {
    files: ['**/*.js', '**/*.mjs'],
    languageOptions: { ecmaVersion: 2022, sourceType: 'module' },
    rules: {
      'no-unused-vars': ['warn', { argsIgnorePattern: '^_' }],
    },
  },
]\n`

files['src/load-env.js'] = `import { applyProjectEnvToProcess } from './envLayering.js'
applyProjectEnvToProcess({ mode: process.env.STARMC_ENV_LAYER || 'simple' })
`

files['src/envLayering.js'] = `import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawn } from 'node:child_process'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const backendRoot = path.resolve(__dirname, '..')

function parseEnvFile(filePath) {
  const out = {}
  if (!fs.existsSync(filePath)) return out
  for (const raw of fs.readFileSync(filePath, 'utf8').split(/\\r?\\n/)) {
    const line = raw.trim()
    if (!line || line.startsWith('#')) continue
    const idx = line.indexOf('=')
    if (idx <= 0) continue
    const key = line.slice(0, idx).trim()
    let value = line.slice(idx + 1).trim()
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1)
    }
    if (key) out[key] = value
  }
  return out
}

export function applyProjectEnvToProcess({ mode = 'simple' } = {}) {
  const generated = path.join(backendRoot, '.env.generated')
  const overlay = path.join(backendRoot, '.env')
  const merged = { ...parseEnvFile(generated) }
  if (mode === 'overlay' && fs.existsSync(overlay)) {
    Object.assign(merged, parseEnvFile(overlay))
  }
  for (const [k, v] of Object.entries(merged)) {
    if (process.env[k] == null || process.env[k] === '') process.env[k] = v
  }
}

const runIdx = process.argv.indexOf('--run')
if (runIdx >= 0) {
  applyProjectEnvToProcess({ mode: 'simple' })
  const target = process.argv[runIdx + 1]
  const rest = process.argv.slice(runIdx + 2)
  const child = spawn(process.execPath, [path.resolve(backendRoot, target), ...rest], {
    stdio: 'inherit',
    env: process.env,
    cwd: backendRoot,
  })
  child.on('exit', (code) => process.exit(code ?? 1))
}
`

// Continue in next part - config.js is large