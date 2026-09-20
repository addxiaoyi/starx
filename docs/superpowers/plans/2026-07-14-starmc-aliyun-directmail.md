# StarMC Alibaba Cloud DirectMail Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace production Ethereal SMTP with Alibaba Cloud DirectMail and prevent test-only SMTP services from ever reporting production-ready again.

**Architecture:** Keep SuperTokens Passwordless and its frontend protocol unchanged. Centralize sandbox-host policy in the existing SMTP helper, pass production intent explicitly from startup and readiness call sites, and make the simple-config linter reject incomplete or sandbox production SMTP before generation or deployment.

**Tech Stack:** Node.js 20, Express, SuperTokens Node 20.1.6, Nodemailer 9, Node test runner, PM2, Nginx, Alibaba Cloud DirectMail and Alibaba Cloud DNS.

**Repository note:** The aggregate workspace has invalid Git metadata, so commit steps are replaced with timestamped backups and verification checkpoints.

---

## File Map

- Modify `重构/backend/src/auth/supertokensEmailDelivery.js`: own sandbox-host classification and production SMTP validation.
- Modify `重构/backend/src/auth/supertokensInit.js`: pass explicit production intent into SMTP parsing.
- Modify `重构/backend/src/server.js`: report production-aware SMTP readiness from both auth status and public bootstrap.
- Modify `重构/backend/tests/unit/auth/supertokensEmailDelivery.test.js`: cover DirectMail mapping, sandbox policy, readiness, and secret-safe errors.
- Modify `tools/starmc-config-lint.mjs`: reject incomplete and sandbox production SMTP configuration.
- Create `tools/starmc-config-lint.test.mjs`: cover DirectMail and Ethereal production lint behavior.
- Modify `tools/starmc-simple-config.env`: document DirectMail settings without adding a credential.
- Deploy protected SMTP values to `/www/wwwroot/starmc-api/.env.generated` only after DirectMail domain verification.

### Task 1: Production SMTP Policy

**Files:**
- Modify: `重构/backend/tests/unit/auth/supertokensEmailDelivery.test.js`
- Modify: `重构/backend/src/auth/supertokensEmailDelivery.js`

- [ ] **Step 1: Add failing DirectMail and sandbox-policy tests**

Append these fixtures and tests:

```js
const directMailEnv = {
  SUPERTOKENS_SMTP_HOST: 'smtpdm.aliyun.com',
  SUPERTOKENS_SMTP_PORT: '465',
  SUPERTOKENS_SMTP_SECURE: 'true',
  SUPERTOKENS_SMTP_USER: 'noreply@notify.star-mc.top',
  SUPERTOKENS_SMTP_PASSWORD: 'directmail-test-password',
  SUPERTOKENS_SMTP_FROM_EMAIL: 'noreply@notify.star-mc.top',
  SUPERTOKENS_SMTP_FROM_NAME: 'StarMC',
}

const etherealEnv = {
  ...directMailEnv,
  SUPERTOKENS_SMTP_HOST: 'smtp.ethereal.email',
}

it('maps Alibaba Cloud DirectMail TLS settings', () => {
  const settings = getPasswordlessSmtpSettings(directMailEnv, {
    required: true,
    production: true,
  })
  assert.equal(settings.host, 'smtpdm.aliyun.com')
  assert.equal(settings.port, 465)
  assert.equal(settings.secure, true)
  assert.equal(settings.authUsername, 'noreply@notify.star-mc.top')
})

it('rejects Ethereal in production without exposing its password', () => {
  assert.throws(
    () => getPasswordlessSmtpSettings(etherealEnv, {
      required: true,
      production: true,
    }),
    (error) => {
      assert.match(error.message, /sandbox SMTP host/i)
      assert.doesNotMatch(error.message, /directmail-test-password/)
      return true
    }
  )
})

it('allows Ethereal for local email previews', () => {
  const settings = getPasswordlessSmtpSettings(etherealEnv, {
    production: false,
  })
  assert.equal(settings.host, 'smtp.ethereal.email')
})

it('reports a production sandbox transport as unavailable', () => {
  assert.equal(isSmtpConfigured(etherealEnv, { production: true }), false)
  assert.equal(isSmtpConfigured(directMailEnv, { production: true }), true)
})
```

