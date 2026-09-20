import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
export const REPO_ROOT = path.resolve(__dirname, '..')

export const PATHS = {
  simpleConfig: path.join(REPO_ROOT, 'tools', 'starmc-simple-config.env'),
  simpleConfigLocal: path.join(REPO_ROOT, 'tools', 'starmc-simple-config.local.env'),
  backendGenerated: path.join(REPO_ROOT, '重构', 'backend', '.env.generated'),
  velocityGenerated: path.join(REPO_ROOT, 'velocity-test', '.env.velocity.generated'),
  frontendEnvPortableDir: path.join(REPO_ROOT, 'docs', 'frontend-env'),
}

/** @returns {string|null} */
export function resolveFrontendDir() {
  const candidates = [
    path.join(REPO_ROOT, '重构', 'starmc'),
    path.join(REPO_ROOT, '重构'),
    path.join(REPO_ROOT, '重构-前端备份-20260707-134037'),
  ]
  for (const dir of candidates) {
    if (fs.existsSync(path.join(dir, 'package.json')) && fs.existsSync(path.join(dir, 'vite.config.ts'))) {
      return dir
    }
  }
  return null
}

export function frontendEnvPaths(frontendDir) {
  return {
    shared: path.join(frontendDir, '.env.starmc.generated'),
    development: path.join(frontendDir, '.env.starmc.development.generated'),
    production: path.join(frontendDir, '.env.starmc.production.generated'),
  }
}

export function portableFrontendEnvPaths() {
  const dir = PATHS.frontendEnvPortableDir
  return {
    shared: path.join(dir, '.env.starmc.shared.generated'),
    development: path.join(dir, '.env.starmc.development.generated'),
    production: path.join(dir, '.env.starmc.production.generated'),
  }
}

export function frontendEnvHeader(profile, targetLabel) {
  return [
    ...envHeader(PATHS.simpleConfig, profile, targetLabel),
    '# 新前端对接见 docs/FRONTEND_INTEGRATION.md',
    '# 开发：Vite 代理 /api /auth → VITE_LOCAL_API_TARGET',
    '# 生产同域：留空 VITE_API_BASE，走 Nginx 反代',
    '# 生产跨域：VITE_API_BASE = PUBLIC_API_BASE_URL，fetch 需 credentials include',
  ]
}

/** @param {string} filePath */
export function parseEnvFile(filePath) {
  /** @type {Record<string, string>} */
  const out = {}
  if (!fs.existsSync(filePath)) return out
  for (const rawLine of fs.readFileSync(filePath, 'utf8').split(/\r?\n/)) {
    const line = rawLine.trim()
    if (!line || line.startsWith('#')) continue
    const idx = line.indexOf('=')
    if (idx <= 0) continue
    const key = line.slice(0, idx).trim()
    let value = line.slice(idx + 1).trim()
    if (
      value.length >= 2 &&
      ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'")))
    ) {
      value = value.slice(1, -1)
    }
    if (key) out[key] = value
  }
  return out
}

/**
 * @param {string} filePath
 * @param {Record<string, string>} values
 * @param {{ headerLines?: string[] }} [opts]
 */
export function writeEnvFile(filePath, values, opts = {}) {
  const headerLines = opts.headerLines || []
  const lines = [...headerLines, '']
  for (const [key, value] of Object.entries(values)) {
    lines.push(`${key}="${String(value ?? '').replace(/"/g, '\\"')}"`)
  }
  fs.mkdirSync(path.dirname(filePath), { recursive: true })
  fs.writeFileSync(filePath, `${lines.join('\n').trimEnd()}\n`, 'utf8')
}

export function loadSimpleConfig() {
  const merged = { ...parseEnvFile(PATHS.simpleConfig) }
  if (fs.existsSync(PATHS.simpleConfigLocal)) {
    Object.assign(merged, parseEnvFile(PATHS.simpleConfigLocal))
  }
  return merged
}

export function truthy(val, defaultWhenEmpty = false) {
  const s = String(val ?? '').trim().toLowerCase()
  if (!s) return defaultWhenEmpty
  if (/^(0|false|no|off)$/.test(s)) return false
  if (/^(1|true|yes|on|enabled)$/.test(s)) return true
  return defaultWhenEmpty
}

