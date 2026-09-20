# StarMC Cookie Session Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ensure SuperTokens browser authentication uses secure cookie transfer so a successful code or password authentication creates a usable session.

**Architecture:** Extend the shared frontend fetch helper with a path-scoped protocol header. `/auth/*` requests receive `st-auth-mode: cookie` unless a caller explicitly supplies another value; all other request behavior remains unchanged.

**Tech Stack:** React 19, TypeScript 5.8, Node test runner, tsx, Vite, SuperTokens Node 20.

---

## File Map

- Modify: `重构/starmc/src/shared/utils/apiResponse.ts` to select cookie transfer for SuperTokens endpoints.
- Create: `重构/starmc/src/shared/utils/apiResponse.test.ts` to protect request-header behavior.
- Modify: `重构/starmc/package.json` to include shared utility tests in `test:unit`.

### Task 1: Establish the Regression Test

**Files:**
- Create: `重构/starmc/src/shared/utils/apiResponse.test.ts`

- [ ] **Step 1: Write the failing test**

```ts
test('requests cookie transfer for SuperTokens endpoints', async () => {
  const request = await captureRequest('/auth/signinup/code')

  assert.equal(request.headers.get('st-auth-mode'), 'cookie')
})
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
npx tsx --test src/shared/utils/apiResponse.test.ts
```

Expected: FAIL because `st-auth-mode` is absent.

### Task 2: Add Scoped Cookie Transfer

**Files:**
- Modify: `重构/starmc/src/shared/utils/apiResponse.ts`
- Test: `重构/starmc/src/shared/utils/apiResponse.test.ts`

- [ ] **Step 1: Add the smallest helper**

```ts
function isSuperTokensPath(url: string): boolean {
  const path = url.startsWith('/') ? url : `/${url}`
  return path === '/auth' || path.startsWith('/auth/')
}
```

- [ ] **Step 2: Set the protocol header without overwriting callers**

```ts
if (isSuperTokensPath(url) && !headers.has('st-auth-mode')) {
  headers.set('st-auth-mode', 'cookie')
}
```

- [ ] **Step 3: Verify GREEN**

Run:

```powershell
npx tsx --test src/shared/utils/apiResponse.test.ts
```

Expected: auth requests use cookie mode, explicit caller values remain intact, and `/api` requests omit the header.

### Task 3: Run the Complete Frontend Gate

**Files:**
- Modify: `重构/starmc/package.json`

- [ ] **Step 1: Include shared utility tests in the existing unit command**

```json
"test:unit": "tsx --test src/lib/*.test.ts src/shared/utils/*.test.ts"
```

- [ ] **Step 2: Run verification**

```powershell
npm run test:unit
npm run lint
npm run build
```

Expected: all tests pass, TypeScript exits 0, and Vite emits a production build.

### Task 4: Deploy and Verify Cookie Session Creation

**Files:**
- Deploy: `重构/starmc/dist/`

- [ ] **Step 1: Stage and atomically switch the static release**

Back up the live frontend directory, upload the complete `dist` artifact to a timestamped temporary directory, validate file hashes, and atomically replace `/www/wwwroot/star-web.top`.

- [ ] **Step 2: Verify browser flow**

In the login page, request a new code, enter it locally, and verify `/api/auth/status` returns `authenticated: true` followed by a successful `/api/user/me` response. Do not copy verification codes into logs or chat.

- [ ] **Step 3: Roll back on failure**

Restore the timestamped frontend directory if session confirmation still returns a guest state.

