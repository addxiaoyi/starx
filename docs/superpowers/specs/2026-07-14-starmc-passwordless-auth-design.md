# StarMC Passwordless Authentication Design

## Goal

Make email verification-code authentication production-ready. A verified email must sign in an existing account or create a new account automatically, then establish the same secure SuperTokens cookie session used by password authentication.

The change also fixes password-auth response handling, browser autocomplete warnings, and the skin-library SSE reconnect guard reported in the same production trace.

## Current Failures

1. The custom verification-code sender stores a code but never sends email through SMTP.
2. Verification confirmation calls `Session.createNewSession` with the pre-v20 signature. The installed `supertokens-node@20.1.7` requires `(req, res, tenantId, recipeUserId, ...)`.
3. The production login path can create a local fallback user without a SuperTokens session when the email does not exist.
4. Password sign-in and sign-up treat SuperTokens HTTP 200 business errors as success because the response `status` is not validated.
5. Login inputs omit appropriate `autocomplete` attributes.
6. `useSkinLibrarySync` checks `pollTimer` before scheduling an SSE reconnect, but a polling timer always exists, so reconnect is never scheduled.

## Chosen Approach

Use the native SuperTokens Passwordless recipe with email user-input codes. Keep EmailPassword enabled for conventional password sign-in and sign-up, and keep Session as the single cookie-session implementation.

This avoids hidden generated passwords, custom code storage, weak random-number generation, and manually constructed sessions. SuperTokens owns code lifecycle, attempt limits, account creation, account linking, and recipe user IDs.

## Backend Architecture

### Recipe Initialization

Initialize recipes in this order:

1. `EmailPassword.init()`
2. `Passwordless.init()` with `contactMethod: "EMAIL"` and `flowType: "USER_INPUT_CODE"`
3. `Session.init()`

The Passwordless email-delivery service will call the existing SMTP configuration through a small Nodemailer adapter. Startup must fail loudly when email-code login is enabled in production but required SMTP fields are missing.

### SMTP Adapter

The adapter will:

- read host, port, secure mode, username, password, sender name, and sender address from existing configuration;
- validate required configuration during initialization;
- send a bilingual plain-text and HTML message containing the user-input code and expiry time;
- never log the code, SMTP password, full message body, or session tokens;
- log only request correlation ID, delivery outcome, latency, and a masked recipient;
- throw a contextual internal error on delivery failure while returning a generic user-facing error.

### Native Passwordless API

The frontend will use SuperTokens' native API paths beneath `/auth`:

- create code: `POST /auth/signinup/code`
- consume code: `POST /auth/signinup/code/consume`

The create response supplies `deviceId` and `preAuthSessionId`. The consume request submits those values with `userInputCode`. On success, SuperTokens creates or links the user and writes HttpOnly session cookies.

The existing `/api/auth/verify-code/*` routes remain temporarily available during the backend-first rollout, but the frontend stops using them. They will no longer be allowed to create production local-user fallbacks. Removal can happen in a later cleanup after the compatibility window.

### Local Profile Synchronization

After a successful Passwordless consume response, normal authenticated profile loading remains the source of truth. `/api/user/me` resolves the SuperTokens primary user and creates or updates the local StarMC profile through the existing synchronization helper.

No local user is created before SuperTokens confirms the code and creates the recipe user.

## Frontend Architecture

### Passwordless Flow State

The login view tracks an explicit challenge object:

```ts
interface EmailCodeChallenge {
  email: string
  deviceId: string
  preAuthSessionId: string
}
```

Sending a code stores the challenge. Confirming a code requires the stored challenge and submits it to the consume endpoint. A successful consume is not considered complete until `/api/auth/status` reports `authenticated: true` and the profile refresh returns a user.

Changing the email or returning to step one clears the previous challenge and entered code.

### Password Response Validation

Password sign-in and sign-up use a shared SuperTokens response parser. It maps:

- `OK` to success;
- `WRONG_CREDENTIALS_ERROR` to a generic email-or-password error;
- `EMAIL_ALREADY_EXISTS_ERROR` to an existing-account message;
- `FIELD_ERROR` to the first safe field validation message;
- unknown statuses to a generic authentication failure while logging status context without credentials.

