# StarMC Account And Session UX Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the selected minimal account page while making a SuperTokens cookie session restore reliably for 30 days and making device revocation affect real sessions.

**Architecture:** Keep SuperTokens Core as the session authority. Backend security routes translate Core session handles into safe device rows and revoke those handles directly. Frontend bootstrap resolves that cookie-backed session before protected-route decisions; the account view then renders a minimal, theme-safe overview and focused account sections.

**Tech Stack:** React 19, TypeScript 5.8, React Router, Tailwind CSS 4, Node test runner with `tsx`, Express, SuperTokens Node/Core.

---

## File Map

- Create: `重构/backend/src/auth/sessionInventory.js` - maps Core session handles to safe account-device rows and owns revocation authorization.
- Create: `重构/backend/tests/unit/auth/sessionInventory.test.js` - exercises real-handle authorization, metadata, and revocation behavior with an injected Core adapter.
- Modify: `重构/backend/src/server.js` - records bounded session metadata and passes the Core adapter into security routes.
- Modify: `重构/backend/src/modules/security/routes.js` - replaces stale JSON-store session operations with `sessionInventory` operations.
- Modify: `重构/starmc/src/lib/returnTo.ts` - normalizes same-origin post-login destinations.
- Create: `重构/starmc/src/lib/returnTo.test.ts` - covers safe return-path handling.
- Modify: `重构/starmc/src/components/ProtectedRoute.tsx` - preserves a protected destination only after bootstrap settles.
- Modify: `重构/starmc/src/components/LoginView.tsx` - returns successful sign-in, signup, and code flows to the safe saved destination.
- Modify: `重构/starmc/src/components/UserCenterView.tsx` - replaces dark-only side navigation and overview cards with the selected minimal account shell.
- Create: `重构/starmc/src/components/account/AccountNavigation.tsx` - owns translated account-section navigation and theme-safe selected states.
- Create: `重构/starmc/src/components/account/accountNavigation.test.ts` - protects account section labels and stable tab IDs.
- Modify: `重构/starmc/src/index.css` - adds compact account surface tokens for light and dark modes.
- Modify: production SuperTokens Core configuration - explicitly sets `refresh_token_validity: 2592000` seconds after backup and confirms the running Core accepts it.

### Task 1: Build A Core-Backed Session Inventory

**Files:**
- Create: `重构/backend/src/auth/sessionInventory.js`
- Create: `重构/backend/tests/unit/auth/sessionInventory.test.js`

- [ ] **Step 1: Write the failing inventory test**

```js
import assert from 'node:assert/strict'
import test from 'node:test'
import { listUserSessions, revokeUserSession } from '../../../src/auth/sessionInventory.js'

test('lists only Core session handles owned by the authenticated user', async () => {
  const core = {
    getAllSessionHandlesForUser: async () => ['mine', 'other'],
    getSessionInformation: async (handle) => handle === 'mine'
      ? { userId: 'st-user', timeCreated: 1000, expiry: 2000, sessionDataInDatabase: { device: 'Edge' } }
      : undefined,
  }

  const sessions = await listUserSessions({ core, userId: 'st-user', currentHandle: 'mine' })

  assert.deepEqual(sessions, [{ id: 'mine', current: true, device: 'Edge', createdAt: 1000, expiresAt: 2000 }])
})

test('does not revoke a handle absent from the users Core session list', async () => {
  let revoked = false
  const core = {
    getAllSessionHandlesForUser: async () => ['mine'],
    revokeSession: async () => { revoked = true },
  }

  const result = await revokeUserSession({ core, userId: 'st-user', handle: 'foreign' })

  assert.equal(result, false)
  assert.equal(revoked, false)
})
```

- [ ] **Step 2: Run the focused test and verify RED**

Run: `node --test tests/unit/auth/sessionInventory.test.js`

Expected: failure because `sessionInventory.js` does not exist.

- [ ] **Step 3: Implement the adapter boundary**

