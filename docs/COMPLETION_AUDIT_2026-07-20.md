# StarX Completion Audit

## Verified

| Area | Evidence |
| --- | --- |
| Self-contained Velocity artifact | `starx-velocity.jar`, SHA-256 `DCDC97E956100B6DD42C38E50AAEF8A035129113E76219231DFE13AC2A48CF80`; no external LimboAPI JAR |
| Java modules | 278 tests, zero failures; `UWORLD_GATE=PASS` |
| Uworld authentication | A01-A04, A06-A07, A10-A14 pass on the current SHA |
| Runtime environment | 23/23 doctor checks pass; see `docs/evidence/2026-07-20-uworld-doctor-current.log` |
| Test server deployment | Candidate and installed JAR hashes match; Velocity/Paper are running |
| Website frontend | 86 tests pass, TypeScript passes, production build passes |
| Website backend | 169 tests pass, lint passes |
| Production website | `/`, `/favicon.ico`, `/status`, `/api/health`, `/api/public/bootstrap`, and `/api/server/player-stats` return 200 |
| Published API contracts | AuthX OpenAPI, frontend OpenAPI, and API contract endpoints return 200; release deployment atomically backs up and switches backend docs |
| Live network bridge | `player-stats.source=starx`; factions is linked via heartbeat HTTP; Plan sample count is advancing |
| Website tunnel | `StarXWebsiteTunnel` scheduled task is running; the tunnel script has a global single-instance mutex |

## Not Verified

| Area | Required evidence |
| --- | --- |
| A05 premium authentication | A real Mojang/Microsoft account and an official client run |
| A08/A09 exact server identity | Official client runs covering the saved RegisteredServer object and equivalent instance behavior |
| D01-D11 official-client matrix | Official Mojang client evidence; Mineflayer probes remain implementation evidence only |
| Production binding E2E | A controlled production mailbox/account, token-link click, successful passwordless login, and replay rejection |
| Arbitrary third-party skin clients | Official-client matrix for each supported skin plugin, not only bridge/heartbeat evidence |

These rows intentionally remain `UNVERIFIED`; passing unit tests or Mineflayer probes cannot promote them to `PASS`.