export function randomSecret(bytes = 32) {
  return crypto.randomBytes(bytes).toString('hex')
}

/**
 * Resolve secret: simple config value > existing generated > auto-generate (local only).
 * @param {object} args
 * @param {string} args.simpleKey
 * @param {string} [args.existingKey]
 * @param {Record<string, string>} args.simple
 * @param {Record<string, string>} args.existing
 * @param {string} args.profile
 * @param {boolean} [args.allowAuto]
 */
export function resolveSecret({ simpleKey, existingKey, simple, existing, profile, allowAuto = true }) {
  const raw = String(simple[simpleKey] ?? '').trim()
  if (raw && raw.toLowerCase() !== 'auto') return raw
  const prev = String(existing[existingKey || simpleKey] ?? '').trim()
  if (prev) return prev
  if (allowAuto && profile !== 'production') return randomSecret()
  return ''
}

function portOr(raw, fallback) {
  const n = Number(String(raw ?? '').trim())
  if (!Number.isFinite(n) || n < 1 || n > 65535) return fallback
  return Math.floor(n)
}

function hostOr(raw, fallback = '127.0.0.1') {
  const h = String(raw ?? '').trim()
  return h || fallback
}

function origin(host, port, https = false) {
  const scheme = https ? 'https' : 'http'
  const isDefault = (https && port === 443) || (!https && port === 80)
  return isDefault ? `${scheme}://${host}` : `${scheme}://${host}:${port}`
}

/**
 * Build normalized runtime view from simple config keys.
 * @param {Record<string, string>} simple
 */
