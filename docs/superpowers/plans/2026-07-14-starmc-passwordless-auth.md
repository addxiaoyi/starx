# StarMC Passwordless Authentication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver email-code sign-in that automatically creates new accounts, sends real SMTP mail, and establishes a verified SuperTokens cookie session while fixing password error handling, form autocomplete, and SSE reconnect behavior.

**Architecture:** Add the native SuperTokens Passwordless recipe beside EmailPassword and Session, using the existing production SMTP environment. Keep authentication protocol parsing in focused frontend helpers, make React contexts verify the resulting session before navigation, and retain hardened legacy code routes during the backend-first compatibility window.

**Tech Stack:** Node.js 20, Express, SuperTokens Node 20.1.7, Nodemailer, React 19, TypeScript 5.8, Node test runner, Vite, PM2, Nginx.

**Repository note:** The aggregate workspace contains an invalid `.git` directory and `git rev-parse` fails. Commit steps are intentionally omitted; create timestamped deployment backups instead.

---

## File Map

- Modify `重构/backend/src/auth/supertokensEmailDelivery.js`: parse and validate Passwordless SMTP configuration.
- Modify `重构/backend/src/auth/supertokensInit.js`: initialize Passwordless with SMTP delivery.
- Modify `重构/backend/src/features/verifyCodeLogin.js`: forbid production local-user fallback.
- Modify `重构/backend/src/modules/auth/verifyCodeRoutes.js`: use the SuperTokens v20 session signature during the compatibility window.
- Create `重构/backend/tests/unit/auth/supertokensEmailDelivery.test.js`: SMTP configuration tests.
- Modify `重构/backend/tests/unit/modules/verifyCode.test.js`: legacy session and fallback regression tests.
- Create `重构/starmc/src/lib/supertokensAuth.ts`: typed native Passwordless and EmailPassword protocol functions.
- Create `重构/starmc/src/lib/supertokensAuth.test.ts`: frontend authentication protocol tests.
- Modify `重构/starmc/src/context/BootstrapContext.tsx`: return the refreshed session user.
- Modify `重构/starmc/src/context/AuthContext.tsx`: orchestrate typed auth functions and require a confirmed session.
- Modify `重构/starmc/src/components/LoginView.tsx`: persist Passwordless challenge state and add form semantics.
- Create `重构/starmc/src/lib/sseReconnect.ts`: pure reconnect-decision helper.
- Create `重构/starmc/src/lib/sseReconnect.test.ts`: reconnect regression tests.
- Modify `重构/starmc/src/hooks/useSkinLibrarySync.ts`: schedule reconnect independently of polling.

### Task 1: Passwordless SMTP Configuration

**Files:**
- Modify: `重构/backend/src/auth/supertokensEmailDelivery.js`
- Create: `重构/backend/tests/unit/auth/supertokensEmailDelivery.test.js`

- [ ] **Step 1: Write failing SMTP configuration tests**

```js
import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import {
  buildPasswordlessEmailContent,
  getPasswordlessSmtpSettings,
  isSmtpConfigured,
} from '../../../src/auth/supertokensEmailDelivery.js'

const completeEnv = {
  SUPERTOKENS_SMTP_HOST: 'smtp.example.com',
  SUPERTOKENS_SMTP_PORT: '465',
  SUPERTOKENS_SMTP_SECURE: 'true',
  SUPERTOKENS_SMTP_USER: 'mailer@example.com',
  SUPERTOKENS_SMTP_PASSWORD: 'secret',
  SUPERTOKENS_SMTP_FROM_EMAIL: 'noreply@example.com',
  SUPERTOKENS_SMTP_FROM_NAME: 'StarMC',
}

describe('Passwordless SMTP configuration', () => {
  it('maps production SMTP environment into SuperTokens settings', () => {
    assert.deepEqual(getPasswordlessSmtpSettings(completeEnv, { required: true }), {
      host: 'smtp.example.com',
      port: 465,
      secure: true,
      authUsername: 'mailer@example.com',
      password: 'secret',
      from: { email: 'noreply@example.com', name: 'StarMC' },
    })
  })

  it('fails loudly when required SMTP fields are missing', () => {
    assert.throws(
      () => getPasswordlessSmtpSettings({}, { required: true }),
      /SUPERTOKENS_SMTP_HOST/
    )
  })

  it('reports incomplete optional SMTP as unavailable', () => {
    assert.equal(isSmtpConfigured({ SUPERTOKENS_SMTP_HOST: 'smtp.example.com' }), false)
  })

  it('builds a bilingual code email without exposing unrelated secrets', () => {
    const content = buildPasswordlessEmailContent({
      email: 'player@example.com',
      userInputCode: '123456',
    })
    assert.equal(content.toEmail, 'player@example.com')
    assert.match(content.subject, /StarMC/)
    assert.match(content.body, /123456/)
    assert.match(content.body, /验证码/)
    assert.match(content.body, /verification code/i)
  })
})
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
node --test tests/unit/auth/supertokensEmailDelivery.test.js
```