- [ ] **Step 2: Run the focused test and verify RED**

Run from `重构/backend`:

```powershell
node --test tests/unit/auth/supertokensEmailDelivery.test.js
```

Expected: the sandbox tests fail because `production` is not enforced and Ethereal still reports configured.

- [ ] **Step 3: Implement the minimal sandbox policy**

Add the sandbox set and pure classifier near `SMTP_KEYS`:

```js
const SANDBOX_SMTP_HOSTS = new Set([
  'smtp.ethereal.email',
])

export function isSandboxSmtpHost(host) {
  return SANDBOX_SMTP_HOSTS.has(String(host || '').trim().toLowerCase())
}
```

Change SMTP parsing and readiness to:

```js
export function getPasswordlessSmtpSettings(
  env = process.env,
  { required = false, production = false } = {}
) {
  const missing = SMTP_KEYS.filter((key) => !String(env[key] || '').trim())
  if (missing.length > 0) {
    if (required) {
      throw new Error(`Passwordless SMTP is missing: ${missing.join(', ')}`)
    }
    return null
  }

  const host = String(env.SUPERTOKENS_SMTP_HOST).trim()
  if (production && isSandboxSmtpHost(host)) {
    throw new Error(`Production cannot use sandbox SMTP host: ${host}`)
  }

  const port = Number(env.SUPERTOKENS_SMTP_PORT)
  if (!Number.isInteger(port) || port < 1 || port > 65_535) {
    throw new Error('SUPERTOKENS_SMTP_PORT must be an integer between 1 and 65535')
  }

  return {
    host,
    port,
    secure: envBool(env.SUPERTOKENS_SMTP_SECURE),
    authUsername: String(env.SUPERTOKENS_SMTP_USER).trim(),
    password: String(env.SUPERTOKENS_SMTP_PASSWORD),
    from: {
      email: String(env.SUPERTOKENS_SMTP_FROM_EMAIL).trim(),
      name: String(env.SUPERTOKENS_SMTP_FROM_NAME || 'StarMC').trim(),
    },
  }
}

export function isSmtpConfigured(
  env = process.env,
  { production = false } = {}
) {
  try {
    return getPasswordlessSmtpSettings(env, { production }) !== null
  } catch {
    return false
  }
}
```

The readiness helper intentionally converts invalid configuration into `false`; startup remains responsible for throwing in production.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the same test command. Expected: all SMTP configuration tests pass with no password in output.

### Task 2: Startup And Readiness Wiring

**Files:**
- Modify: `重构/backend/tests/unit/auth/supertokensEmailDelivery.test.js`
- Modify: `重构/backend/src/auth/supertokensInit.js`
- Modify: `重构/backend/src/server.js`

- [ ] **Step 1: Add a failing production recipe test**

Append:

```js
it('refuses to construct a production Passwordless recipe with Ethereal', () => {
  assert.throws(
    () => createPasswordlessRecipe({
      env: etherealEnv,
      required: true,
      production: true,
      PasswordlessRecipe: { init: () => 'should-not-initialize' },
      SmtpService: class {},
    }),
    /sandbox SMTP host/i
  )
})
```

- [ ] **Step 2: Run the focused test and verify RED**

Expected: recipe construction succeeds or ignores `production`.

- [ ] **Step 3: Pass production intent into recipe construction**

Change the constructor signature and parser call:

```js
export function createPasswordlessRecipe({
  env = process.env,
  required = config.isProd,
  production = config.isProd,
  PasswordlessRecipe = Passwordless,
  SmtpService = SMTPService,
} = {}) {
  const smtpSettings = getPasswordlessSmtpSettings(env, {
    required,
    production,
  })
  // Existing SMTPService and Passwordless recipe construction remains unchanged.
}
```

- [ ] **Step 4: Make both readiness call sites production-aware**

In the dependency object passed to `buildPublicBootstrap`, replace the raw function with:

```js
isSmtpConfigured: () => isSmtpConfigured(process.env, {
  production: config.isProd,
}),
```

In `/api/auth/status`, change the response field to:

```js
smtpConfigured: isSmtpConfigured(process.env, {
  production: config.isProd,
})
```