export function buildConfigView(simple) {
  const profile = String(simple.STARMC_PROFILE || 'local').trim().toLowerCase()
  const isProd = profile === 'production'
  const host = hostOr(simple.STARMC_HOST, '127.0.0.1')
  const frontendPort = portOr(simple.STARMC_FRONTEND_PORT, 5173)
  const backendPort = portOr(simple.STARMC_BACKEND_PORT, 8787)
  const pluginCallbackPort = portOr(simple.STARMC_PLUGIN_CALLBACK_PORT, 8788)
  const vlaHttpPort = portOr(simple.STARMC_VLA_HTTP_PORT, 8090)
  const velocityMcPort = portOr(simple.STARMC_VELOCITY_MC_PORT, 25579)
  const supertokensPort = portOr(simple.STARMC_SUPERTOKENS_PORT, 3567)

  const publicSite =
    String(simple.STARMC_PUBLIC_SITE_URL || '').trim() ||
    (isProd ? 'https://star-web.top' : origin(host, frontendPort))
  const publicApi =
    String(simple.STARMC_PUBLIC_API_URL || '').trim() ||
    (isProd ? publicSite : origin(host, backendPort))

  const corsList = String(simple.STARMC_CORS_ORIGINS || '')
    .split(/[\s,;]+/)
    .map((s) => s.trim())
    .filter(Boolean)
  const corsOrigin =
    corsList.length > 0
      ? corsList.join(',')
      : [publicSite, origin(host, 4173)].filter(Boolean).join(',')

  return {
    profile,
    isProd,
    host,
    frontendPort,
    backendPort,
    pluginCallbackPort,
    vlaHttpPort,
    velocityMcPort,
    supertokensPort,
    publicSite: publicSite.replace(/\/+$/, ''),
    publicApi: publicApi.replace(/\/+$/, ''),
    corsOrigin,
    siteName: String(simple.STARMC_SITE_NAME || 'StarMC').trim() || 'StarMC',
    trustProxy: truthy(simple.STARMC_TRUST_PROXY, isProd),
    databaseUrl: String(simple.STARMC_DATABASE_URL || '').trim(),
    supertokensUri:
      String(simple.STARMC_SUPERTOKENS_URI || '').trim() ||
      origin(host, supertokensPort),
    vlaHttpBind: hostOr(simple.STARMC_VLA_HTTP_BIND, host),
    smtp: {
      host: String(simple.STARMC_SMTP_HOST || '').trim(),
      port: String(simple.STARMC_SMTP_PORT || '587').trim(),
      user: String(simple.STARMC_SMTP_USER || '').trim(),
      password: String(simple.STARMC_SMTP_PASSWORD || '').trim(),
      fromEmail: String(simple.STARMC_SMTP_FROM_EMAIL || '').trim(),
      fromName: String(simple.STARMC_SMTP_FROM_NAME || 'StarMC').trim(),
      secure: truthy(simple.STARMC_SMTP_SECURE, false),
    },
    oauth: {
      githubClientId: String(simple.STARMC_OAUTH_GITHUB_CLIENT_ID || '').trim(),
      githubClientSecret: String(simple.STARMC_OAUTH_GITHUB_CLIENT_SECRET || '').trim(),
      githubRedirectUri:
        String(simple.STARMC_OAUTH_GITHUB_REDIRECT_URI || '').trim() ||
        `${publicSite.replace(/\/+$/, '')}/auth/callback/github`,
    },
    features: {
      reviewEntryEnabled: truthy(simple.STARMC_REVIEW_ENTRY_ENABLED, false),
      inviteCodesRequired: truthy(simple.STARMC_INVITE_CODES_REQUIRED, false),
      inviteCodes: String(simple.STARMC_INVITE_CODES || '')
        .split(/[\s,;]+/)
        .map((s) => s.trim())
        .filter(Boolean)
        .join(','),
      requireTotpForMcLink: truthy(simple.STARMC_REQUIRE_TOTP_FOR_MC_LINK, false),
      mcGamePasswordResetEnabled: truthy(simple.STARMC_MC_GAME_PASSWORD_RESET_ENABLED, true),
      mojangSkinSyncEnabled: truthy(simple.STARMC_MOJANG_SKIN_SYNC_ENABLED, true),
      telemetryEnabled: truthy(simple.STARMC_TELEMETRY_ENABLED, true),
      skinBridgePublicProfile: truthy(simple.STARMC_SKIN_BRIDGE_PUBLIC_PROFILE, true),
      vlaNotifyOnSkinChange: truthy(simple.STARMC_VLA_NOTIFY_ON_SKIN_CHANGE, true),
      geoBlockedCountries: String(simple.STARMC_GEO_BLOCKED_COUNTRIES || '')
        .split(/[\s,;]+/)
        .map((s) => s.trim().toUpperCase())
        .filter(Boolean)
        .join(','),
    },
    secrets: {
      vlaApiKey: { simple: 'STARMC_VLA_API_KEY', backend: 'AUTHX_VLA_API_KEY' },
      vlaHmacSecret: { simple: 'STARMC_VLA_HMAC_SECRET', backend: 'AUTHX_VLA_HMAC_SECRET' },
      vlaWebhookSecret: { simple: 'STARMC_VLA_WEBHOOK_SECRET', backend: 'AUTHX_VLA_WEBHOOK_SECRET' },
      pluginRefreshSecret: { simple: 'STARMC_PLUGIN_REFRESH_SECRET', backend: 'PLUGIN_REFRESH_SECRET' },
      devAdminSecret: { simple: 'STARMC_DEV_ADMIN_SECRET', backend: 'DEV_ADMIN_SECRET' },
      oauthStateSecret: { simple: 'STARMC_OAUTH_STATE_SECRET', backend: 'OAUTH_STATE_SECRET' },
    },
  }
}

/**
 * @param {ReturnType<typeof buildConfigView>} view
 * @param {Record<string, string>} simple
 * @param {Record<string, string>} existingBackend
 */
