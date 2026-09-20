# StarMC Account And Session UX Design

## Goal

Replace the current opaque, dark-only account layout with the selected minimal account experience and make an authenticated browser session reliably survive a refresh for up to 30 days.

## Scope

- Redesign the `/profile` experience around a minimal account overview.
- Make light and dark themes use explicit, readable surface and text tokens.
- Remove remaining hard-coded English from the account flow.
- Restore a valid SuperTokens cookie session before protected-route redirects.
- Configure a 30-day renewable browser session without storing tokens in `localStorage` or `sessionStorage`.
- Preserve existing profile, Minecraft, wardrobe, security, logout, and device-management capabilities.

## Non-Goals

- Do not create a second authentication system.
- Do not expose session tokens to JavaScript.
- Do not alter passwordless email delivery, account data, or unrelated catalogue screens.

## Experience

### Account Overview

`/profile` opens on a simple account overview instead of a translucent sidebar plus dark cards. It contains:

- Identity row: pixel avatar, display name, email-verification state, and linked Minecraft name when present.
- Session note: `本设备保持登录` with a short explanation that refreshes restore the session.
- Four clear navigation rows: `个人资料`, `游戏角色`, `皮肤库`, and `安全与设备`.
- Each row opens one focused account surface. On small screens, the rows remain full-width touch targets; on wider screens, content stays within one readable column rather than introducing nested cards.

The security surface owns active sessions, login history, alerts, TOTP, and sign-out-other-devices. The overview only summarizes that state and links into it.

### Theme And Copy Rules

The account shell uses semantic theme classes for page background, primary text, secondary text, outlines, muted panels, and destructive actions. It must not use dark-only utility combinations such as `bg-black/40` with `text-white` for a surface rendered in the light theme.

All labels use the existing `_t(zh, en)` helper. Chinese is the default output. English remains available only after the user switches language.

### Session Restoration

1. SuperTokens remains the only source of session truth.
2. `/auth/*` continues requesting cookie token transfer with `st-auth-mode: cookie`.
3. The SuperTokens Core refresh-token lifetime is explicitly configured to 2,592,000 seconds (30 days) through its supported production configuration. Active refreshes renew the browser session according to Core policy.
4. Browser code relies on secure HttpOnly cookies and never implements persistence by copying a token to web storage.
5. Application bootstrap waits for `loadSessionSnapshot` to resolve before a protected route decides whether to redirect to `/login`.
6. A valid restored session opens the requested route. A guest or expired session redirects once to `/login` and carries the intended return path.
7. Successful password or email-code login returns to that saved path, falling back to `/profile`.
8. Logout and device revocation clear the applicable SuperTokens session immediately, then reset the local bootstrap user state.

### Loading And Failure States

- Account routes show a stable skeleton while the first session probe is in progress.
- A `401` during session recovery means guest state, not an application error.
- Network or server failures show a concise retry state and retain the current route; they do not masquerade as a logout.
- The page does not render the account shell until the current session state is known.

## Technical Boundaries

- Frontend session ownership stays in `BootstrapContext` and `sessionProbe`.
- Authentication calls stay in `AuthContext` and `supertokensAuth`.
- The profile visual composition stays in `UserCenterView`; extract small presentational subcomponents only where this removes repeated theme and state logic.
- Server session lifetime is configured in the SuperTokens Core deployment configuration, then verified through the production session cookie behavior. Do not guess a local server-side expiry value when Core owns that setting.

## Verification

- Unit coverage proves a protected route waits for initial session restoration and preserves a return path.
- Unit coverage keeps the existing guest and `401` fallbacks intact.
- Unit coverage proves login success resolves the saved return path safely and rejects external redirect targets.
- Static/component coverage checks Chinese account labels and theme-safe account surfaces.
- Typecheck, unit suite, and production build pass.
- Browser checks cover light and dark modes at desktop and mobile widths, then confirm a refresh keeps an authenticated session and a guest route redirects only after probing completes.
- Production deployment preserves a timestamped static rollback directory; backend/Core configuration is backed up before changing session validity.

## Rollback

If production session restoration fails, restore the previous static release first. If the configured Core session lifetime causes a regression, restore the prior Core configuration and restart only the authentication service after its health check passes.