```js
export async function listUserSessions({ core, userId, currentHandle }) {
  const handles = await core.getAllSessionHandlesForUser(userId)
  const rows = await Promise.all(handles.map(async (handle) => {
    const info = await core.getSessionInformation(handle)
    if (!info || info.userId !== userId) return null
    const metadata = info.sessionDataInDatabase?.deviceMetadata || {}
    return {
      id: handle,
      current: handle === currentHandle,
      device: String(metadata.device || '未知设备'),
      createdAt: info.timeCreated,
      expiresAt: info.expiry,
    }
  }))
  return rows.filter(Boolean).sort((a, b) => Number(b.createdAt) - Number(a.createdAt))
}

export async function revokeUserSession({ core, userId, handle }) {
  const handles = await core.getAllSessionHandlesForUser(userId)
  if (!handles.includes(handle)) return false
  return core.revokeSession(handle)
}
```

- [ ] **Step 4: Run the focused test and verify GREEN**

Run: `node --test tests/unit/auth/sessionInventory.test.js`

Expected: 2 passing tests.

- [ ] **Step 5: Extend the test for revoking every non-current handle**

```js
test('keeps the current handle when revoking other devices', async () => {
  const revoked = []
  const core = {
    getAllSessionHandlesForUser: async () => ['current', 'phone', 'tablet'],
    revokeMultipleSessions: async (handles) => { revoked.push(...handles); return handles },
  }

  const result = await revokeOtherUserSessions({ core, userId: 'st-user', currentHandle: 'current' })

  assert.deepEqual(revoked, ['phone', 'tablet'])
  assert.equal(result, 2)
})
```

- [ ] **Step 6: Implement and rerun the expanded test**

```js
export async function revokeOtherUserSessions({ core, userId, currentHandle }) {
  const handles = await core.getAllSessionHandlesForUser(userId)
  const targets = handles.filter((handle) => handle !== currentHandle)
  if (!targets.length) return 0
  const revoked = await core.revokeMultipleSessions(targets)
  return revoked.length
}
```

Run: `node --test tests/unit/auth/sessionInventory.test.js`

Expected: 3 passing tests.

### Task 2: Wire Device Actions To Core Sessions

**Files:**
- Modify: `重构/backend/src/server.js`
- Modify: `重构/backend/src/modules/security/routes.js`
- Test: `重构/backend/tests/unit/auth/sessionInventory.test.js`

- [ ] **Step 1: Add a failing route-dependency assertion**

Add a focused assertion that the route adapter receives an object exposing `getAllSessionHandlesForUser`, `getSessionInformation`, `revokeSession`, and `revokeMultipleSessions`.

```js
assert.deepEqual(Object.keys(sessionCore).sort(), [
  'getAllSessionHandlesForUser',
  'getSessionInformation',
  'revokeMultipleSessions',
  'revokeSession',
])
```

- [ ] **Step 2: Run the backend unit group and verify RED**

Run: `node --test tests/unit/auth/sessionInventory.test.js`

Expected: failure until `server.js` exposes the narrow adapter.

- [ ] **Step 3: Pass a narrow Core adapter from `server.js`**

```js
const sessionCore = {
  getAllSessionHandlesForUser: (userId) => Session.getAllSessionHandlesForUser(userId),
  getSessionInformation: (handle) => Session.getSessionInformation(handle),
  revokeSession: (handle) => Session.revokeSession(handle),
  revokeMultipleSessions: (handles) => Session.revokeMultipleSessions(handles),
}

registerSecurityRoutes(app, {
  // existing dependencies
  sessionCore,
})
```

- [ ] **Step 4: Replace JSON-store session routes**

Use the authenticated SuperTokens identity and handle for every operation:

```js
const userId = req.session.getUserId()
const currentHandle = req.session.getHandle()
const sessions = await listUserSessions({ core: sessionCore, userId, currentHandle })
jsonOk(res, { sessions })
```

For a single revoke, call `revokeUserSession`; for `revoke-all`, call `revokeOtherUserSessions`. Reject a request to revoke `currentHandle` with `409 current_session` and direct the user to normal sign-out. Append the existing audit event only after Core confirms the revocation.

- [ ] **Step 5: Persist bounded device metadata**

In `attachLocalUser`, merge a `deviceMetadata` object into `req.session` data only when the stored `lastSeenAt` is older than 15 minutes:

```js
const existing = await req.session.getSessionDataFromDatabase()
const previous = Number(existing?.deviceMetadata?.lastSeenAt || 0)
if (Date.now() - previous >= 15 * 60_000) {
  await req.session.updateSessionDataInDatabase({
    ...existing,
    deviceMetadata: {
      device: String(req.headers['user-agent'] || '未知设备').slice(0, 180),
      ip: String(req.ip || ''),
      lastSeenAt: Date.now(),
    },
  })
}
```

- [ ] **Step 6: Rerun unit tests and backend smoke checks**

Run:

```powershell
node --test tests/unit/auth/sessionInventory.test.js
npm test
```

Expected: focused tests pass; the existing backend suite exits 0.

### Task 3: Preserve Protected Destinations During Session Restore

**Files:**
- Create: `重构/starmc/src/lib/returnTo.ts`
- Create: `重构/starmc/src/lib/returnTo.test.ts`
- Modify: `重构/starmc/src/components/ProtectedRoute.tsx`

- [ ] **Step 1: Write failing redirect-normalization tests**

```ts
import assert from 'node:assert/strict'
import test from 'node:test'
import { resolveReturnTo } from './returnTo'

test('keeps an internal protected destination', () => {
  assert.equal(resolveReturnTo({ pathname: '/profile', search: '?tab=security' }), '/profile?tab=security')
})

test('rejects external and malformed return targets', () => {
  assert.equal(resolveReturnTo('https://attacker.example'), '/profile')
  assert.equal(resolveReturnTo('//attacker.example'), '/profile')
})
```

- [ ] **Step 2: Run the focused test and verify RED**

Run: `npx tsx --test src/lib/returnTo.test.ts`

Expected: import failure because `returnTo.ts` does not exist.

- [ ] **Step 3: Implement safe internal return resolution**

```ts
type RouteState = { pathname?: unknown; search?: unknown; hash?: unknown }

export function resolveReturnTo(value: unknown): string {
  if (!value || typeof value !== 'object') return '/profile'
  const state = value as RouteState
  const pathname = typeof state.pathname === 'string' ? state.pathname : ''
  if (!pathname.startsWith('/') || pathname.startsWith('//')) return '/profile'
  const search = typeof state.search === 'string' && state.search.startsWith('?') ? state.search : ''
  const hash = typeof state.hash === 'string' && state.hash.startsWith('#') ? state.hash : ''
  return `${pathname}${search}${hash}`
}
```

- [ ] **Step 4: Gate protected redirects on bootstrap completion**

Keep `bootLoading` as the authoritative initial session state. Replace the spinner with a stable account skeleton and keep the saved route in `Navigate` state:

```tsx
if (bootLoading) return <AccountRouteSkeleton />
if (!currentUser) {
  return <Navigate to="/login" replace state={{ from: location }} />
}
```

Do not use `AuthContext.loading` as a substitute for the initial probe because it only represents an active submit action.

- [ ] **Step 5: Run focused tests and existing session regression tests**

Run:

```powershell
npx tsx --test src/lib/returnTo.test.ts src/lib/sessionProbe.test.ts
```

Expected: all return-path and guest-session tests pass.

### Task 4: Return To The Intended Page After Any Login Flow

**Files:**
- Modify: `重构/starmc/src/components/LoginView.tsx`
- Test: `重构/starmc/src/lib/returnTo.test.ts`

- [ ] **Step 1: Write a failing test for a saved protected location**

```ts
test('uses the saved location after authentication', () => {
  assert.equal(resolveReturnTo({ pathname: '/profile', search: '?tab=security' }), '/profile?tab=security')
})
```

- [ ] **Step 2: Replace the fixed `/profile` redirects**

Read the `from` state once with `useLocation` and resolve it through the helper:

```tsx
const location = useLocation()
const destination = resolveReturnTo((location.state as { from?: unknown } | null)?.from)

await loginWithCode(challenge, code)
navigate(destination, { replace: true })
```

Apply the same `navigate(destination, { replace: true })` behavior to password sign-in and registration. Remove the one-second `setTimeout` so a successful session reaches the intended page immediately.

