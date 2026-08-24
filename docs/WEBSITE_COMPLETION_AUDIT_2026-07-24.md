# StarMC 网站完成审计 — 2026-07-24

## 结论

网站的工程与生产发布闭环已经完成：当前源码通过前后端门禁，可重复生成无敏感运行数据的发布包，生产部署具备清单校验、备份、自动回滚和依赖健康门禁；当前候选已部署到 `https://star-web.top`，公网深链、SuperTokens、StarX 实时状态和匿名权限边界均已验证。

不得把该结论扩展为所有外部业务流程都已完成。真实邮箱验证码投递和消费、真实 GitHub OAuth 回调、真实登录用户的 Minecraft 绑定与跨设备批准仍需要受控账号参与，状态保持 `UNVERIFIED`。

## 当前发布身份

```text
manifest_entries=244
manifest_sha256=bcc7d22ecc14373d598874bd61fed850f83288bc5531816902f7140b40818d6d
frontend_asset=/assets/index-BgtfXBbs.js
backend_server_sha256=041be144223654ec9758ab9cb547cca6e23bfa74b953d26da65460c55e4ce423
backend_starx_health_sha256=6cdd3822add7d357dcd6ebf298210244bdf6eef4a6f6e1f602d650a4b9da2d28
```

远端两份后端源码 SHA-256 与本地候选完全一致。生产回滚副本：

```text
/www/wwwroot/star-web.top.rollback-20260724-012656
/www/wwwroot/starmc-api.rollback-20260724-012656
```

## 自动门禁

| 范围 | 结果 |
|---|---:|
| 前端单元/契约测试 | 95/95 PASS |
| TypeScript | PASS |
| Vite 生产构建 | PASS |
| 后端测试 | 187/187 PASS，45 suites |
| 后端 ESLint | PASS |
| 前端生产依赖审计 | 0 high/critical |
| 后端生产依赖审计 | 0 high/critical |
| 发布清单 | 244 项，全部存在且 SHA-256 一致 |
| 发布包卫生 | 无 `.env`、数据库、日志、测试、`node_modules` 或 mock server |

## 本轮修复

- 修复根 `.gitignore` 非法 glob。
- 修复 Unicode 长路径下正式 release 构建，Windows 使用 ASCII `subst` 路径。
- `manifest.json` 改为 UTF-8 无 BOM，远端校验兼容历史 BOM。
- 修复 Windows 系统 OpenSSH 损坏导致的部署与反向隧道中断，改用可工作的 Git OpenSSH。
- 恢复 `StarXWebsiteTunnel` 计划任务，生产玩家状态从 fallback 恢复为原生 StarX 数据。
- 强化部署门禁：浏览器 UA 公网探针、SuperTokens Core、StarX bridge、实时节点、OpenAPI、主页和 favicon。
- 修复 `/api/health` 与 `/api/server/player-stats` 的 StarX 首次采集自锁，统一并发刷新状态。
- 两次不满足门禁的候选均由部署脚本自动回滚，未留下半部署状态。

## 生产验收

| 检查 | 结果 |
|---|---|
| `/`、`/status`、`/login`、`/account` | HTTP 200，SPA 深链正常 |
| `/favicon.ico` | HTTP 200，`image/x-icon` |
| `/api/health` | HTTP 200，`ok=true`，`networkStatusHealthy=true` |
| `/api/auth/status` | HTTP 200，SuperTokens Core 可达 |
| `/api/public/bootstrap` | SuperTokens、SMTP、GitHub OAuth、StarX adapter 已配置 |
| `/api/server/player-stats` | `source=starx`，1 个实时节点 |
| `/api/docs/openapi/authx` | HTTP 200 |
| 匿名 `/api/user/me` | HTTP 401 |
| 匿名 `/api/admin/reports` | HTTP 403 |
| 连续 5 次健康采样 | 全部稳定通过 |
| 反向隧道计划任务 | running |

生产当前使用 `json` 持久化。该模式是后端 README 明确支持的配置，PostgreSQL 为可选项，因此不构成未实现缺口；若未来切换 PostgreSQL，必须单独执行数据迁移和回滚验收。

宝塔 WAF 对通用自动化 User-Agent 返回 403，对正常浏览器 User-Agent 返回 200。当前发布脚本已按浏览器探针验证；独立监控系统应使用允许的 User-Agent 或配置 WAF allowlist。

## 保持 UNVERIFIED

- 真实邮箱验证码投递、消费和重放拒绝。
- 真实 GitHub 账号 OAuth 完整回调与会话创建。
- 真实登录用户的 Minecraft 绑定、解绑和跨设备批准。
- 需要多角色生产账号执行的对象级授权写操作。

以上项目不能由配置状态、单元测试或匿名探针替代。

## 状态

```text
WEBSITE_TECHNICAL_CLOSURE=PASS
WEBSITE_PRODUCTION_DEPLOYMENT=PASS
WEBSITE_FULL_BUSINESS_E2E=UNVERIFIED
```

机器可读证据：`docs/evidence/2026-07-24-website-production-closure.log`。