Expected: FAIL because `getPasswordlessSmtpSettings` is not exported.

- [ ] **Step 3: Implement strict SMTP parsing**

```js
const SMTP_KEYS = [
  'SUPERTOKENS_SMTP_HOST',
  'SUPERTOKENS_SMTP_PORT',
  'SUPERTOKENS_SMTP_USER',
  'SUPERTOKENS_SMTP_PASSWORD',
  'SUPERTOKENS_SMTP_FROM_EMAIL',
]

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (char) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  })[char])
}

export function buildPasswordlessEmailContent({ email, userInputCode }) {
  const code = String(userInputCode || '').trim()
  if (!/^\d{6}$/.test(code)) throw new Error('Passwordless email requires a six-digit code')
  const safeCode = escapeHtml(code)
  return {
    toEmail: String(email).trim(),
    subject: 'StarMC 登录验证码 / Sign-in code',
    isHtml: true,
    body: `<p>你的 StarMC 验证码是：</p><p><strong>${safeCode}</strong></p>` +
      `<p>This is your StarMC verification code. It expires shortly.</p>`,
  }
}

function envBool(value) {
  return /^(1|true|yes|on)$/i.test(String(value || '').trim())
}

export function getPasswordlessSmtpSettings(env = process.env, { required = false } = {}) {
  const missing = SMTP_KEYS.filter((key) => !String(env[key] || '').trim())
  if (missing.length > 0) {
    if (required) throw new Error(`Passwordless SMTP is missing: ${missing.join(', ')}`)
    return null
  }

  const port = Number(env.SUPERTOKENS_SMTP_PORT)
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error('SUPERTOKENS_SMTP_PORT must be an integer between 1 and 65535')
  }

  return {
    host: String(env.SUPERTOKENS_SMTP_HOST).trim(),
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

export function isSmtpConfigured(env = process.env) {
  return getPasswordlessSmtpSettings(env) !== null
}
```

- [ ] **Step 4: Run the focused test and verify GREEN**

Run `node --test tests/unit/auth/supertokensEmailDelivery.test.js`.

Expected: 4 tests pass with no warnings.

### Task 2: Initialize Native Passwordless Authentication

**Files:**
- Modify: `重构/backend/src/auth/supertokensInit.js`
- Test: `重构/backend/tests/unit/auth/supertokensEmailDelivery.test.js`

- [ ] **Step 1: Add a failing recipe-construction test**

Append a test that injects fakes and asserts the recipe config:

```js
import { createPasswordlessRecipe } from '../../../src/auth/supertokensInit.js'

it('creates an email user-input-code recipe with SMTP delivery', () => {
  let recipeInput
  const recipe = createPasswordlessRecipe({
    env: completeEnv,
    required: true,
    PasswordlessRecipe: { init: (input) => { recipeInput = input; return 'passwordless-recipe' } },
    SmtpService: class { constructor(input) { this.input = input } },
  })

  assert.equal(recipe, 'passwordless-recipe')
  assert.equal(recipeInput.contactMethod, 'EMAIL')
  assert.equal(recipeInput.flowType, 'USER_INPUT_CODE')
  assert.equal(recipeInput.emailDelivery.service.input.smtpSettings.host, 'smtp.example.com')
})
```

- [ ] **Step 2: Run the test and verify RED**

Expected: FAIL because `createPasswordlessRecipe` is not exported.

- [ ] **Step 3: Implement Passwordless recipe construction and initialization**

Add imports and a dependency-injected constructor:

