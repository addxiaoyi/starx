# 新前端对接指南

后端仓库维护 API；前端在**独立仓库/目录**开发。本文说明如何与 `重构/backend` 联调。

## 1. 快速开始

```bash
# 仓库根目录
npm run config:doctor:simple    # 生成 backend + velocity env
npm run config:frontend-env     # 生成可拷贝的前端 env 模板 → docs/frontend-env/
npm run backend:dev             # API 默认 8787
```

新前端项目将 `docs/frontend-env/` 下文件复制为 `.env.starmc.*`（或合并进 `.env`），并配置 Vite 代理（见 §4）。

## 2. 启动时拉取配置

应用启动时**先请求一次**（无需登录）：

```
GET /api/public/bootstrap
```

返回：

| 区块 | 用途 |
|------|------|
| `contractVersion` | API 契约版本，便于前端做兼容分支 |
| `site` | 站名、公开站点/API 根 URL |
| `features` | 功能开关（审核入口、邀请码、遥测、皮肤桥接等） |
| `auth` | SuperTokens 路径、OAuth 提供商、SMTP/开发兜底 |
| `realtime` | 皮肤库 SSE / 轮询路径与推荐间隔 |
| `endpoints` | 常用 API 相对路径索引 |
| `docs` | 在线契约与 OpenAPI 地址 |

兼容旧逻辑可继续用扁平的 `GET /api/public/site-features`（字段与 `features` 子集一致，含 `ok/success`）。

### 会话恢复流程

1. `GET /api/public/bootstrap`
2. `GET /api/user/me`（`credentials: 'include'`）
3. 已登录 → `GET /api/user/permissions` 做路由门禁
4. 未登录 → SuperTokens `/auth/*` 或邮箱验证码 `/api/auth/verify-code/*`

## 3. HTTP 客户端约定

- **Cookie 会话**：SuperTokens 使用 HTTP-only Cookie，不要用 localStorage 存 token。
- **credentials**：所有 API 请求使用 `credentials: 'include'`（跨域部署时必需）。
- **成功判定**：`ok === true` 或 `success === true`。
- **错误处理**：优先按 `code` 分支（409 冲突、429 限流等），再展示 `message`。
- **分页**：读 `pagination.*`，兼容 `total/page/pageSize`。

契约全文：`GET /api/docs/api-contract` 或 `重构/backend/docs/api-contract.md`。

## 4. 开发代理（Vite）

推荐**同源相对路径**（`fetch('/api/...')`），由 Vite 转发到后端：

| 路径 | 目标 |
|------|------|
| `/api` | `VITE_LOCAL_API_TARGET`（默认 `http://127.0.0.1:8787`） |
| `/auth` | 同上（SuperTokens） |
| `/uploads` | 同上 |
| `/healthz` | 同上 |

参考实现：`重构-前端备份-20260707-134037/vite.proxy.config.ts`。

生产同域部署时 Nginx 反代 `/api` + `/auth`；拆分部署时设置 `VITE_API_BASE` 为 `PUBLIC_API_BASE_URL`，并确保 `CORS_ORIGIN` 包含前端源。

## 5. 环境变量（Vite）

由 `npm run config:frontend-env` 生成：

| 变量 | 说明 |
|------|------|
| `VITE_PUBLIC_SITE_ORIGIN` | 公开站点 URL（生产：`https://star-web.top`；与游戏服 `star-mc.top` 不同） |
| `VITE_AUTH_API_DOMAIN` | 与站点同源时 = `VITE_PUBLIC_SITE_ORIGIN` |
| `VITE_AUTH_WEBSITE_DOMAIN` | 同上 |
| `VITE_AUTH_API_BASE_PATH` | 默认 `/auth` |
| `VITE_LOCAL_API_TARGET` | 开发代理目标（仅 development） |
| `VITE_API_BASE` | 生产跨域时的 API 根（仅 production） |
| `VITE_TELEMETRY_ENABLED` | 是否上报遥测（与后端 `TELEMETRY_ENABLED` 对齐） |
| `VITE_SKIN_LIBRARY_SSE_PATH` | 皮肤库 SSE 路径 |
| `VITE_SKIN_REALTIME_REFRESH_MS` | SSE 不可用时的轮询间隔 |

功能开关以运行时 `bootstrap.features` 为准，不要在前端硬编码。

皮肤库同步以 SSE 为主：连接建立后服务端先发 `hello`，审核批准会递增目录版本并广播 `catalog_sync`。健康连接期间前端不轮询目录；只有 SSE 断开时才按 `realtime.recommendedPollMs`（当前 15 秒）降级轮询，重连成功后立即停止轮询。目录记录中的纹理 URL 若返回 HTML、非图片或加载失败，3D 预览会稳定选择项目内 `/image/skins/skin1.png` 至 `skin12.png` 的一张作为备用，不把异步加载错误泄漏到控制台。

## 6. 主要 API 分区

| 分区 | 前缀 | 认证 |
|------|------|------|
| 公开引导 | `/api/public/*` | 无 |
| 认证 | `/auth/*`、`/api/auth/*` | 部分无 |
| 用户 | `/api/user/*` | Session |
| 皮肤 | `/api/skins/*` | 公开 + Session |
| 安全中心 | `/api/security/*` | Session |
| 遥测 | `/api/metrics/*`、`/api/telemetry` | 无/可选 |

OpenAPI 摘要：`GET /api/docs/openapi/frontend-public`  
插件/管理端：`GET /api/docs/openapi/authx`

## 7. 实时更新

- **皮肤库**：`EventSource('/api/skins/library/stream', { withCredentials: true })`
- **通知/安全**：无 WebSocket；使用轮询或用户操作后刷新

## 8. 最小骨架代码

可直接拷贝到新前端项目：

| 文件 | 说明 |
|------|------|
| `docs/frontend-skeleton/vite.config.ts` | Vite 入口 |
| `docs/frontend-skeleton/vite.proxy.ts` | `/api` `/auth` `/uploads` 代理 |
| `docs/frontend-skeleton/src/lib/apiFetch.ts` | 统一 fetch（Cookie + 错误码） |
| `docs/frontend-skeleton/src/lib/bootstrap.ts` | 启动引导 + 会话探测 |
| `docs/frontend-skeleton/src/main.example.ts` | 入口示例 |

```bash
# 后端仓库生成 env 后复制到新前端根目录
npm run config:frontend-env
cp docs/frontend-env/.env.starmc.*.generated <你的前端>/
```

## 9. 参考代码

| 资源 | 路径 |
|------|------|
| 旧版 Vue 参考 | `重构-前端备份-20260707-134037/` |
| API 响应解析 | `src/shared/utils/apiResponse.ts` |
| 皮肤 SSE | `src/features/skin/api.ts` |
| MC 绑定 | `src/features/mc-binding/` |
| 功能开关文档 | `docs/FEATURE_FLAGS.md` |
| 简约配置 | `docs/SIMPLE_CONFIG.md` |

## 10. 联调检查清单

- [ ] `npm run backend:dev` 后 `GET /api/public/bootstrap` 返回 200
- [ ] `GET /api/user/me` 未登录返回 401（非 500）
- [ ] Vite 代理 `/api` + `/auth` 可达
- [ ] `bootstrap.features.reviewEntryEnabled` 与 UI 审核入口一致
- [ ] 跨域部署时 `CORS_ORIGIN` 与 `credentials: 'include'` 已验证
- [ ] 插件联调：`npm run plugin:probe` → `ready=true`
