# StarX Server

`starx-server.jar` 是 StarX 的 Paper/Folia 子服适配器。同一个 JAR 可安装在 Paper 和 Folia；它负责报告本地能力与状态，不复制 Velocity 的认证、Uworld、路由或全局队列。

## 要求

- Java 21
- Paper/Folia 1.21.x；编译 API 基线为 Paper 1.21.11
- 前置 Velocity 已安装同版本 `starx-velocity.jar`
- Velocity modern forwarding 与子服 secret 配置正确

## 构建与安装

从仓库根目录执行：

```powershell
& .\scripts\invoke-gradle-ascii.ps1 `
  :starx-plugins:starx-server:test `
  :starx-plugins:starx-server:shadowJar `
  --no-daemon --console=plain
```

将 `starx-plugins/starx-server/build/libs/starx-server.jar` 放入每个 Paper/Folia 子服的 `plugins/`，完整重启后编辑：

```yaml
# plugins/StarXServer/config.yml
node-id: lobby
bridge:
  enabled: true
  heartbeat:
    enabled: true
    velocity-url: "http://127.0.0.1:8788"
    api-key: "与 Velocity plugins/starx/config.yml 根级 api-key 相同"
```

`node-id` 建议与 Velocity `[servers]` 中的键一致。允许字符为字母、数字、点、下划线和短横线，长度 1-64；无效值会让插件在启动阶段明确失败。

## 平台差异

| 核心 | 执行模型 | 附加能力 |
|---|---|---|
| Paper | `main-thread` | `scheduler.main`、`world.paper` |
| Folia | `regionized` | `scheduler.global`、`scheduler.region`、`world.regionized` |

共同能力包括 `bridge.http-exchange`、`bridge.v1`、`player.carrier`、`players.snapshot`、`server.commands` 和 `server.status`。插件消息本身依赖在线玩家；启用 heartbeat 后，状态与皮肤请求可在载体不可用时进入该注册服专属的有界 HTTP mailbox。后端会在 `starx:bridge` 通道注册完成后发送 hello，Velocity 再通过同一连接请求状态，避免连接初期的载体时序丢包。

启用 `bridge.heartbeat` 后，Paper/Folia 还会直接向 Velocity 的 `/v1/backend/heartbeat` 上报空服状态，因此没有在线玩家时也能显示人数、平台和能力。响应体可携带一条代理指令；插件把指令投递到 global scheduler 后处理，并在同一交换循环回传响应，HttpClient 回调线程不直接调用 Bukkit 或皮肤插件 API。HTTP 不可达不会阻断子服启动，插件消息载体仍可继续使用；日志会逐层解包 `CompletionException` / `ExecutionException`，根异常没有文本时打印异常类型（例如 `ConnectException`），不会再输出含义不明的 `null`。恢复后的首个成功心跳只记录一次 `heartbeat recovered`。

若管理员安装 SkinsRestorer，StarX 会通过反射软集成读取玩家绑定的签名 texture；未安装、API 无数据或玩家没有皮肤时明确返回 `found=false`。StarX 不打包、不下载 SkinsRestorer API。

## 命令

- `/starxserver status`：显示节点、平台、执行模型、人数与最后代理联系时间。
- `/starxserver capabilities`：显示本地能力。
- `/starxbackend`：子服侧别名。
- 权限：`starx.command.server`，默认仅 OP。

完整架构、安装顺序和 Velocity 命令见[多平台部署文档](../../docs/STARX_PLATFORMS.md)。
