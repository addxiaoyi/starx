#!/usr/bin/env node
/**
 * Generate portable frontend env templates for external SPA repos.
 *
 * Usage:
 *   node tools/starmc-frontend-env.mjs [--write]
 */
import fs from 'node:fs'
import {
  PATHS,
  buildBackendEnv,
  buildConfigView,
  buildFrontendEnvs,
  frontendEnvHeader,
  loadSimpleConfig,
  parseEnvFile,
  portableFrontendEnvPaths,
  writeEnvFile,
} from './starmc-config-lib.mjs'

const write = process.argv.includes('--write')

function main() {
  const simple = loadSimpleConfig()
  const view = buildConfigView(simple)
  const existingBackend = parseEnvFile(PATHS.backendGenerated)
  const backendEnv = buildBackendEnv(view, simple, existingBackend)
  const frontendEnvs = buildFrontendEnvs(view, backendEnv)
  const paths = portableFrontendEnvPaths()

  const targets = [
    {
      label: 'frontend shared env',
      path: paths.shared,
      values: frontendEnvs.shared,
      header: frontendEnvHeader(view.profile, 'frontend shared env (portable)'),
    },
    {
      label: 'frontend development env',
      path: paths.development,
      values: frontendEnvs.development,
      header: frontendEnvHeader(view.profile, 'frontend development env (portable)'),
    },
    {
      label: 'frontend production env',
      path: paths.production,
      values: frontendEnvs.production,
      header: frontendEnvHeader(view.profile, 'frontend production env (portable)'),
    },
  ]

  if (!write) {
    console.log('[frontend-env] dry-run (pass --write to generate)')
    for (const t of targets) {
      console.log(`  would write ${t.path}`)
    }
    return
  }

  fs.mkdirSync(PATHS.frontendEnvPortableDir, { recursive: true })
  for (const t of targets) {
    writeEnvFile(t.path, t.values, { headerLines: t.header })
    console.log(`[frontend-env] wrote ${t.path}`)
  }
  console.log('[frontend-env] 对接说明见 docs/FRONTEND_INTEGRATION.md')
}

main()
