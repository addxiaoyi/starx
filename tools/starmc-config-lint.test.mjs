import assert from 'node:assert/strict'
import test from 'node:test'
import { lintSimpleConfig } from './starmc-config-lint.mjs'

function productionConfig(overrides = {}) {
  return {
    STARMC_PROFILE: 'production',
    STARMC_PUBLIC_SITE_URL: 'https://star-web.top',
    STARMC_VLA_API_KEY: 'test-vla-api-key',
    STARMC_VLA_HMAC_SECRET: 'test-vla-hmac',
    STARMC_VLA_WEBHOOK_SECRET: 'test-vla-webhook',
    STARMC_PLUGIN_REFRESH_SECRET: 'test-plugin-refresh',
    STARMC_DEV_ADMIN_SECRET: 'test-admin-secret',
    STARMC_OAUTH_STATE_SECRET: 'test-oauth-state',
    STARMC_SMTP_HOST: 'smtpdm.aliyun.com',
    STARMC_SMTP_PORT: '465',
    STARMC_SMTP_SECURE: 'true',
    STARMC_SMTP_USER: 'noreply@notify.star-mc.top',
    STARMC_SMTP_PASSWORD: 'test-smtp-password',
    STARMC_SMTP_FROM_EMAIL: 'noreply@notify.star-mc.top',
    STARMC_SMTP_FROM_NAME: 'StarMC',
    STARMC_TRUST_PROXY: 'true',
    ...overrides,
  }
}

test('accepts complete DirectMail production SMTP settings', () => {
  const smtpIssues = lintSimpleConfig(productionConfig(), { existingBackend: {} })
    .filter((issue) => issue.code.startsWith('prod_smtp'))

  assert.deepEqual(smtpIssues, [])
})

test('rejects Ethereal as a production SMTP host', () => {
  const issues = lintSimpleConfig(
    productionConfig({ STARMC_SMTP_HOST: 'smtp.ethereal.email' }),
    { existingBackend: {} }
  )

  assert.equal(issues.some((issue) => issue.code === 'prod_smtp_sandbox'), true)
})

test('rejects incomplete production SMTP settings', () => {
  const issues = lintSimpleConfig(
    productionConfig({ STARMC_SMTP_PASSWORD: '' }),
    { existingBackend: {} }
  )

  assert.equal(issues.some((issue) => issue.code === 'prod_smtp_missing'), true)
  assert.equal(issues.some((issue) => /test-smtp-password/.test(issue.message)), false)
})