```js
import Passwordless from 'supertokens-node/recipe/passwordless'
import { SMTPService } from 'supertokens-node/recipe/passwordless/emaildelivery/services/index.js'
import { getPasswordlessSmtpSettings } from './supertokensEmailDelivery.js'

export function createPasswordlessRecipe({
  env = process.env,
  required = config.isProd,
  PasswordlessRecipe = Passwordless,
  SmtpService = SMTPService,
} = {}) {
  const smtpSettings = getPasswordlessSmtpSettings(env, { required })
  const emailDelivery = smtpSettings
    ? {
        service: new SmtpService({
          smtpSettings,
          override: (original) => ({
            ...original,
            getContent: async (input) => buildPasswordlessEmailContent(input),
          }),
        }),
      }
    : undefined

  return PasswordlessRecipe.init({
    contactMethod: 'EMAIL',
    flowType: 'USER_INPUT_CODE',
    ...(emailDelivery ? { emailDelivery } : {}),
  })
}
```

Change the recipe list to:

```js
recipeList: [EmailPassword.init(), createPasswordlessRecipe(), Session.init()],
```

- [ ] **Step 4: Run focused and full backend tests**

Run:

```powershell
node --test tests/unit/auth/supertokensEmailDelivery.test.js
npm test
```

Expected: focused tests pass; the complete backend suite has zero failures.

### Task 3: Harden Legacy Verification-Code Compatibility

**Files:**
- Modify: `重构/backend/src/features/verifyCodeLogin.js`
- Modify: `重构/backend/src/modules/auth/verifyCodeRoutes.js`
- Modify: `重构/backend/tests/unit/modules/verifyCode.test.js`

- [ ] **Step 1: Write failing production-fallback and session-signature tests**

Add a pure policy test:

```js
import { canUseVerifyCodeDevFallback } from '../../../src/features/verifyCodeLogin.js'

it('never allows local verification fallback in production', () => {
  assert.equal(canUseVerifyCodeDevFallback(true, '用户不存在，请先注册'), false)
  assert.equal(canUseVerifyCodeDevFallback(true, 'SuperTokens 未初始化'), false)
  assert.equal(canUseVerifyCodeDevFallback(false, 'SuperTokens 未初始化'), true)
})
```

Add a route test whose `confirmVerifyCodeLogin` invokes `hooks.onLogin` and whose mock user exposes `loginMethods[0].recipeUserId`:

```js
it('creates legacy sessions with the SuperTokens v20 signature', async () => {
  const recipeUserId = { getAsString: () => 'recipe-user-1' }
  let sessionArgs
  const deps = createMockDeps({
    deps: {
      supertokens: {
        listUsersByAccountInfo: async () => [{
          id: 'primary-user-1',
          loginMethods: [{ recipeId: 'emailpassword', recipeUserId }],
        }],
      },
      Session: {
        createNewSession: async (...args) => { sessionArgs = args },
      },
      confirmVerifyCodeLogin: async (_store, _body, hooks) => ({
        ok: true,
        user: await hooks.onLogin('test@example.com'),
      }),
    },
  })
  const app = express()
  app.use(express.json())
  registerVerifyCodeRoutes(app, deps)

  const response = await fakeRequest(app, 'POST', '/api/auth/verify-code/confirm', {
    body: { email: 'test@example.com', code: '123456' },
  })

  assert.equal(response.status, 200)
  assert.equal(sessionArgs[0].url, '/api/auth/verify-code/confirm')
  assert.equal(sessionArgs[1].statusCode, 200)
  assert.equal(sessionArgs[2], 'public')
  assert.equal(sessionArgs[3], recipeUserId)
})
```

- [ ] **Step 2: Run the module test and verify RED**

Run `node --test tests/unit/modules/verifyCode.test.js`.

Expected: FAIL because fallback policy is missing and the current call omits `req`.

- [ ] **Step 3: Implement the production policy**

```js
export function canUseVerifyCodeDevFallback(isProd, message) {
  if (isProd) return false
  return message.includes('SuperTokens 未初始化') || message.includes('用户不存在')
}
```

Use it inside `confirmVerifyCodeLogin` before creating a local fallback user.

- [ ] **Step 4: Fix SuperTokens v20 session creation**

For existing users, select an email-password or passwordless login method and call:

```js
const loginMethod = stUsers[0].loginMethods.find((method) => method.recipeUserId)
if (!loginMethod) throw new Error('用户认证方式不可用')
await Session.createNewSession(req, res, 'public', loginMethod.recipeUserId, {}, {})
```