export function buildBackendEnv(view, simple, existingBackend) {
  /** @type {Record<string, string>} */
  const secrets = {}
  for (const [field, map] of Object.entries(view.secrets)) {
    secrets[field] = resolveSecret({
      simpleKey: map.simple,
      existingKey: map.backend,
      simple,
      existing: existingBackend,
      profile: view.profile,
    })
  }

  const smtp = { ...view.smtp }
  const smtpFallback = [
    ['host', 'SUPERTOKENS_SMTP_HOST', 'STARMC_SMTP_HOST'],
    ['port', 'SUPERTOKENS_SMTP_PORT', 'STARMC_SMTP_PORT'],
    ['user', 'SUPERTOKENS_SMTP_USER', 'STARMC_SMTP_USER'],
    ['password', 'SUPERTOKENS_SMTP_PASSWORD', 'STARMC_SMTP_PASSWORD'],
    ['fromEmail', 'SUPERTOKENS_SMTP_FROM_EMAIL', 'STARMC_SMTP_FROM_EMAIL'],
    ['fromName', 'SUPERTOKENS_SMTP_FROM_NAME', 'STARMC_SMTP_FROM_NAME'],
  ]
  for (const [field, backendKey] of smtpFallback) {
    if (!String(smtp[field] ?? '').trim() && String(existingBackend[backendKey] ?? '').trim()) {
      smtp[field] = existingBackend[backendKey]
    }
  }
  if (!String(smtp.fromEmail).trim() && String(smtp.user).trim()) {
    smtp.fromEmail = smtp.user
  }

  /** @type {Record<string, string>} */
  const env = {
    STARMC_PROFILE: view.profile,
    NODE_ENV: view.isProd ? 'production' : 'development',
    PORT: String(view.backendPort),
    PLUGIN_PORT: String(view.pluginCallbackPort),
    CORS_ORIGIN: view.corsOrigin,
    TRUST_PROXY: view.trustProxy ? 'true' : 'false',
    PUBLIC_SITE_ORIGIN: view.publicSite,
    PUBLIC_API_BASE_URL: view.publicApi,
    SUPERTOKENS_CONNECTION_URI: view.supertokensUri,
    SUPERTOKENS_APP_NAME: view.siteName,
    AUTH_API_DOMAIN: view.publicSite,
    AUTH_WEBSITE_DOMAIN: view.publicSite,
    AUTH_API_BASE_PATH: '/auth',
    AUTH_WEBSITE_BASE_PATH: '/auth',
    DATABASE_URL: view.databaseUrl,
    AUTHX_PLUGIN_ADAPTER: 'vla',
    AUTHX_PLUGIN_BASE: origin(view.vlaHttpBind, view.vlaHttpPort),
    AUTHX_VLA_API_KEY: secrets.vlaApiKey,
    AUTHX_VLA_HMAC_SECRET: secrets.vlaHmacSecret,
    AUTHX_VLA_WEBHOOK_SECRET: secrets.vlaWebhookSecret,
    PLUGIN_REFRESH_SECRET: secrets.pluginRefreshSecret,
    DEV_ADMIN_SECRET: secrets.devAdminSecret,
    OAUTH_STATE_SECRET: secrets.oauthStateSecret,
    OAUTH_GITHUB_CLIENT_ID: view.oauth.githubClientId,
    OAUTH_GITHUB_CLIENT_SECRET: view.oauth.githubClientSecret,
    OAUTH_GITHUB_REDIRECT_URI: view.oauth.githubRedirectUri,
    SUPERTOKENS_SMTP_HOST: smtp.host,
    SUPERTOKENS_SMTP_PORT: smtp.port,
    SUPERTOKENS_SMTP_USER: smtp.user,
    SUPERTOKENS_SMTP_PASSWORD: smtp.password,
    SUPERTOKENS_SMTP_FROM_EMAIL: smtp.fromEmail || smtp.user,
    SUPERTOKENS_SMTP_FROM_NAME: smtp.fromName,
    SUPERTOKENS_SMTP_SECURE: smtp.secure ? 'true' : 'false',
    VLA_NOTIFY_ON_SKIN_CHANGE: view.features.vlaNotifyOnSkinChange ? 'true' : 'false',
    SKIN_BRIDGE_PUBLIC_PROFILE: view.features.skinBridgePublicProfile ? 'true' : 'false',
    REVIEW_ENTRY_ENABLED: view.features.reviewEntryEnabled ? 'true' : 'false',
    INVITE_CODES_REQUIRED_FOR_MC_LINK: view.features.inviteCodesRequired ? 'true' : 'false',
    INVITE_CODES: view.features.inviteCodes,
    REQUIRE_TOTP_FOR_MC_LINK: view.features.requireTotpForMcLink ? 'true' : 'false',
    MC_GAME_PASSWORD_RESET_ENABLED: view.features.mcGamePasswordResetEnabled ? 'true' : 'false',
    MOJANG_SKIN_SYNC_ENABLED: view.features.mojangSkinSyncEnabled ? 'true' : 'false',
    TELEMETRY_ENABLED: view.features.telemetryEnabled ? 'true' : 'false',
    GEO_BLOCKED_COUNTRIES: view.features.geoBlockedCountries,
  }

  return env
}

