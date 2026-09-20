#!/usr/bin/env node
/**
 * Generate module env files from tools/starmc-simple-config.env
 *
 * Usage:
 *   node tools/starmc-config-wizard.mjs --write
 */
import {
  PATHS,
  REPO_ROOT,
  buildBackendEnv,
  buildConfigView,
  buildFrontendEnvs,
  buildVelocityEnv,
  envHeader,
  velocityEnvHeader,
  frontendEnvHeader,
  frontendEnvPaths,
  loadSimpleConfig,
  parseEnvFile,
  resolveFrontendDir,
  writeEnvFile,
} from './starmc-config-lib.mjs'

const write = process.argv.includes('--write')
const backendOnly =
  process.argv.includes('--backend-only') ||
  process.env.STARMC_CONFIG_BACKEND_ONLY === '1'

function main() {
  const simple = loadSimpleConfig()
  const view = buildConfigView(simple)
  const existingBackend = parseEnvFile(PATHS.backendGenerated)
  const backendEnv = buildBackendEnv(view, simple, existingBackend)
  const velocityEnv = buildVelocityEnv(view, backendEnv)
  const frontendDir = backendOnly ? null : resolveFrontendDir()

  /** @type {{ label: string, path: string, values: Record<string, string>, header: string[] }[]} */
  const targets = [
    {
      label: 'backend runtime env',
      path: PATHS.backendGenerated,
      values: backendEnv,
      header: envHeader(PATHS.simpleConfig, view.profile, 'backend runtime env'),
    },
    {
      label: 'velocity probe env',
      path: PATHS.velocityGenerated,
      values: velocityEnv,
      header: velocityEnvHeader(PATHS.simpleConfig, view.profile),
    },
  ]

  if (!backendOnly && frontendDir) {
    const frontendEnvs = buildFrontendEnvs(view, backendEnv)
    const fePaths = frontendEnvPaths(frontendDir)
    targets.push(
      {
        label: 'vite shared env',
        path: fePaths.shared,
        values: frontendEnvs.shared,
        header: frontendEnvHeader(view.profile, 'vite shared env'),
      },
      {
        label: 'vite development env',
        path: fePaths.development,
        values: frontendEnvs.development,
        header: frontendEnvHeader(view.profile, 'vite development env'),
      },
      {
        label: 'vite production env',
        path: fePaths.production,
        values: frontendEnvs.production,
        header: frontendEnvHeader(view.profile, 'vite production env'),
      }
    )
  }

  console.log(`[config-wizard] profile=${view.profile} repo=${REPO_ROOT}`)
  console.log(`[config-wizard] publicSite=${view.publicSite} publicApi=${view.publicApi}`)
  if (backendOnly) {
    console.log('[config-wizard] backend-only mode (skipped frontend env)')
  } else if (!frontendDir) {
    console.warn('[config-wizard] frontend dir not found (skipped vite env generation)')
  } else {
    console.log(`[config-wizard] frontendDir=${frontendDir}`)
  }

  for (const target of targets) {
    const rel = target.path.replace(`${REPO_ROOT}${process.platform === 'win32' ? '\\' : '/'}`, '')
    if (write) {
      writeEnvFile(target.path, target.values, { headerLines: target.header })
      console.log(`[config-wizard] wrote ${rel}`)
    } else {
      console.log(`[config-wizard] would write ${rel} (${Object.keys(target.values).length} keys)`)
    }
  }

  if (!write) {
    console.log('[config-wizard] dry-run only; pass --write to generate files')
  }
}

main()
