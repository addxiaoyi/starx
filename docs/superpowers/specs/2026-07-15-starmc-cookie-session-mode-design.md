# StarMC Cookie Session Mode Design

## Goal

Make successful SuperTokens password and email-code authentication establish a browser-managed cookie session before the frontend refreshes the user profile.

## Evidence

- `POST /auth/signinup/code/consume` returns successfully in the browser.
- `AuthContext.confirmSession()` immediately receives no user from `refreshUser()`.
- `apiFetch()` already uses `credentials: 'include'`, but it does not send SuperTokens' `st-auth-mode` request header.
- With SuperTokens Session's default `any` transfer mode, a missing `st-auth-mode: cookie` can select response-header token transfer. Browser fetch does not persist those response headers as a session, leaving `/api/auth/status` unauthenticated.

## Chosen Design

Keep the backend Session recipe, SMTP delivery, Nginx proxy, and existing cookie security settings unchanged. In the shared frontend `apiFetch()` helper, add `st-auth-mode: cookie` only for paths under `/auth` when the caller did not set the header explicitly.

This covers native Passwordless create and consume, password sign-in and sign-up, token refresh, and sign-out without adding the header to unrelated `/api` calls. Existing `credentials: 'include'` then allows the browser to store and return SuperTokens' Secure, HttpOnly session cookies.

## Error Handling

- Preserve any caller-provided `st-auth-mode`; do not overwrite an explicit protocol choice.
- Keep existing API error parsing unchanged.
- Do not log session tokens, cookie values, challenges, verification codes, or full email addresses.

## Security Properties

- Tokens remain server-issued Secure, HttpOnly cookies.
- No frontend token storage is introduced.
- The change applies only to SuperTokens endpoints and does not weaken CSP, CORS, CSRF protections, or backend authorization.

## Test Strategy

1. Add a focused unit test that records fetch headers for `/auth/signinup/code`.
2. Verify it fails before implementation because the cookie transfer mode header is absent.
3. Add the smallest path-scoped header helper.
4. Verify auth endpoints receive the header, explicit caller headers are preserved, and `/api` endpoints remain unchanged.
5. Run all frontend unit tests, TypeScript checking, and a production build.
6. Deploy the new static build atomically and perform a real browser code-login test without exposing the verification code.