/** @param {ReturnType<typeof buildConfigView>} view @param {Record<string, string>} backendEnv */
export function buildFrontendEnvs(view, backendEnv) {
  const shared = {
    STARMC_PROFILE: view.profile,
    VITE_SITE_NAME: view.siteName,
    VITE_PUBLIC_SITE_ORIGIN: view.publicSite,
    VITE_AUTH_API_DOMAIN: view.publicSite,
    VITE_AUTH_WEBSITE_DOMAIN: view.publicSite,
    VITE_AUTH_API_BASE_PATH: '/auth',
    VITE_SUPERTOKENS_APP_NAME: view.siteName,
    VITE_WEB_VITALS_ENDPOINT: '/api/metrics/web-vitals',
    VITE_TELEMETRY_ENABLED: view.features.telemetryEnabled ? 'true' : 'false',
    VITE_SKIN_LIBRARY_SSE_PATH: '/api/skins/library/stream',
    VITE_SKIN_REALTIME_REFRESH_MS: '15000',
  }
  const development = {
    STARMC_PROFILE: view.profile,
    VITE_LOCAL_API_TARGET: origin(view.host, view.backendPort),
    VITE_LOCAL_PLUGIN_TARGET: origin(view.host, view.pluginCallbackPort),
  }
  const production = {
    STARMC_PROFILE: view.profile,
    VITE_API_BASE: backendEnv.PUBLIC_API_BASE_URL,
  }
  return { shared, development, production }
}

/** @param {ReturnType<typeof buildConfigView>} view @param {Record<string, string>} backendEnv */
export function buildVelocityEnv(view, backendEnv) {
  const apiKey = backendEnv.AUTHX_VLA_API_KEY
  const httpBase = origin(view.vlaHttpBind, view.vlaHttpPort)
  const websiteBase = String(backendEnv.PUBLIC_API_BASE_URL || origin(view.host, view.backendPort)).replace(/\/+$/, '')
  return {
    STARMC_PROFILE: view.profile,
    VLA_HTTP_BIND: view.vlaHttpBind,
    VLA_HTTP_PORT: String(view.vlaHttpPort),
    VLA_HTTP_BASE: httpBase,
    VLA_HTTP_API_KEY: apiKey,
    VLA_API_KEY: apiKey,
    VLA_MC_HOST: view.host,
    VLA_MC_PORT: String(view.velocityMcPort),
    VLA_WEBSITE_API_BASE: websiteBase,
    VLA_WEBHOOK_URL: `${websiteBase}/api/v1/plugin/callback`,
    VLA_BIND_EMAIL_WEBHOOK_URL: `${websiteBase}/api/v1/plugin/bind-email`,
    VLA_SKIN_PROFILE_URL: `${websiteBase}/api/public/skin-profile/{username}`,
    VLA_WEBHOOK_SECRET: backendEnv.AUTHX_VLA_WEBHOOK_SECRET,
  }
}

export function velocityEnvHeader(sourcePath, profile) {
  return [
    ...envHeader(sourcePath, profile, 'velocity probe env'),
    '# 插件 ↔ 网站 URL 对照见 重构/backend/docs/PLUGIN_INTEGRATION.md',
    '# VLA_WEBHOOK_URL          → outbound-webhooks 通用事件（login_success/login_failed/verify_premium/...）',
    '# VLA_BIND_EMAIL_WEBHOOK_URL → 绑定邮箱专用回调',
    '# VLA_SKIN_PROFILE_URL     → skin.custom-profile-url（{username} 替换为游戏名）',
    '# 网站 API 默认 8787；VLA HTTP 默认 8090（VLA_HTTP_BASE）',
  ]
}

export function envHeader(sourcePath, profile, targetLabel) {
  return [
    '# generated-by=tools/starmc-config-wizard.mjs',
    `# profile=${profile}`,
    `# source=${path.relative(REPO_ROOT, sourcePath).replace(/\\/g, '/')}`,
    `# target=${targetLabel}`,
  ]
}
