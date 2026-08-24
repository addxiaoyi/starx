# StarX 插件—网站适配审计 — 2026-07-24

## 结论

插件与网站的**工程、接口契约、本地运行和生产技术链路已经完成原生 StarX 适配**。网站不再依赖旧 VLA 路由才能完成状态、账号关联、皮肤刷新或密码重置；旧路径被保留为兼容入口，并统一映射到插件当前 `/v1` API。

```text
PLUGIN_WEBSITE_TECHNICAL_ADAPTATION=PASS
PLUGIN_WEBSITE_PRODUCTION_ROUTE_PROBE=PASS
PLUGIN_WEBSITE_REAL_ACCOUNT_E2E=UNVERIFIED
```

该结论不等于已使用真实生产用户修改账号。生产探针仅使用不存在用户名验证路由、鉴权和业务响应；真实账号绑定、密码修改和跨设备批准仍保持 `UNVERIFIED`。

## 本轮修复

### 路由统一

网站旧调用现在映射为：

| 兼容调用 | StarX 标准路由 |
|---|---|
| `/v1/server/status` | `/v1/network/status` |
| `/api/plugin/authx/admin/skin-refresh` | `/v1/admin/skin-refresh` |
| `/api/plugin/authx/admin/reset-password` | `/v1/admin/reset-password` |
| `/internal/v1/link/external-user` | `/v1/admin/link-external-user` |

新增插件路由：

```text
GET /v1/security/events
```

网站用户画像改接：

```text
GET /v1/user/overview?name=<linked-name>
```

### 原生配置统一

所有网站到插件的 GET/POST 请求统一使用：

```text
STARX_NETWORK_API_BASE
STARX_NETWORK_API_KEY
```

旧配置仍作为兼容回退：

```text
AUTHX_PLUGIN_BASE
AUTHX_VLA_API_KEY
```

生产首次探针发现状态客户端使用原生配置，但写请求仍只读取旧密钥。该缺口已修复、重建、重新发布并在生产主机端到端复测。

### 账号关联与解绑

插件 `/v1/admin/link-external-user` 现在支持：

- 非空 `externalUserId`：绑定网站用户。
- 空字符串：解绑并将数据库 `external_user_id` 清空。
- 响应返回 `linked` 状态。

本地测试账号已执行完整的“保存原值 → 绑定 → 数据库确认 → 解绑 → 数据库确认 → 恢复原值”流程，全部通过，且原始状态已恢复。

### 安全事件

插件新增按用户名读取事件时间线：

```text
GET /v1/security/events?name=<username>&limit=<1-100>
```

接口使用现有 `IncidentTimeline`，按 payload 中的用户名进行不区分大小写过滤，不新增独立、不可审计的数据源。

## 安全模型

网站到插件：

- `X-API-Key` 常量时间校验。
- 通过反向 SSH 隧道访问本地监听端口。
- 插件侧全局限流和敏感接口限流。
- 网站侧重试、断路器和调用计数。

插件到网站：

- StarX HMAC V2。
- 请求方法、目标路径、时间戳和原始 body 参与签名。
- 五分钟时间窗。
- 签名重放拒绝。
- 事件 ID 去重。
- Webhook 重试和磁盘 outbox。

网站到插件当前未启用 HMAC 请求签名，而是使用 API Key 和反向隧道。这是已验证的生产设计，不影响本次接口适配结论；若未来插件 API 暴露到公网，应迁移为双向 HMAC 或 mTLS。

## 自动门禁

| 范围 | 结果 |
|---|---:|
| 后端 | 191/191，46 suites，0 failures |
| 前端 | 95/95，TypeScript 与生产构建 PASS |
| Java | 220 suites / 590 tests，0 failures，0 errors |
| 跨项目路由契约 | PASS，缺失路由 0 |
| Velocity/Paper 构建 | PASS |

跨项目测试会扫描网站中的字面插件调用和插件 Java 注册路由；兼容映射后任何网站调用若没有对应插件路由，测试将失败。

## 本地运行验收

当前安装候选：

```text
starx-velocity.jar
SHA-256 7d0d2856f976ac460a3c1e1b81b76c8a7b77a272b1cc9095e02390078606af59

starx-server.jar
SHA-256 e0281e5c4193f0eb4ad1df5b4435c16e318602633160d9d6dc91d318a949fdfe
```

本地部署返回：

```text
STARX_LOCAL_DEPLOY=PASS
```

已验证：

- `/v1/health`。
- `/v1/network/status`。
- `/v1/security/events`。
- `/v1/user/overview`。
- `/v1/admin/link-external-user`。
- `/v1/admin/skin-refresh`。
- 可逆绑定与解绑持久化。
- Paper、Velocity、8788 API 监听和 watchdog 恢复。

## 生产发布与探针

网站候选：

```text
manifest_entries=244
manifest_sha256=4f13145bdcd35b3cccf61b636224205f643bf62b3162dae964f980e967eb9f80
server_sha256=8c40c4f0ee152cbc4d88a362178baca8c9c0b5a36e30127b1102aa7a03526300
route_mapper_sha256=8573105bd112a2510f3ec265b162a9c1656730269b78f15f96cc0995961350c3
```

远端源码哈希与本地候选完全一致。最新回滚副本：

```text
/www/wwwroot/star-web.top.rollback-20260724-041020
/www/wwwroot/starmc-api.rollback-20260724-041020
```

生产主机探针加载了**已部署的路由映射模块**，通过生产 env layering、反向隧道和原生 StarX API Key 实际请求插件。结果：

| 请求 | 映射结果 | 响应 |
|---|---|---:|
| 旧状态路由 | `/v1/network/status` | 200，1 个节点 |
| 安全事件 | `/v1/security/events` | 200 |
| 用户概览 | `/v1/user/overview` | 404，不存在用户 |
| 旧皮肤刷新 | `/v1/admin/skin-refresh` | 404，不存在用户 |
| 旧密码重置 | `/v1/admin/reset-password` | 400，不存在用户 |
| 旧账号关联 | `/v1/admin/link-external-user` | 404，不存在用户 |

这些结果证明映射、隧道、认证和插件业务路由均已到达；探针未修改真实账号。

生产公网连续 5 次采样全部满足：

```text
health=200
ok=true
bridge=true
coreReachable=true
adapter=starx
mcGamePasswordReset=true
playerStats.source=starx
playerStats.servers=1
```

## 仍为 UNVERIFIED

以下项目必须使用受控真实账号完成，不能由不存在用户探针或单元测试替代：

- 真实生产网站用户绑定和解绑 Minecraft 角色。
- 真实生产 Minecraft 密码修改。
- 真实跨设备批准，包括邮箱绑定和皮肤账号绑定。
- 真实邮箱验证码投递与消费。
- 真实 GitHub OAuth 回调和会话创建。

## 状态

```text
PLUGIN_WEBSITE_ROUTE_CONTRACT=PASS
PLUGIN_WEBSITE_NATIVE_CONFIG=PASS
PLUGIN_WEBSITE_LOCAL_BINDING_ROUNDTRIP=PASS
PLUGIN_WEBSITE_PRODUCTION_TECHNICAL_E2E=PASS
PLUGIN_WEBSITE_REAL_ACCOUNT_E2E=UNVERIFIED
```

机器可读证据：`docs/evidence/2026-07-24-plugin-website-adaptation.log`。