Every authentication method verifies the resulting session before navigating to `/profile`.

### Form Semantics

Inputs receive stable names and autocomplete hints:

- email: `name="email"`, `autoComplete="email"`
- sign-in password: `name="password"`, `autoComplete="current-password"`
- sign-up password: `name="new-password"`, `autoComplete="new-password"`
- verification code: `name="one-time-code"`, `autoComplete="one-time-code"`, `inputMode="numeric"`
- username: `name="username"`, `autoComplete="username"`

Buttons keep disabled and loading states. Errors remain visible in the form and no failed request redirects the user.

## Security Properties

- Session tokens remain in Secure, HttpOnly cookies managed by SuperTokens.
- Codes are generated and validated by SuperTokens rather than `Math.random` and local JSON storage.
- Account creation occurs only after proof of mailbox possession.
- Rate limits remain at the public endpoint boundary, with SuperTokens attempt limits as a second layer.
- Create-code responses do not disclose whether an email already exists.
- Authentication logs exclude passwords, codes, cookies, full email addresses, and response bodies.
- Production never falls back to development bearer users or local-only verification users.
- All password and code inputs are validated at the request boundary.

## SSE Correction

`useSkinLibrarySync` schedules a 15-second SSE reconnect when no reconnect timer is already pending. The 60-second poll remains independent as a fallback. A successful reconnect clears the pending reconnect state, and cleanup cancels both timers and closes the EventSource.

The browser may still report a closed EventSource during navigation or server restarts; the functional requirement is automatic recovery without duplicate EventSource connections or timers.

## Test Strategy

Implementation follows red-green-refactor.

### Frontend Unit Tests

- password sign-in rejects `WRONG_CREDENTIALS_ERROR`;
- password sign-up rejects `EMAIL_ALREADY_EXISTS_ERROR` and `FIELD_ERROR`;
- verification-code send persists the native challenge identifiers;
- consume submits the stored challenge and code;
- a consume response without an authenticated session is rejected;
- form metadata exposes the expected autocomplete values through extracted form-field helpers;
- SSE schedules one reconnect after an error even while polling is active.

### Backend Unit Tests

- Passwordless recipe configuration is enabled for email user-input codes;
- SMTP delivery maps the code and expiry into the message without logging secrets;
- missing production SMTP configuration fails initialization;
- legacy verification-code confirmation cannot use a local fallback in production;
- local profile synchronization occurs only after a valid SuperTokens identity exists.

### Integration and Production Verification

- run the full backend test and lint suites;
- run frontend unit tests, TypeScript checking, and a clean production build;
- deploy backend first and verify password endpoints plus Passwordless create/consume contracts;
- deploy frontend second with a timestamped backup and atomic cutover;
- use a fresh disposable mailbox to verify received code, automatic account creation, HttpOnly session establishment, profile load, logout, and subsequent code sign-in;
- verify wrong code, expired code, duplicate email password sign-up, and wrong password stay on the login page with safe errors;
- inspect browser console and network for autocomplete warnings, unexpected 401 responses, duplicate SSE connections, and leaked secrets.

## Deployment and Rollback

1. Back up backend authentication files and deploy Passwordless support while retaining legacy endpoints.
2. Restart PM2 and smoke the existing password flow plus new native code endpoint.
3. Build the frontend into a clean output directory and preflight its auth endpoint strings.
4. Atomically switch the frontend directory and retain a timestamped backup.
5. Complete the disposable-mailbox end-to-end test.

If backend smoke checks fail, restore backend files and restart PM2. If frontend checks fail, atomically restore the previous frontend directory. No schema migration or destructive user operation is part of this change.

## Acceptance Criteria

- A valid email code signs in an existing user or creates a new user automatically.
- The browser receives a valid SuperTokens cookie session and `/api/auth/status` returns `authenticated: true`.
- The verification email is delivered through configured SMTP.
- Password authentication surfaces SuperTokens business errors accurately.
- No authentication path reports success without a confirmed session.
- Production never creates a local-only fallback identity.
- Login fields produce no Chromium autocomplete warnings.
- Skin-library SSE reconnects after failure while polling remains available.
- All targeted and full validation commands pass before deployment.
