#!/usr/bin/env node
/**
 * Generate env files, validate config, print feature summary.
 *
 * Usage:
 *   node tools/starmc-config-doctor.mjs [--write] [--backend-only]
 */
import { spawnSync } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { lintSimpleConfig, printFeatureSummary } from './starmc-config-lint.mjs'
import { buildConfigView, loadSimpleConfig } from './starmc-config-lib.mjs'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const write = process.argv.includes('--write')
const backendOnly =
  process.argv.includes('--backend-only') || process.env.STARMC_CONFIG_BACKEND_ONLY === '1'

function runWizard() {
  const wizard = path.join(__dirname, 'starmc-config-wizard.mjs')
  const args = [wizard]
  if (write) args.push('--write')
  if (backendOnly) args.push('--backend-only')
  const result = spawnSync(process.execPath, args, { stdio: 'inherit' })
  if (result.status !== 0) process.exit(result.status ?? 1)
}

function main() {
  console.log(`[config-doctor] mode=${write ? 'write+lint' : 'lint-only'} backendOnly=${backendOnly}`)

  if (write) {
    runWizard()
  } else {
    console.log('[config-doctor] dry-run (pass --write to regenerate env files)')
  }

  const simple = loadSimpleConfig()
  const view = buildConfigView(simple)
  const issues = lintSimpleConfig(simple)
  const errors = issues.filter((i) => i.level === 'error')
  const warns = issues.filter((i) => i.level === 'warn')

  for (const issue of issues) {
    const tag = issue.level === 'error' ? 'ERROR' : 'WARN'
    console.log(`[config-doctor] ${tag} ${issue.code}: ${issue.message}`)
  }

  printFeatureSummary(view)

  if (errors.length > 0) {
    console.log(`\n[config-doctor] failed (${errors.length} error(s))`)
    process.exit(1)
  }

  console.log(`\n[config-doctor] ok (${warns.length} warn(s))`)
  if (view.isProd) {
    console.log('[config-doctor] 生产检查: npm run backend:doctor && npm run plugin:probe')
  }
}

main()
