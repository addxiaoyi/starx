# 2026-07-19 网站与 StarX 发布证据

## 网站构件与生产状态

- 最新干净发布清单包含 229 个条目、51,402,345 bytes；`manifest.json` SHA-256 为 `3B76DAA4248D3F90A1A70D11BAF3CD147BA5E4D03CACA41125C59F2830407050`。
- 本地 `重构/starmc/release-current` 已原子切换到该清单；上一版保存在 `重构/starmc/release-current.backup-20260719-070018`，更早的 `release-current.backup-20260719-053003` 保持不变。
- 最新生产回滚点为 `/www/wwwroot/star-web.top.rollback-20260719-070018` 与 `/www/wwwroot/starmc-api.rollback-20260719-070018`；较早的 `20260719-050912` 回滚点保持不变。
- Nginx 皮肤深链规则备份为 `/www/server/panel/vhost/nginx/extension/star-web.top/skins.conf.backup-20260719-0517`；aaPanel Nginx 使用完整配置路径执行语法检查并通过。
- 生产入口为 `/assets/index-CzowOdYv.js` 与 `/assets/index-D4vS2cKq.css`；两份远端文件 SHA-256 与发布清单一致。
- PM2 `starmc-api` PID 为 `1417314`，状态为 `online`，restarts 与 unstable restarts 均为 0，错误日志为 0 bytes。

## 网站运行验收

- `https://star-web.top/api/health` 返回成功；默认 `curl` User-Agent 可直接读取 `/api/server/player-stats`，响应为 `source=starx`、`0/100` 和 `factions=linked/heartbeat-http`。只读 `GET/HEAD` 放行不改变命令行客户端对写请求的 403 防护。
- `/skins/k_24127527010c` 直接刷新返回 200；详情正确展示皮肤名称、作者与模型。
- Playwright 实浏览器状态页显示 StarX 数据源、Plan 样本数、子服版本、平台、运行时长、最后上报、HTTP 指令计数与能力列表，控制台为 0 error / 0 warning。
- 桌面与 390x844 移动端的首页、皮肤目录、皮肤详情、登录、地图、关于、Wiki、状态和 404 页面已检查，无横向溢出或加载卡死。
- 前端 57 项测试、后端 166 项测试、两侧 lint、TypeScript、部署清单、隧道与 Nginx 路由契约均通过；新增中间件回归分别覆盖公开只读 API 放行与写请求继续拒绝。

## StarX 构件与部署

- `starx-velocity.jar`: SHA-256 `754BA07377E864D2A16E44828F122598DB2E5C0783C9EAD13A281C843C3340D9`，17,986,720 bytes。
- `starx-server.jar`: SHA-256 `E610AB0ACC10232C12F998983637ADE6F15796BDD055ECFB80845DD56C96AF39`，45,444 bytes。
- 构建目录与测试服安装目录中的两个 JAR 分别哈希一致。Velocity PID 为 `95524`，Paper PID 为 `60588`，监听 `25579/8788` 与 `127.0.0.1:25565`。
- 本轮 Velocity 滚动发布备份位于 `velocity-test/backups/starx/20260719-055115`；发布后 Velocity stderr 与 Paper stderr 均为 0 bytes。
- 环境 doctor 的 23 项检查全部通过，原始结果见 `docs/evidence/2026-07-19-uworld-doctor.log`，其 SHA-256 为 `C693A92EC7C2E03EBDCF9AF9AEE538EAE86F40520CB7E6C003676A28E442F67F`。

## 通信与客户端冒烟

- 空服精确 probe 使 `factions` 的 accepted/delivered 从 `0→1`，rejected/queued 保持 `0`，节点保持 `linked` 且 transport 为 `heartbeat-http`。
- keepalive 竞态修复后的隔离 Mineflayer 诊断流程连续两次完成，证据目录为 `tmp/uworld-real-client-probe/runs/20260719-054536-437` 与 `tmp/uworld-real-client-probe/runs/20260719-054756-266`。
- live `25579` 临时离线账号完成首次注册、二次密码登录，并两次精确进入 `factions`；聊天栏包含玩家名、UUID、账号类型、当前/上次 IP、上次登录、累计游玩、认证目标和可点击绑定入口。测试账号已通过管理 API 删除，最终一轮未新增服务端 ERROR。
- Java 全模块 94 个 suite / 299 项测试、Node 探针 10 项测试全部通过；Default 与 Diagnostics 冷启动均为 PASS。

## 验收边界

Mineflayer 冒烟不是 Mojang 官方客户端多版本矩阵。完整 Uworld 25 项矩阵、真实 SkinsRestorer 客户端效果、玩家载体皮肤跨服同步和当前 Server SHA 的 Folia 实机冷启动仍保持 `UNVERIFIED`，不能由本页结果替代。