- [ ] **Step 3: Run the focused frontend tests**

Run:

```powershell
npx tsx --test src/lib/returnTo.test.ts src/lib/supertokensAuth.test.ts
```

Expected: all tests pass and the login helper still rejects a missing session.

### Task 5: Build The Selected Minimal Account Shell

**Files:**
- Create: `重构/starmc/src/components/account/AccountNavigation.tsx`
- Create: `重构/starmc/src/components/account/accountNavigation.test.ts`
- Modify: `重构/starmc/src/components/UserCenterView.tsx`
- Modify: `重构/starmc/src/index.css`

- [ ] **Step 1: Write a failing navigation metadata test**

```ts
import assert from 'node:assert/strict'
import test from 'node:test'
import { accountSections } from './AccountNavigation'

test('exposes the four selected account sections in Chinese', () => {
  assert.deepEqual(accountSections.map((section) => section.id), ['profile', 'minecraft', 'wardrobe', 'security'])
  assert.equal(accountSections[0].label.zh, '个人资料')
  assert.equal(accountSections[3].label.zh, '安全与设备')
})
```

- [ ] **Step 2: Run the focused test and verify RED**

Run: `npx tsx --test src/components/account/accountNavigation.test.ts`

Expected: import failure because `AccountNavigation.tsx` does not exist.

- [ ] **Step 3: Add translated account-section metadata and navigation**

```tsx
export const accountSections = [
  { id: 'profile', label: { zh: '个人资料', en: 'Profile' }, icon: User },
  { id: 'minecraft', label: { zh: '游戏角色', en: 'Minecraft' }, icon: Box },
  { id: 'wardrobe', label: { zh: '皮肤库', en: 'Skin library' }, icon: Palette },
  { id: 'security', label: { zh: '安全与设备', en: 'Security & devices' }, icon: Shield },
] as const
```

Render them as full-width rows on small screens and a narrow plain navigation column on wide screens. Selected state must use a solid semantic surface, never translucent black on the light theme.

- [ ] **Step 4: Replace the profile overview with the C layout**

Use a single readable content column with:

```tsx
<section className="account-identity">
  <img src={resolveAvatarUrl(currentUser)} alt="" />
  <div>
    <p className="account-kicker">{_t('账户', 'Account')}</p>
    <h1>{currentUser.username}</h1>
    <p>{currentUser.email}</p>
  </div>
</section>
<p className="account-session-note">
  {_t('本设备保持登录。刷新页面后会自动恢复；你可在安全与设备中退出其他设备。', 'This device stays signed in. Refresh restores the session; manage other devices in Security & devices.')}
</p>
```

Keep all existing actions and data loads, but move them behind the chosen focused section. Replace every remaining hard-coded visible account string with `_t(zh, en)`.

- [ ] **Step 5: Add theme-safe account tokens**

```css
.account-surface { background: color-mix(in srgb, var(--account-surface) 94%, transparent); border: 1px solid var(--account-border); color: var(--account-text); }
.light { --account-surface: #ffffff; --account-border: #dfe6de; --account-text: #17211d; --account-muted: #66726b; }
.dark { --account-surface: #151b18; --account-border: #2d3932; --account-text: #eff6ef; --account-muted: #a4b0a7; }
```

Use these classes for account surfaces, muted copy, rows, focus states, and destructive controls. Keep fixed 8px-or-less radii for operational controls.

- [ ] **Step 6: Run the focused UI metadata test**

Run: `npx tsx --test src/components/account/accountNavigation.test.ts`

Expected: section IDs and Chinese labels pass.

### Task 6: Surface Real Session State In The Account UX

**Files:**
- Modify: `重构/starmc/src/components/UserCenterView.tsx`
- Modify: `重构/starmc/src/lib/api.ts` or the existing security API adapter that owns `getSessions`, `revokeSession`, and `revokeOtherSessions`
- Test: `重构/starmc/src/lib/sessionProbe.test.ts`

- [ ] **Step 1: Add a failing session-summary normalization test**

```ts
test('renders a current Core session as a retained device', () => {
  const session = normalizeSession({ id: 'h-1', current: true, device: 'Edge', expiresAt: 2_592_000_000 })
  assert.equal(session.current, true)
  assert.equal(session.device, 'Edge')
})
```

