# Website Production Stability Implementation Plan

> **For agentic workers:** Execute inline in this session with test-first checkpoints. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the StarMC website deployment observable and recoverable without changing the accepted 30-minute login bypass policy.

**Architecture:** Add a small pure readiness contract for production dependencies, expose it through a dedicated `/api/ready` endpoint, and redeploy the already-tested backend source as an atomic remote update. Set only the missing public API origin in production; leave administrator-secret policy unchanged because enabling an unused administrative surface requires a separate authorization decision.

**Tech Stack:** Node.js 22, Express, SuperTokens Core, PM2, PowerShell/OpenSSH, Node test runner.

---

### Task 1: Add the production readiness contract

**Files:**
- Create: `重构/backend/src/services/productionReadiness.js`
- Create: `重构/backend/tests/production-readiness.test.js`

- [ ] **Step 1: Write the failing tests**

Cover production auth ready, production auth unavailable, and development auth optional cases. The expected result is a payload with `ready`, `ok`, and a `checks.supertokens` record.

- [ ] **Step 2: Run the focused test and confirm it fails**

Run `node --test tests/production-readiness.test.js` from `重构/backend`. It must fail because the readiness module does not exist yet.

- [ ] **Step 3: Implement the minimal pure helper**

Implement `buildProductionReadiness({ production, supertokensConfigured, supertokensReady })`. Production is ready only when SuperTokens is configured and ready; development remains ready when authentication is intentionally optional.

- [ ] **Step 4: Run the focused test and confirm it passes**

Run the same command and require all cases to pass.

### Task 2: Expose readiness without breaking the existing health probe

**Files:**
- Modify: `重构/backend/src/server.js`
- Test: `重构/backend/tests/production-readiness.test.js`

- [ ] **Step 1: Add the route after `/api/health`**

Return HTTP 200 with the readiness payload when ready and HTTP 503 with the same JSON shape when a required production dependency is unavailable. Keep `/api/health` backward-compatible at HTTP 200.

- [ ] **Step 2: Run the backend readiness and existing auth/network tests**

Run `node --test tests/production-readiness.test.js tests/supertokens-imports.test.js tests/starx-network-health.test.js tests/starx-network-status.test.js` from `重构/backend`.

### Task 3: Roll out the tested backend and missing public origin

**Files:**
- Remote: `/www/wwwroot/starmc-api/src/server.js`
- Remote: `/www/wwwroot/starmc-api/src/services/productionReadiness.js`
- Remote: `/www/wwwroot/starmc-api/.env.generated`

- [ ] **Step 1: Back up the exact remote files**

Create a timestamped backup under `/www/wwwroot/starmc-api/backups/` before copying any source or environment file.

- [ ] **Step 2: Copy the tested source files**

Copy the local `server.js` and readiness module over SSH, preserve ownership and permissions, and verify SHA-256 values before restarting PM2.

- [ ] **Step 3: Set `PUBLIC_API_BASE_URL` only if absent**

Add `PUBLIC_API_BASE_URL=https://star-web.top` to `.env.generated` without printing any secret values. Do not alter `password-bypass-minutes`, `DEV_ADMIN_SECRET`, webhook secrets, or API keys.

- [ ] **Step 4: Restart and verify the service**

Restart `starmc-api`, wait for `/api/ready` to return 200, then verify public health, bootstrap, plugin-network, PM2 restart stability, and the absence of new SuperTokens import/config warnings.

### Task 4: Record residual acceptance gaps

**Files:**
- Modify: `docs/DEPLOY.md`

- [ ] **Step 1: Document `/api/ready` and the public origin requirement**

Add the readiness probe to the deployment checklist and state that the accepted same-IP bypass remains 30 minutes.

- [ ] **Step 2: Run the final focused checks**

Run the backend focused tests and direct public endpoint checks. Report any OAuth or plugin heartbeat failures separately instead of treating `/api/health` alone as proof of full readiness.