For legacy register mode, use the returned recipe ID:

```js
await Session.createNewSession(req, res, 'public', resp.recipeUserId, {}, {})
```

- [ ] **Step 5: Run backend module, full suite, and lint**

```powershell
node --test tests/unit/modules/verifyCode.test.js
npm test
npm run lint
```

Expected: all tests pass and ESLint reports no errors.

### Task 4: Add Typed Frontend Authentication Contracts

**Files:**
- Create: `重构/starmc/src/lib/supertokensAuth.ts`
- Create: `重构/starmc/src/lib/supertokensAuth.test.ts`

- [ ] **Step 1: Write failing protocol tests**

Cover native challenge creation, consume payloads, and password business errors:

```ts
import assert from 'node:assert/strict'
import test from 'node:test'
import {
  consumeEmailCode,
  signInWithPassword,
  signUpWithPassword,
  startEmailCode,
} from './supertokensAuth'

test('stores native Passwordless challenge identifiers', async () => {
  const calls: Array<{ path: string; body: string }> = []
  const challenge = await startEmailCode(async (path, options) => {
    calls.push({ path, body: String(options.body) })
    return { status: 'OK', deviceId: 'device-1', preAuthSessionId: 'preauth-1' }
  }, 'Player@Example.com')
  assert.deepEqual(challenge, {
    email: 'player@example.com',
    deviceId: 'device-1',
    preAuthSessionId: 'preauth-1',
  })
  assert.equal(calls[0].path, '/auth/signinup/code')
})

test('submits the native challenge when consuming a code', async () => {
  let requestBody = ''
  await consumeEmailCode(async (_path, options) => {
    requestBody = String(options.body)
    return { status: 'OK' }
  }, { email: 'player@example.com', deviceId: 'device-1', preAuthSessionId: 'preauth-1' }, '123456')
  assert.deepEqual(JSON.parse(requestBody), {
    deviceId: 'device-1',
    preAuthSessionId: 'preauth-1',
    userInputCode: '123456',
  })
})

test('rejects password business errors returned with HTTP 200', async () => {
  await assert.rejects(
    signInWithPassword(async () => ({ status: 'WRONG_CREDENTIALS_ERROR' }), 'a@b.com', 'wrong'),
    /邮箱或密码错误/
  )
  await assert.rejects(
    signUpWithPassword(async () => ({ status: 'EMAIL_ALREADY_EXISTS_ERROR' }), 'a@b.com', 'secret123'),
    /邮箱已注册/
  )
})
```

- [ ] **Step 2: Run the frontend unit test and verify RED**

Run `npx tsx --test src/lib/supertokensAuth.test.ts`.

Expected: FAIL because the module does not exist.

- [ ] **Step 3: Implement protocol functions**

Create exported `EmailCodeChallenge` and dependency-injected request functions:

```ts
import type { SessionUser } from './session'

export interface EmailCodeChallenge {
  email: string
  deviceId: string
  preAuthSessionId: string
}

type AuthBody = Record<string, unknown>
type AuthRequest = (path: string, options: RequestInit) => Promise<AuthBody>

function authError(body: AuthBody): Error {
  const status = String(body.status || '')
  if (status === 'WRONG_CREDENTIALS_ERROR') return new Error('邮箱或密码错误')
  if (status === 'EMAIL_ALREADY_EXISTS_ERROR') return new Error('邮箱已注册，请直接登录')
  if (status === 'INCORRECT_USER_INPUT_CODE_ERROR') return new Error('验证码错误')
  if (status === 'EXPIRED_USER_INPUT_CODE_ERROR') return new Error('验证码已过期，请重新获取')
  if (status === 'RESTART_FLOW_ERROR') return new Error('验证码流程已失效，请重新获取')
  if (status === 'FIELD_ERROR') {
    const fields = body.formFields as Array<{ error?: string }> | undefined
    return new Error(String(fields?.[0]?.error || '表单验证失败'))
  }
  return new Error('认证失败，请稍后重试')
}

function requireOk(body: AuthBody): AuthBody {
  if (body.status !== 'OK') throw authError(body)
  return body
}

export async function startEmailCode(request: AuthRequest, email: string): Promise<EmailCodeChallenge> {
  const normalizedEmail = email.trim().toLowerCase()
  const body = requireOk(await request('/auth/signinup/code', {
    method: 'POST',
    body: JSON.stringify({ email: normalizedEmail }),
  }))
  const deviceId = String(body.deviceId || '')
  const preAuthSessionId = String(body.preAuthSessionId || '')
  if (!deviceId || !preAuthSessionId) throw new Error('验证码服务返回了无效响应')
  return { email: normalizedEmail, deviceId, preAuthSessionId }
}

export async function consumeEmailCode(request: AuthRequest, challenge: EmailCodeChallenge, code: string) {
  return requireOk(await request('/auth/signinup/code/consume', {
    method: 'POST',
    body: JSON.stringify({
      deviceId: challenge.deviceId,
      preAuthSessionId: challenge.preAuthSessionId,
      userInputCode: code.trim(),
    }),
  }))
}

function passwordBody(email: string, password: string) {
  return JSON.stringify({ formFields: [
    { id: 'email', value: email.trim().toLowerCase() },
    { id: 'password', value: password },
  ] })
}

export async function signInWithPassword(request: AuthRequest, email: string, password: string) {
  return requireOk(await request('/auth/signin', { method: 'POST', body: passwordBody(email, password) }))
}

export async function signUpWithPassword(request: AuthRequest, email: string, password: string) {
  return requireOk(await request('/auth/signup', { method: 'POST', body: passwordBody(email, password) }))
}

export function requireAuthenticatedUser(user: SessionUser | null): SessionUser {
  if (!user) throw new Error('登录会话未建立，请重试')
  return user
}
```

- [ ] **Step 4: Run focused and complete frontend unit tests**

```powershell
npx tsx --test src/lib/supertokensAuth.test.ts
npm run test:unit
```

Expected: all protocol tests and all existing library tests pass.

### Task 5: Require a Confirmed Session in React State

**Files:**
- Modify: `重构/starmc/src/context/BootstrapContext.tsx`
- Modify: `重构/starmc/src/context/AuthContext.tsx`
- Modify: `重构/starmc/src/components/LoginView.tsx`
- Test: `重构/starmc/src/lib/supertokensAuth.test.ts`

- [ ] **Step 1: Add a failing session-confirmation test**

```ts
import { requireAuthenticatedUser } from './supertokensAuth'

test('rejects auth responses that do not establish a session', () => {
  assert.throws(() => requireAuthenticatedUser(null), /会话未建立/)
  assert.equal(requireAuthenticatedUser({ id: 'u-1', username: 'player' }).id, 'u-1')
})
```

- [ ] **Step 2: Run the focused test and verify RED**

Expected: FAIL because `requireAuthenticatedUser` is missing.

- [ ] **Step 3: Return the refreshed user from BootstrapContext**

Change `refreshUser` to `() => Promise<SessionUser | null>`. Return `snapshot.user` after setting state, return `null` for 401, and rethrow non-401 failures after logging so authentication callers cannot report false success.

- [ ] **Step 4: Update AuthContext orchestration**

Expose:

```ts
sendLoginCode: (email: string) => Promise<EmailCodeChallenge>
loginWithCode: (challenge: EmailCodeChallenge, code: string) => Promise<void>
```

Use the new protocol helpers for password and code methods. After each successful SuperTokens response:

```ts
clearDevSessionUser()
requireAuthenticatedUser(await refreshUser())
```

- [ ] **Step 5: Update LoginView challenge state and form semantics**

Replace `mockVerificationCode` and direct `api.auth.sendCode` usage with `EmailCodeChallenge | null`. Clear the challenge when the email changes or the user goes back.

Add:

```tsx
autoComplete="email"
autoComplete="current-password"
autoComplete="new-password"
autoComplete="one-time-code"
inputMode="numeric"
```

with stable `name` attributes. Remove the production-facing development-code panel and its state.

- [ ] **Step 6: Run frontend tests and TypeScript checking**

```powershell
npm run test:unit
npm run lint
```

Expected: all tests pass and `tsc --noEmit` exits 0.

### Task 6: Restore SSE Reconnection

**Files:**
- Create: `重构/starmc/src/lib/sseReconnect.ts`
- Create: `重构/starmc/src/lib/sseReconnect.test.ts`
- Modify: `重构/starmc/src/hooks/useSkinLibrarySync.ts`