- [ ] **Step 2: Load sessions only for the security section**

Keep the overview passive. When `activeTab === 'security'`, load Core-backed sessions, render the current device as non-revocable, and use a destructive confirmation before `revoke-all` alters other devices.

- [ ] **Step 3: Apply optimistic updates only after Core success**

```ts
await api.security.revokeOtherSessions()
setSessions((rows) => rows.filter((row) => row.current))
setActionMessage(_t('已退出其他设备', 'Other devices signed out'))
```

On failure, preserve the displayed rows and show a retryable error. Do not remove a device before the API confirms it.

- [ ] **Step 4: Run relevant frontend regression tests**

Run: `npx tsx --test src/lib/sessionProbe.test.ts src/components/account/accountNavigation.test.ts`

Expected: all session and account-navigation tests pass.

### Task 7: Configure And Verify The 30-Day Core Session Policy

**Files:**
- Modify: the deployed SuperTokens Core configuration that owns refresh-token validity.
- Modify: `docs/DEPLOY.md` with the exact 30-day verification and rollback procedure.

- [ ] **Step 1: Back up the running Core configuration**

On the production host, identify the process that serves the configured `SUPERTOKENS_CONNECTION_URI`, copy its active configuration to a timestamped backup, and record its current refresh-token lifetime without printing connection credentials.

- [ ] **Step 2: Set the explicit refresh-token target**

Apply the supported Core setting:

```yaml
refresh_token_validity: 2592000
```

Restart only the Core service after its configuration validates. Do not change cookie security flags, token transfer mode, domains, or SMTP values in this task.

- [ ] **Step 3: Verify real session creation**

In a browser, complete a new login without sharing the code. Confirm:

```text
POST /auth/signinup/code/consume -> 200 with cookie transfer
GET /api/auth/status -> authenticated: true
GET /api/user/me -> 200
reload /profile -> same authenticated account remains visible
```

- [ ] **Step 4: Verify revocation and rollback**

Create a second browser session, revoke it from `安全与设备`, and confirm its next protected request becomes unauthenticated. If Core health or session recovery fails, restore the timestamped Core configuration before restoring the static frontend release.

### Task 8: Run The Full Gate And Release

**Files:**
- Modify: frontend production `dist/` output.
- Deploy: `/www/wwwroot/star-web.top` with timestamped static backup.

- [ ] **Step 1: Run frontend verification**

Run:

```powershell
npm run test:unit
npm run lint
npm run build
```

Expected: unit tests pass, `tsc --noEmit` exits 0, and Vite produces the deployment bundle.

- [ ] **Step 2: Run backend verification**

Run:

```powershell
npm test
```

Expected: backend test suite exits 0, including Core session inventory coverage.

- [ ] **Step 3: Perform visual verification**

Check `/profile` in both themes and at desktop plus 390px mobile width. Confirm no gray opaque account surfaces, no clipped text, no English account labels while Chinese is selected, and no redirect flash during session restoration.

- [ ] **Step 4: Deploy with rollback readiness**

Upload the static artifact to a timestamped staging directory, verify its hash, atomically replace `/www/wwwroot/star-web.top`, and retain the old directory as `star-web.top.rollback-<timestamp>`. Do not restart PM2 unless backend changes are included in the release.

- [ ] **Step 5: Record the release evidence**

Record test counts, lint/build exit codes, Core policy validation, production page status, authenticated reload result, and the static rollback directory. Do not create a Git commit while the workspace Git metadata is unavailable.

## Plan Self-Review

- Spec coverage: Tasks 1-2 make device controls real; Tasks 3-4 cover session restoration and return paths; Tasks 5-6 implement the selected C UX and theme/copy repair; Task 7 owns the 30-day Core policy; Task 8 validates and deploys.
- Placeholder scan: no `TODO`, `TBD`, or deferred implementation steps remain.
- Type consistency: backend routes use the `sessionCore` adapter and `req.session.getUserId()` / `getHandle()` consistently; frontend redirects use `resolveReturnTo` in both route guard and login flows.
