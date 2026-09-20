# 功能开关与生产环境配置

所有开关在 `tools/starmc-simple-config.env` 中维护，由 `npm run config:doctor:simple` 写入 `重构/backend/.env.generated`。

运行时也可通过 `GET /api/public/site-features` 或 `GET /api/public/bootstrap` 查看当前生效状态（供前端展示入口）。

## 开关一览

| 简约配置键 | 后端环境变量 | 默认 (local) | 说明 |
|------------|--------------|--------------|------|
| `STARMC_REVIEW_ENTRY_ENABLED` | `REVIEW_ENTRY_ENABLED` | `false` | 皮肤投稿审核 API 与前端审核入口 |
| `STARMC_INVITE_CODES_REQUIRED` | `INVITE_CODES_REQUIRED_FOR_MC_LINK` | `false` | 绑定 MC 是否必须邀请码 |
| `STARMC_INVITE_CODES` | `INVITE_CODES` | 空 | 邀请码白名单（逗号分隔，一码一账号） |
| `STARMC_REQUIRE_TOTP_FOR_MC_LINK` | `REQUIRE_TOTP_FOR_MC_LINK` | `false` | 绑/解绑 MC 是否要求 TOTP |
| `STARMC_MC_GAME_PASSWORD_RESET_ENABLED` | `MC_GAME_PASSWORD_RESET_ENABLED` | `true` | 游戏库密码邮件重置 |
| `STARMC_MOJANG_SKIN_SYNC_ENABLED` | `MOJANG_SKIN_SYNC_ENABLED` | `true` | 绑定后同步 Mojang 皮肤 |
| `STARMC_TELEMETRY_ENABLED` | `TELEMETRY_ENABLED` | `true` | 前端遥测采集 |
| `STARMC_SKIN_BRIDGE_PUBLIC_PROFILE` | `SKIN_BRIDGE_PUBLIC_PROFILE` | `true` | 公开皮肤档案（VLA 拉取） |
| `STARMC_VLA_NOTIFY_ON_SKIN_CHANGE` | `VLA_NOTIFY_ON_SKIN_CHANGE` | `true` | 改皮肤后通知插件 refresh |
| `STARMC_GEO_BLOCKED_COUNTRIES` | `GEO_BLOCKED_COUNTRIES` | 空 | 地区写操作黑名单（ISO 3166-1 alpha-2） |

## 生产环境推荐

### 公开上线（标准服）

```env
STARMC_PROFILE=production
STARMC_PUBLIC_SITE_URL=https://www.example.com
STARMC_PUBLIC_API_URL=https://www.example.com
STARMC_TRUST_PROXY=true

STARMC_REVIEW_ENTRY_ENABLED=false
STARMC_INVITE_CODES_REQUIRED=false
STARMC_MC_GAME_PASSWORD_RESET_ENABLED=true
STARMC_MOJANG_SKIN_SYNC_ENABLED=true
STARMC_TELEMETRY_ENABLED=true
```

### 内测 / 白名单服

```env
STARMC_INVITE_CODES_REQUIRED=true
STARMC_INVITE_CODES=ALPHA2026,BETA2026
STARMC_REVIEW_ENTRY_ENABLED=true
```

### 高安全

```env
STARMC_REQUIRE_TOTP_FOR_MC_LINK=true
STARMC_GEO_BLOCKED_COUNTRIES=
# 按运营策略填写，如 XX,YY
```

## 依赖关系

| 功能 | 前置条件 |
|------|----------|
| 游戏密码重置 | `AUTHX_PLUGIN_ADAPTER=vla`、插件 HTTP 写接口、SMTP |
| 审核入口 | `REVIEW_ENTRY_ENABLED=1` + 审核员/管理员角色 |
| 邀请码绑定 | `INVITE_CODES_REQUIRED=1` + 非空 `INVITE_CODES` |
| TOTP 绑 MC | 用户已在个人中心启用 TOTP |
| 皮肤桥接 | `PUBLIC_API_BASE_URL` 可被 Velocity 访问（勿用 localhost 跨机） |
| 地区封锁 | 反代提供 `CF-IPCountry` 或 `X-Country-Code` |

## 变更流程

1. 修改 `tools/starmc-simple-config.env`（或本地 `starmc-simple-config.local.env`）
2. `npm run config:doctor:simple`
3. 重启后端进程
4. 访问 `/api/public/site-features` 或看 `config:doctor` 摘要确认

## 常见误配

| 现象 | 可能原因 |
|------|----------|
| 审核按钮不显示 | `REVIEW_ENTRY_ENABLED=false` |
| 绑 MC 不要邀请码但提示需要 | `INVITE_CODES_REQUIRED=true` |
| 改游戏密码 503 | SMTP 未配或 VLA 不可达 |
| 皮肤进服不更新 | `VLA_NOTIFY_ON_SKIN_CHANGE=0` 或 `PUBLIC_API_BASE_URL` 错误 |
| 配置改了不生效 | 未重跑 `config:doctor:simple` 或未重启 API |
