# StarMC Production Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the unsafe mock deployment path, eliminate actionable dependency findings, restore production site configuration, and bring every listed game node into the website skin bridge.

**Architecture:** Keep `重构/backend` as the only production API. The Vite project only builds static assets; its in-memory mock remains explicitly opt-in for local demonstrations. Production configuration is verified through public bootstrap/status contracts and node registration is accepted only after each node advertises `skin.refresh` and receives a refresh command.

**Tech Stack:** Node.js, Vite, Express, SuperTokens, StarX/Velocity, Paper, PowerShell, Playwright, curl.

---

### Task 1: Separate the frontend build from the mock API

**Files:**
- Modify: `重构/starmc/package.json`
- Create: `重构/starmc/scripts/productionPackageScripts.test.mjs`

- [ ] **Step 1: Write the failing test**

```js
test('production scripts never build or start the demonstration server', () => {
  assert.equal(scripts.build, 'vite build')
  assert.equal('start' in scripts, false)
  assert.equal('deploy:backend' in scripts, false)
  assert.match(scripts['build:mock'], /dist-mock[\\/]server\.cjs/)
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `npm run test:unit -- scripts/productionPackageScripts.test.mjs`

Expected: failure because `build` creates `dist/server.cjs` and `start` exists.

- [ ] **Step 3: Implement the script split**

Make `build` static-only, place the explicit mock artifact in `dist-mock/`, and remove production-looking mock start/deploy scripts.

- [ ] **Step 4: Run the unit test and production frontend build**

Run: `npm run test:unit -- scripts/productionPackageScripts.test.mjs` and `npm run build:frontend`.

Expected: test and production build pass; `dist/` has no mock server.

### Task 2: Resolve dependency findings without changing runtime contracts

**Files:**
- Modify: `重构/starmc/package.json`
- Modify: `重构/starmc/package-lock.json`
- Modify: `重构/backend/package-lock.json`

- [ ] **Step 1: Record the failing audit result**

Run: `npm audit --omit=dev --json --registry=https://registry.npmjs.org` in both packages.

Expected: frontend reports PostCSS/React Router and backend reports brace-expansion.

- [ ] **Step 2: Update production dependencies and lockfiles**

Keep Vite in `devDependencies` only, update React Router to a non-affected upstream version, and update the ESLint/minimatch chain to remove `brace-expansion@1.1.16`.

- [ ] **Step 3: Verify the audit and build gates**

Run both audits, frontend `tsc --noEmit`, frontend unit tests, and backend tests.

Expected: no high/critical audit findings and all existing gates pass.

### Task 3: Restore production web configuration and administrator access

**Systems:**
- Production StarMC API configuration
- GitHub OAuth administration allow-list
- Admin site configuration

- [ ] **Step 1: Set the canonical origin**

Set `STARMC_PUBLIC_SITE_URL=https://star-web.top`, regenerate the backend environment, restart the API, then require `/api/public/bootstrap` to return `site.publicSiteOrigin` as `https://star-web.top`.

- [ ] **Step 2: Verify GitHub administrator assignment**

Ensure `OAUTH_GITHUB_ADMIN_EMAILS` includes the GitHub-verified `2293237813@qq.com`, sign in, and require `/api/user/permissions` to include administrator capability before opening `/admin`.

- [ ] **Step 3: Save non-fictional public configuration**

From the administrator dashboard, set the documented game hosts `star-mc.top`, `mc.star-mc.top`, and `max.star-mc.top`; leave sponsors and acknowledgements empty until real account IDs are supplied. Require the public config endpoint to retain the addresses.

### Task 4: Enroll missing nodes and prove skin propagation

**Systems:**
- StarX/website-sync on `ChaserGamer`, `ChaserLobby`, `CrystalFFA`, `lk`, `lobby2`, `oldsky`, `skyblock`, `thepit`, and `zombie`

- [ ] **Step 1: Generate per-node bootstrap credentials from the administrator bridge panel**

Each credential must include `network.status` and `skin.refresh`; never reuse a credential between nodes.

- [ ] **Step 2: Install the generated enrollment configuration and restart each node**

Require each node to report `transport=heartbeat-http`, `status=linked`, and `skinBridge=available` through `/api/server/player-stats`.

- [ ] **Step 3: Perform an authenticated skin refresh through the website**

Change a linked test player texture, require a delivered refresh count on every enrolled node, and confirm the texture in a real game client after reconnecting.

### Task 5: Final acceptance

- [ ] **Step 1: Run repository checks**

Run `npm run backend:verify`, `npm run frontend:verify`, `npm run production:test`, and the targeted package tests.

- [ ] **Step 2: Run production checks without the local proxy**

Require `200` from health, bootstrap, Wiki, and player stats; require 12 linked skin-bridge nodes; require a GitHub administrator session to render the Wiki and administrator pages.