- [ ] **Step 1: Write a failing reconnect-decision test**

```ts
import assert from 'node:assert/strict'
import test from 'node:test'
import { getSseReconnectDelay } from './sseReconnect'

test('polling does not suppress SSE reconnect', () => {
  assert.equal(getSseReconnectDelay(false), 15_000)
  assert.equal(getSseReconnectDelay(true), null)
})
```

- [ ] **Step 2: Run and verify RED**

Run `npx tsx --test src/lib/sseReconnect.test.ts`.

Expected: FAIL because the helper is missing.

- [ ] **Step 3: Implement and use the helper**

```ts
export function getSseReconnectDelay(hasPendingReconnect: boolean): number | null {
  return hasPendingReconnect ? null : 15_000
}
```

In `onerror`, check `reconnectTimer`, not `pollTimer`. Set `reconnectTimer = null` before calling `startSSE()` from the timeout. Keep the 60-second poll independent and prevent duplicate EventSource instances.

- [ ] **Step 4: Run all frontend tests and type checking**

Expected: zero failures and no TypeScript errors.

### Task 7: Full Local Quality and Security Gates

**Files:**
- Verify all changed files above.

- [ ] **Step 1: Run backend verification**

```powershell
cd 重构\backend
npm run lint
npm test
```

Expected: ESLint has zero errors; all backend tests pass.

- [ ] **Step 2: Run frontend verification**

```powershell
cd 重构\starmc
npm run test:unit
npm run lint
npx vite build --outDir dist-passwordless-release --emptyOutDir
```

Expected: all tests pass, TypeScript exits 0, and the production build completes.

- [ ] **Step 3: Inspect generated assets for secret leakage**

```powershell
rg -n "SUPERTOKENS_SMTP_PASSWORD|userInputCode.*console|password.*console" dist-passwordless-release src ..\backend\src
```

Expected: no embedded credential or credential-logging match.

- [ ] **Step 4: Verify production configuration keys without printing values**

Confirm `.env.generated` contains the seven SMTP keys plus `SUPERTOKENS_APP_NAME` and `SUPERTOKENS_CONNECTION_URI` identified during design. Do not display their values.

### Task 8: Rollback-Safe Production Deployment and E2E

**Files:**
- Deploy backend files changed above.
- Deploy clean frontend output from Task 7.

- [ ] **Step 1: Back up and deploy backend**

Create `/www/wwwroot/starmc-api.backup-<timestamp>/` containing the changed backend auth files, upload replacements, run `npm test` remotely if dependencies permit, then `pm2 restart starmc-api --update-env`.

- [ ] **Step 2: Smoke backend before frontend cutover**

Use browser-equivalent `Origin`, `Referer`, and `User-Agent` headers. Verify:

```text
GET  /api/auth/status              -> 200, supertokens=true, smtpConfigured=true
POST /auth/signinup/code           -> 200, status=OK, deviceId and preAuthSessionId present
POST /auth/signin                  -> a known bad credential returns WRONG_CREDENTIALS_ERROR
```

Use `STARMC_E2E_EMAIL` as the test recipient. The value must be a mailbox available to the operator; do not log it beyond a masked form.

- [ ] **Step 3: Package and atomically deploy frontend**

Require exactly one `index-*.js`, verify it contains `/auth/signinup/code`, and switch `/www/wwwroot/star-web.top` to a timestamped release while preserving `/www/wwwroot/star-web.top.backup-<timestamp>`.

- [ ] **Step 4: Complete real mailbox E2E**

In a fresh browser session:

1. request a code for a new disposable mailbox;
2. verify the email arrives and contains a six-digit code;
3. consume the code and verify `/api/auth/status` is authenticated;
4. verify `/api/user/me` returns the new synchronized profile;
5. sign out and sign in again with a second code;
6. confirm wrong and expired codes remain on the login page with safe errors;
7. confirm password duplicate-email and wrong-password errors are accurate;
8. confirm no autocomplete warning, unexpected 401, leaked code, or repeated SSE failure loop appears in the console.

- [ ] **Step 5: Roll back on any failed gate**

If backend smoke fails, restore the timestamped backend files and restart PM2. If frontend or E2E fails, atomically restore the previous frontend directory. Keep the failed release under a timestamped `.failed-<timestamp>` path for inspection.