- [ ] **Step 5: Run focused and public bootstrap tests**

```powershell
node --test tests/unit/auth/supertokensEmailDelivery.test.js
node --test tests/public-bootstrap.test.js
```

Expected: both commands pass; injected bootstrap fakes remain unchanged.

### Task 3: Configuration Doctor Guard

**Files:**
- Create: `tools/starmc-config-lint.test.mjs`
- Modify: `tools/starmc-config-lint.mjs`
- Modify: `tools/starmc-simple-config.env`

- [ ] **Step 1: Create failing configuration-lint tests**

Create `tools/starmc-config-lint.test.mjs`:

```js
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
```

- [ ] **Step 2: Run the test and verify RED**

```powershell
node --test tools/starmc-config-lint.test.mjs
```

Expected: Ethereal and incomplete settings are not rejected with the new codes.

- [ ] **Step 3: Implement strict production linting**

Import the shared classifier:

```js
import { isSandboxSmtpHost } from '../重构/backend/src/auth/supertokensEmailDelivery.js'
```

Make the generated-env fallback injectable so tests cannot silently inherit local credentials:

```js
export function lintSimpleConfig(
  simple,
  { existingBackend = parseEnvFile(PATHS.backendGenerated) } = {}
) {
  const issues = []
  const profile = String(simple.STARMC_PROFILE || 'local').trim().toLowerCase()
  // Existing profile validation remains unchanged.

  const view = buildConfigView(simple)
  const backendEnv = buildBackendEnv(view, simple, existingBackend)
  // Existing feature and file checks remain unchanged.
}
```

Replace the current host-only production SMTP check with:

```js
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
```

- [ ] **Step 4: Document DirectMail values without a password**

Change the SMTP comment block in `tools/starmc-simple-config.env` to:

```env
# SMTP（production 必填；DirectMail 推荐使用独立发信子域）
# STARMC_SMTP_HOST=smtpdm.aliyun.com
# STARMC_SMTP_PORT=465
# STARMC_SMTP_SECURE=true
# STARMC_SMTP_USER=noreply@notify.star-mc.top
# STARMC_SMTP_PASSWORD=请仅在本地私密覆盖或服务器环境中设置
# STARMC_SMTP_FROM_EMAIL=noreply@notify.star-mc.top
# STARMC_SMTP_FROM_NAME=StarMC
```

- [ ] **Step 5: Run lint tests and the current config doctor**

```powershell
node --test tools/starmc-config-lint.test.mjs
npm run config:lint
```

Expected: unit tests pass. The current production config must fail with `prod_smtp_sandbox` until DirectMail credentials replace Ethereal; that failure is the intended release gate.

### Task 4: Complete Local Verification

**Files:**
- Verify all files changed in Tasks 1-3.

- [ ] **Step 1: Run the full backend gate**

```powershell
npm --prefix 重构/backend run lint
npm --prefix 重构/backend test
```

Expected: ESLint exits 0 and all backend tests pass.

- [ ] **Step 2: Run the frontend regression gate**

```powershell
npm --prefix 重构/starmc run test:unit
npm --prefix 重构/starmc run lint
npm --prefix 重构/starmc run build:frontend
```

Expected: all frontend tests pass, TypeScript exits 0, and Vite builds successfully. No frontend code should change.

- [ ] **Step 3: Scan for secret leakage and test-provider drift**

```powershell
rg -n "SUPERTOKENS_SMTP_PASSWORD=.*[^=]$|directmail-test-password|test-smtp-password" 重构/backend/src 重构/starmc/src 重构/starmc/dist
rg -n "smtp\.ethereal\.email" 重构/backend/src tools
```

Expected: test passwords appear only in test fixtures. Ethereal appears only in the sandbox deny-list, tests, and documentation explaining the prohibition.

### Task 5: Provision DirectMail Without Affecting Feishu

**External state:** Alibaba Cloud DirectMail and Alibaba Cloud DNS.

- [ ] **Step 1: Activate DirectMail and add the isolated sender domain**

In the DirectMail console, activate the mainland China service and add `notify.star-mc.top`. Do not add `star-mc.top`, because its root MX records belong to Feishu.

