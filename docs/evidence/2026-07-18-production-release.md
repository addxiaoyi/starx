# 2026-07-18 网站与 StarX 测试服发布证据

## 构件

- `starx-velocity.jar`: `5E6A3D646D2B5638F435800F268B82AA9984169AF713C8601EB472B1538A024D`, 17,982,609 bytes。
- `starx-server.jar`: `A4855D46B0EE0CAF9779506F776306FB43DA36520644FE44C569F49D73117E44`, 43,165 bytes。
- 测试环境安装的两个 JAR 与上述构件哈希一致。

## 网站发布

- 生产根：`/www/wwwroot/star-web.top`、`/www/wwwroot/starmc-api`。
- 首次发布回滚点：`star-web.top.rollback-20260718-174422`、`starmc-api.rollback-20260718-174422`。
- 移动端标题修复发布回滚点：`star-web.top.rollback-20260718-193820`、`starmc-api.rollback-20260718-193820`。
- HTTP transport 计数发布回滚点：`star-web.top.rollback-20260718-221053`、`starmc-api.rollback-20260718-221053`。
- 发布均通过清单 SHA-256 校验、PM2 启动检查、后端 doctor 和 bootstrap 检查；远端 `.env.generated`、`data` 与运行数据未被发布包覆盖。
- 生产首页引用 `index-aQ8-9FeX.js`；`/api/health`、`/api/auth/status`、`/api/public/bootstrap`、`/api/skins/catalog`、`/api/skins/library/stream` 和 `/api/server/player-stats` 已返回预期响应。

## 测试服运行证据

- Velocity PID `83916`、Paper PID `73092` 使用当前构件冷启动；监听分别为 `25579/8788` 与 `127.0.0.1:25565`，两个 stderr 均为 0 bytes，启动日志无 ERROR/Exception。
- 0 在线玩家时调用 `POST /v1/admin/backend/probe`，返回 correlation ID `2adb8e3f-363b-4cd3-b08d-7ea509cbbd07`；`factions` 的 accepted/delivered 从 `0→1`、queued 回到 `0`、`lastSeen` 前进且节点保持 `linked`。
- 网站公开玩家统计返回 `source=starx`，子服 `factions` 为 `linked`、`heartbeat-http`、`0/20`，并公开 `bridge.http-exchange` 与四个非敏感 HTTP 指令计数。
- 测试服回滚批次：`velocity-test/backups/starx/20260718-215837`。

## 浏览器证据

- 桌面：首页、状态、皮肤页截图位于 `output/playwright/production-20260718/*-desktop.png`。
- 移动端：首页、状态、皮肤页截图位于 `output/playwright/production-20260718/*-mobile.png`。
- 390×844 首页复拍确认英文与中文主标题分行；最终页面控制台为 0 error / 0 warning。
- 皮肤页健康连接采样超过 35 秒后，目录与合集请求仍各只有初始一次，没有 15 秒重复轮询；SSE 端点独立返回 `200 text/event-stream` 与 `hello`。

## 边界

本页不是 Uworld 当前候选的真实客户端验收。真实 SkinsRestorer 客户端皮肤效果、玩家载体跨服同步、全部 Uworld 客户端矩阵以及当前 Server SHA 的 Folia 实机启动仍必须按各自验收文档提供与当前 SHA-256 绑定的证据。
