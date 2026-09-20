#!/usr/bin/env node
/**
 * Validate StarMC simple config and generated env files.
 *
 * Usage:
 *   node tools/starmc-config-lint.mjs [--backend-only]
 */
import fs from 'node:fs'
import { fileURLToPath } from 'node:url'
import {
  PATHS,
  buildBackendEnv,
  buildConfigView,
  loadSimpleConfig,
  parseEnvFile,
  resolveFrontendDir,
} from './starmc-config-lib.mjs'
import { isSandboxSmtpHost } from '../重构/backend/src/auth/supertokensEmailDelivery.js'

const backendOnly =
  process.argv.includes('--backend-only') || process.env.STARMC_CONFIG_BACKEND_ONLY === '1'

/** @typedef {{ level: 'error' | 'warn', code: string, message: string }} Issue */

/** @returns {Issue[]} */
export function lintSimpleConfig(
  simple,
  { existingBackend = parseEnvFile(PATHS.backendGenerated) } = {}
) {
  /** @type {Issue[]} */
  const issues = []
  const profile = String(simple.STARMC_PROFILE || 'local').trim().toLowerCase()
  if (!['local', 'production'].includes(profile)) {
    issues.push({
      level: 'error',
      code: 'bad_profile',
      message: `STARMC_PROFILE 必须是 local 或 production，当前: ${profile || '(empty)'}`,
    })
  }

  const view = buildConfigView(simple)
  const backendEnv = buildBackendEnv(view, simple, existingBackend)

  if (view.isProd) {
    if (!String(simple.STARMC_PUBLIC_SITE_URL || '').trim()) {
      issues.push({
        level: 'error',
        code: 'prod_site_url',
        message: 'production 必须设置 STARMC_PUBLIC_SITE_URL（如 https://star-web.top）',
      })
    }
    for (const [field, map] of Object.entries(view.secrets)) {
      const val = String(backendEnv[map.backend] || '').trim()
      if (!val) {
        issues.push({
          level: 'error',
          code: 'prod_secret',
          message: `production 必须设置 ${map.simple}（勿用 auto）`,
        })
      }
    }
    const requiredSmtp = [
      'SUPERTOKENS_SMTP_HOST',
      'SUPERTOKENS_SMTP_PORT',
      'SUPERTOKENS_SMTP_USER',
      'SUPERTOKENS_SMTP_PASSWORD',
      'SUPERTOKENS_SMTP_FROM_EMAIL',
    ]
    const missingSmtp = requiredSmtp.filter(
      (key) => !String(backendEnv[key] || '').trim()
    )
    if (missingSmtp.length > 0) {
      issues.push({
        level: 'error',
        code: 'prod_smtp_missing',
        message: `production SMTP 缺少配置项: ${missingSmtp.join(', ')}`,
      })
    } else if (isSandboxSmtpHost(backendEnv.SUPERTOKENS_SMTP_HOST)) {
      issues.push({
        level: 'error',
        code: 'prod_smtp_sandbox',
        message: 'production 禁止使用测试邮箱服务，请配置真实 SMTP 提供商',
      })
    }
    if (!view.trustProxy) {
      issues.push({
        level: 'warn',
        code: 'prod_trust_proxy',
        message: 'production 建议 STARMC_TRUST_PROXY=true（反代后获取真实 IP）',
      })
    }
  }

  if (view.features.inviteCodesRequired && !String(view.features.inviteCodes || '').trim()) {
    issues.push({
      level: 'error',
      code: 'invite_codes_empty',
      message: 'STARMC_INVITE_CODES_REQUIRED=true 时 STARMC_INVITE_CODES 不能为空',
    })
  }

  if (!fs.existsSync(PATHS.simpleConfig)) {
    issues.push({
      level: 'error',
      code: 'missing_simple',
      message: `缺少配置源 ${PATHS.simpleConfig}`,
    })
  }

  if (!fs.existsSync(PATHS.backendGenerated)) {
    issues.push({
      level: 'warn',
      code: 'missing_backend_env',
      message: '未找到 重构/backend/.env.generated，请运行 npm run config:doctor:simple',
    })
  }

  if (!fs.existsSync(PATHS.velocityGenerated)) {
    issues.push({
      level: 'warn',
      code: 'missing_velocity_env',
      message: '未找到 velocity-test/.env.velocity.generated',
    })
  }

  if (!backendOnly) {
    const frontendDir = resolveFrontendDir()
    if (!frontendDir) {
      issues.push({
        level: 'warn',
        code: 'frontend_missing',
        message: '未找到 Vite 前端目录（重构/starmc）',
      })
    }
  }

  return issues
}

/** @param {ReturnType<typeof buildConfigView>} view */
export function printFeatureSummary(view) {
  const rows = [
    ['reviewEntryEnabled', view.features.reviewEntryEnabled],
    ['inviteRequiredForMcLink', view.features.inviteCodesRequired],
    ['requireTotpForMcLink', view.features.requireTotpForMcLink],
    ['mcGamePasswordReset', view.features.mcGamePasswordResetEnabled],
    ['mojangSkinSync', view.features.mojangSkinSyncEnabled],
    ['telemetryEnabled', view.features.telemetryEnabled],
    ['skinBridgePublicProfile', view.features.skinBridgePublicProfile],
    ['vlaNotifyOnSkinChange', view.features.vlaNotifyOnSkinChange],
  ]
  console.log('\n[config] 功能开关摘要')
  console.log(`  profile=${view.profile}  site=${view.publicSite}  api=${view.publicApi}`)
  for (const [key, val] of rows) {
    console.log(`  ${key}=${val ? 'true' : 'false'}`)
  }
  if (view.features.inviteCodesRequired && view.features.inviteCodes) {
    console.log(`  inviteCodes=${view.features.inviteCodes}`)
  }
  if (view.features.geoBlockedCountries) {
    console.log(`  geoBlockedCountries=${view.features.geoBlockedCountries}`)
  }
}

function main() {
  const simple = loadSimpleConfig()
  const view = buildConfigView(simple)
  const issues = lintSimpleConfig(simple)

  const errors = issues.filter((i) => i.level === 'error')
  const warns = issues.filter((i) => i.level === 'warn')

  for (const issue of issues) {
    const tag = issue.level === 'error' ? 'ERROR' : 'WARN'
    console.log(`[config-lint] ${tag} ${issue.code}: ${issue.message}`)
  }

  printFeatureSummary(view)

  if (errors.length > 0) {
    console.log(`\n[config-lint] failed (${errors.length} error(s), ${warns.length} warn(s))`)
    process.exit(1)
  }
  console.log(`\n[config-lint] ok (${warns.length} warn(s))`)
}

if (process.argv[1] === fileURLToPath(import.meta.url)) main()
