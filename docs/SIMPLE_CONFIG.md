# StarMC 简约配置（后端为主）

单一配置源：`tools/starmc-simple-config.env`

> 前端由独立仓库/目录维护时，默认使用 **backend-only** 模式，不生成 Vite env。  
> 若需连同旧版前端备份一并生成：`npm run config:doctor:all`

## 快速开始（后端）

```bash
# 仓库根目录
npm run config:doctor:simple   # 生成 backend + velocity env，校验，打印功能开关
npm run backend:dev            # 启动 API（读 重构/backend/.env.generated）
npm run backend:verify         # lint + 测试
```

## 配置链路（backend-only）

```
tools/starmc-simple-config.env
        │
        ▼  npm run config:doctor:simple
        │
        ├── 重构/backend/.env.generated
        └── velocity-test/.env.velocity.generated
```

本地覆盖（不提交 Git）：`tools/starmc-simple-config.local.env`

## 常用命令

| 命令 | 作用 |
|------|------|
| `npm run config:doctor` | 校验配置 + 打印功能开关（不写入） |
| `npm run config:doctor:simple` | 重新生成后端 env + 校验 + 摘要（默认 backend-only） |
| `npm run config:doctor:all` | 连同前端备份目录一并生成 Vite env |
| `npm run config:write` | 仅生成 env 文件（backend-only） |
| `npm run config:lint` | 仅校验（backend-only） |
| `npm run backend:dev` | 开发模式启动 API |
| `npm run backend:start` | 生产模式启动 API（simple 层） |
| `npm run backend:verify` | lint + 测试 + 安全基线 |
| `npm run backend:test` | 仅跑测试 |
| `npm run backend:doctor` | 检查 SuperTokens / PostgreSQL |
| `npm run plugin:probe` | 探测插件桥接 `bridge-status`（需 API 已启动） |
| `npm run config:frontend-env` | 生成新前端 env 模板 → `docs/frontend-env/` |
| `npm run frontend:dev` | 启动 `重构/starmc` 新前端（Vite 5173） |
| `npm run frontend:build` | 构建新前端 |

新前端对接详见 [FRONTEND_INTEGRATION.md](./FRONTEND_INTEGRATION.md)。新前端源码：`重构/starmc/`。

## 环境档位

| `STARMC_PROFILE` | 说明 |
|------------------|------|
| `local` | 默认。`127.0.0.1` + 固定端口，密钥可 `auto` |
| `production` | 官网 `https://star-web.top`（`STARMC_PUBLIC_SITE_URL`）、全部密钥、SMTP |

## 与 legacy `.env` 的关系

- **推荐**：只维护 `starmc-simple-config.env`，用 `config:doctor:simple` 生成。
- **兼容**：`重构/backend/.env` 仅在 `npm run dev`（overlay 模式）下覆盖生成值。
- 若实际行为与简约配置不一致，先重跑 `config:doctor:simple`，再检查 legacy 覆盖文件。

## 端口默认值

| 服务 | 端口 | 简约配置键 |
|------|------|------------|
| 前端 Vite | 5173 | `STARMC_FRONTEND_PORT` |
| 后端 API | 8787 | `STARMC_BACKEND_PORT` |
| 插件回调网关 | 8788 | `STARMC_PLUGIN_CALLBACK_PORT` |
| StarX/VLA HTTP | 8090 | `STARMC_VLA_HTTP_PORT` |
| Velocity 游戏 | 25579 | `STARMC_VELOCITY_MC_PORT` |
| SuperTokens Core | 3567 | `STARMC_SUPERTOKENS_PORT` |

## Velocity 联调 env（`.env.velocity.generated`）

`npm run config:doctor:simple` 会写入 `velocity-test/.env.velocity.generated`，供 StarVelocity/VLA 插件读取。与网站 API 的 URL 对照见 [`重构/backend/docs/PLUGIN_INTEGRATION.md`](../重构/backend/docs/PLUGIN_INTEGRATION.md)。

| 键 | 说明 |
|----|------|
| `VLA_WEBSITE_API_BASE` | 网站 API 根 URL（默认 `http://127.0.0.1:8787`） |
| `VLA_WEBHOOK_URL` | 通用插件回调 → `POST /api/v1/plugin/callback`（`login_success` / `login_failed` / `verify_premium` 等） |
| `VLA_BIND_EMAIL_WEBHOOK_URL` | 绑定邮箱专用 → `POST /api/v1/plugin/bind-email` |
| `VLA_SKIN_PROFILE_URL` | 皮肤档案模板 → `GET /api/public/skin-profile/{username}` |
| `VLA_WEBHOOK_SECRET` | 与后端 `AUTHX_VLA_WEBHOOK_SECRET` 一致 |
| `VLA_HTTP_BASE` | VLA HTTP 管理面（默认 `http://127.0.0.1:8090`） |

手工覆盖仍可使用 `velocity-test/.env.velocity`（后加载，优先级更高）。

## 生产部署检查清单

1. `STARMC_PROFILE=production`
2. 设置 `STARMC_PUBLIC_SITE_URL=https://star-web.top`（官网；游戏进服域名见前端 `serverAddresses.ts`）
3. 填写全部 `STARMC_*_SECRET`（勿用 `auto`）
4. 配置 `STARMC_SMTP_*`
5. `npm run config:doctor` 无 error
6. `npm run backend:doctor` SuperTokens 可达
7. 启动 API 后 `npm run plugin:probe` 确认 VLA 桥接 `ready=true`

功能开关详见 [FEATURE_FLAGS.md](./FEATURE_FLAGS.md)。生产部署见 [DEPLOY.md](./DEPLOY.md)。