- [ ] **Step 2: Apply the console-generated DNS records**

In Alibaba Cloud DNS, add every record displayed for `notify.star-mc.top`, including ownership, MX, SPF/TXT, and DKIM/CNAME records. Use the exact record names and targets from the console. Do not replace or edit existing root-domain Feishu MX records.

Expected: DirectMail reports the sender domain as verified and `Resolve-DnsName` returns the same public values shown by the console.

- [ ] **Step 3: Create the sender and SMTP credential**

Create `noreply@notify.star-mc.top`, enable SMTP sending, and set a dedicated SMTP password. Store it in a protected password manager or server secret input. Do not send it through chat or place it in repository files.

- [ ] **Step 4: Set a real E2E recipient**

Choose a mailbox controlled by the operator and make it available only as `STARMC_E2E_EMAIL` in the deployment shell. The address must not be committed or printed unmasked.

### Task 6: Rollback-Safe Production Cutover

**Remote paths:**
- Backend: `/www/wwwroot/starmc-api`
- Backend backup pattern: `/www/wwwroot/starmc-api.backup-$(date -u +%Y%m%d-%H%M%S)`

- [ ] **Step 1: Rotate SSH access before deployment**

Rotate the password exposed earlier in chat, install an SSH public key for the deployment operator, and verify `ssh -o BatchMode=yes` succeeds. Do not put the new password in scripts or command-line arguments.

- [ ] **Step 2: Back up production**

Create a timestamped copy of the backend source files and `.env.generated`, preserving permissions. Confirm the resolved backup path stays under `/www/wwwroot` before copying.

- [ ] **Step 3: Install the DirectMail environment securely**

Set these non-secret values in the protected server env:

```env
SUPERTOKENS_SMTP_HOST=smtpdm.aliyun.com
SUPERTOKENS_SMTP_PORT=465
SUPERTOKENS_SMTP_SECURE=true
SUPERTOKENS_SMTP_USER=noreply@notify.star-mc.top
SUPERTOKENS_SMTP_FROM_EMAIL=noreply@notify.star-mc.top
SUPERTOKENS_SMTP_FROM_NAME=StarMC
```

Supply `SUPERTOKENS_SMTP_PASSWORD` through a non-echoing secret prompt or protected file editor. Confirm only that the variable is non-empty; never print its value.

- [ ] **Step 4: Verify SMTP authentication before restart**

Run a server-local Nodemailer `transport.verify()` using the installed environment. Expected output contains only:

```json
{"verified":true,"host":"smtpdm.aliyun.com","port":465,"secure":true}
```

- [ ] **Step 5: Deploy code and restart PM2**

Upload the three changed backend files and restart:

```bash
pm2 restart starmc-api --update-env
pm2 show starmc-api
```

Expected: process status is `online`, with no sandbox-host or SMTP initialization error.

- [ ] **Step 6: Smoke readiness with browser-equivalent headers**

Verify:

```text
GET /api/auth/status       -> 200, supertokens=true, smtpConfigured=true
GET /api/public/bootstrap  -> 200, auth.smtpConfigured=true
POST /auth/signin          -> known bad password returns WRONG_CREDENTIALS_ERROR
```

- [ ] **Step 7: Complete the real mailbox authentication flow**

1. Request a code with `POST /auth/signinup/code` for `STARMC_E2E_EMAIL`.
2. Read the six-digit code from that mailbox without logging it.
3. Consume it with `POST /auth/signinup/code/consume` using the returned device and pre-auth identifiers.
4. Reuse the cookie jar and verify `/api/auth/status` reports `authenticated: true`.
5. Verify `/api/user/me` returns the synchronized user.
6. Sign out and repeat code sign-in once.

Expected: both sign-ins establish a SuperTokens cookie session and no unexpected guest `401` appears after authentication.

- [ ] **Step 8: Roll back on any failed gate**

If SMTP verification, startup, readiness, or real mailbox E2E fails, restore the timestamped source and environment backup, restart `starmc-api`, and retain redacted PM2 logs. Never restore Ethereal as a production-ready configuration; if the old release is restored temporarily, disable email-code login or report SMTP unavailable until DirectMail is corrected.
