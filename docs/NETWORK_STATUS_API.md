# StarX Network Status API

StarX exposes its built-in HTTP API without downloading code or depending on another plugin.

## Exposure selection

At startup StarX selects the advertised base URL in this order:

1. A globally routable address assigned to a local network interface, when `http.bind` listens on that address family.
2. The administrator-configured `http.frp-public-url`.
3. The local bind address, marked as local-only.

No external IP-discovery service is called. StarX does not start or configure FRP; `frp-public-url` describes an FRP route already managed by the administrator.

```yaml
http:
  bind: "::"
  port: 8788
  frp-public-url: "https://api.example.com/starx"
```

Use `0.0.0.0` for public IPv4 interfaces and `::` for public IPv6 interfaces. Keep `127.0.0.1` when FRP terminates locally and direct public access is not required.

## Console output

StarX prints the selected source and every registered API route during startup:

```text
StarX API exposure source=FRP base=https://api.example.com/starx public=true
StarX API GET https://api.example.com/starx/v1/health access=public
StarX API GET https://api.example.com/starx/v1/network/status access=X-API-Key
```

The API key value is never printed.

## Network status

`GET /v1/network/status` requires the `X-API-Key` header and returns aggregate data only:

```json
{
  "collectedAt": "2026-07-17T06:14:25Z",
  "onlinePlayers": 0,
  "maxPlayers": 100,
  "servers": [
    {
      "name": "factions",
      "onlinePlayers": 0,
      "maxPlayers": 20,
      "skinProvider": "skinsrestorer",
      "skinBridge": "available",
      "bridgeState": "linked",
      "transport": "heartbeat-http",
      "capabilities": "bridge.http-exchange,bridge.v1,scheduler.main,server.status",
      "httpCommandsAccepted": "4",
      "httpCommandsDelivered": "4",
      "httpCommandsRejected": "0",
      "httpCommandsQueued": "0"
    }
  ]
}
```

Per-server online counts come from Velocity's active connections. Capacity prefers a recent StarX backend bridge report and falls back to the backend's Minecraft status ping, so an empty server still reports its configured maximum. An unavailable backend reports `0` instead of failing the whole network response.

`skinProvider` and `skinBridge` report the backend's reflected skin integration. StarX currently reads signed texture data from SkinsRestorer without linking its API at build time. When the provider is absent, the backend reports `none` and `unavailable`; StarX continues normally.

Skin profile requests prefer the connected player as the authenticated Minecraft plugin-message carrier. When that carrier cannot send and the backend advertises `bridge.http-exchange`, Velocity queues the request in that exact registered server's bounded mailbox; the next authenticated heartbeat carries it to Paper/Folia and returns the response without an online player. A successful backend response replaces only the player's `textures` GameProfile property and preserves unrelated properties. `transport=ping-only` means only ping capacity is known, `player-carrier` means the plugin-message bridge reported, and `heartbeat-http` means the authenticated empty-server exchange is active.

`httpCommandsAccepted`, `httpCommandsDelivered`, `httpCommandsRejected` and `httpCommandsQueued` are cumulative per-server transport counters. They let operators and the website distinguish “request accepted by Velocity” from “actually taken by the backend”; they contain no player identity.

## Empty-server probe

`POST /v1/admin/backend/probe` requires `X-API-Key` and a JSON body containing an exact Velocity registered-server name:

```json
{ "server": "factions" }
```

An accepted probe returns HTTP 202 with its correlation ID and queues `STATUS_REQUEST` only for that server. Invalid names return 400, unknown registered servers return 404, and a full mailbox returns 503. Compare the server's command counters through `GET /v1/network/status`; a delivered counter increment with queued returning to zero proves the empty backend took the command. The endpoint never falls back to an arbitrary first server.

The endpoint does not expose player identities, IP addresses, credentials, or database records. A website must call it from trusted server-side code; do not place the StarX API key in browser JavaScript.

The public StarMC adapter at `/api/server/player-stats` converts the four command counters to non-negative JSON numbers and also exposes aggregate/per-server online counts, capabilities and built-in Plan sampling metadata. It never forwards the StarX API key.

## Deployed test status

On 2026-07-19 the test network ran Velocity artifact `754BA07377E864D2A16E44828F122598DB2E5C0783C9EAD13A281C843C3340D9` with `factions` reporting `linked` over `heartbeat-http`. An empty-server probe advanced accepted and delivered from 0 to 1 while rejected and queued remained 0. The public `/api/server/player-stats` adapter returned `source=starx`, aggregate `0/100`, per-server `0/20`, capabilities and Plan samples without exposing the StarX API key.

On 2026-07-20 the test network was upgraded to Velocity artifact `82C86F4334E2535D995063830B42E4026AB246C05FFC33D1A8E89D7C83546F92`. With zero players online, `/v1/admin/skin-refresh` queued a real `SkinProbe` UUID/name request over the heartbeat mailbox. Counters reached accepted `1`, delivered `1`, queued `0`; Paper returned a SkinsRestorer profile with a 408-character texture value. The proxy logged only UUID, provider, found state and length, never the texture value or signature. Evidence: `docs/evidence/2026-07-20-binding-skin-bridge.txt`.
