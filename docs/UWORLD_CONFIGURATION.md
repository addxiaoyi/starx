# Uworld 配置

Uworld 是 `starx-velocity.jar` 内部模块。配置文件位于 `plugins/starx/config.yml`，core 配置默认位于 `plugins/starx/uworld/core.yml`。不要安装外置 LimboAPI。

## 默认配置

```yaml
modules:
  starx.auth:
    enabled: true
  starx.hub:
    enabled: true
  starx.uworld:
    enabled: true

uworld:
  enabled: true
  transfer-timeout-seconds: 15
  auth:
    timeout-seconds: 300
    target-server: "lobby"
    world:
      dimension: "OVERWORLD"
      spawn-x: 0.5
      spawn-y: 100.0
      spawn-z: 0.5
      spawn-yaw: 0.0
      spawn-pitch: 0.0
      game-mode: "SURVIVAL"
      loader-type: "VOID"
      file-name: "auth_world.schem"
      offset-x: 0
      offset-y: 0
      offset-z: 0
      view-distance: 4
      simulation-distance: 4
      platform-radius: 5
  diagnostics:
    enabled: false
    timeout-seconds: 120
    platform-radius: 5
```

`platform-radius: 5` 生成边长 `2 * 5 + 1`，即 11x11 的平台。

## 数据库基线

Uworld 的 Auth 流程使用 StarX Velocity 的共享数据库。省略 `database:` 根时仍使用以下受支持默认值：

```yaml
database:
  type: "sqlite"
  database: "plugins/starx/data.db"
  pool-max-size: 2
  connection-timeout-ms: 30000
```

当前发行物只捆绑 SQLite JDBC 驱动。数据库文件路径相对于 Velocity 工作目录；运行服务账户必须能写入其父目录。`data.db`、`data.db-wal` 和 `data.db-shm` 必须在 Velocity 停止后作为同一批次备份。生产权限、备份和恢复命令见 [Uworld 生产环境](UWORLD_ENVIRONMENT.md)。

## Auth 与 Uworld 启用组合

Auth 是 Uworld 的当前生产消费者。`modules.starx.auth.enabled: true` 要求 `modules.starx.uworld.enabled` 与 `uworld.enabled` 同时为 `true`；否则 Auth 初始化时发现 runtime 未 ready，整个插件按 fail closed 回滚，而不是绕过认证进入后端。

| `modules.starx.auth.enabled` | `modules.starx.uworld.enabled` | `uworld.enabled` | 结果 |
|---|---|---|---|
| true | true | true | 支持：Auth 使用内置受管虚拟世界；Diagnostics 可按自己的开关启用 |
| false | true | true | 支持：Uworld runtime 可供 Diagnostics 或内部 API 独立运行；StarX 共享数据库仍须初始化成功 |
| true | false | 任意 | FAIL_CLOSED：Auth 依赖的 Uworld 模块不存在，插件启动回滚 |
| true | true | false | FAIL_CLOSED：Uworld runtime 不 ready，插件启动回滚 |
| false | false | false | 支持：Auth 与 Uworld 均关闭，不提供虚拟世界流程 |

Diagnostics 模块由 `modules.starx.uworld.enabled` 派生；关闭 Uworld 模块会同时取消 `/sxworld` 命令注册。要禁用 Uworld，必须先同步禁用 `modules.starx.auth.enabled`，完整停止并重新启动 Velocity。

## 字段

| 路径 | 默认值 | 约束 | 说明 |
|---|---:|---|---|
| `modules.starx.auth.enabled` | `true` | 布尔值 | 启用 Auth；为 `true` 时强依赖 Uworld 模块和 runtime 同时启用。 |
| `modules.starx.uworld.enabled` | `true` | 布尔值 | 注册并启用 Uworld 模块。 |
| `modules.starx.hub.enabled` | `true` | 布尔值 | 注册 `/sxhub`，目标复用 `uworld.auth.target-server`。 |
| `uworld.enabled` | `true` | 布尔值 | 允许创建受管虚拟世界。 |
| `uworld.transfer-timeout-seconds` | `15` | 大于 0 | 玩家离开 Uworld 后等待精确目标服连接的最长时间。 |
| `uworld.auth.timeout-seconds` | `300` | 大于 0 | 认证流程处于虚拟世界中的最长时间；经验等级显示剩余秒数，经验条显示剩余比例，常规阶段每 5 秒提示音、最后 10 秒每秒加急。 |
| `uworld.auth.target-server` | `lobby` | 注册的 Velocity 后端名 | 值会 trim；null 或空白归一化为 `lobby`。启动时解析一次并保存同一个 `RegisteredServer` 对象。 |
| `uworld.auth.world.dimension` | `OVERWORLD` | `OVERWORLD`、`NETHER`、`THE_END` | 虚拟世界维度。 |
| `uworld.auth.world.spawn-x/y/z` | `0.5/100.0/0.5` | 有限数值 | 玩家出生坐标。 |
| `uworld.auth.world.spawn-yaw` | `0.0` | 有限数值 | 出生水平朝向。 |
| `uworld.auth.world.spawn-pitch` | `0.0` | 有限数值，运行时范围 `-90..90` | 出生垂直朝向。 |
| `uworld.auth.world.game-mode` | `SURVIVAL` | `SURVIVAL`、`CREATIVE`、`ADVENTURE`、`SPECTATOR` | Uworld 内游戏模式。 |
| `uworld.auth.world.loader-type` | `VOID` | 见下节 | 世界来源。名称忽略大小写并规范化为大写。 |
| `uworld.auth.world.file-name` | `auth_world.schem` | 非空相对路径 | 相对于 `plugins/starx/` 的世界文件。`VOID` 不读取该文件。 |
| `uworld.auth.world.offset-x/y/z` | `0` | 整数 | 文件 loader 写入虚拟世界时的偏移。 |
| `uworld.auth.world.view-distance` | `4` | `1..32` | 发送给客户端的视距。 |
| `uworld.auth.world.simulation-distance` | `4` | `1..32` | 虚拟世界模拟距离。 |
| `uworld.auth.world.platform-radius` | `5` | `1..64` | `VOID` 默认平台半径。 |
| `uworld.diagnostics.enabled` | `false` | 布尔值 | 启用 `/sxworld test` 和 `/sxworld leave`。生产默认关闭。 |
| `uworld.diagnostics.timeout-seconds` | `120` | 大于 0 | 独立诊断流程超时。 |
| `uworld.diagnostics.platform-radius` | `5` | `1..64` | 诊断世界平台半径。 |

配置枚举、布尔类型、整数类型、范围、loader 文件或目标服务器无效时必须 fail closed。布尔键不得写成字符串，整数字段不得写小数；错误必须携带完整配置 key。不要依赖日志警告后继续接纳待认证玩家。

## Loader

| 值 | 文件 | 行为 |
|---|---|---|
| `VOID` | 不读取 | 生成内置平台并填充光照。 |
| `SCHEMATIC` | `.schematic` | 读取 MCEdit schematic。 |
| `WORLDEDIT_SCHEM` | `.schem` | 读取 WorldEdit schem。 |
| `STRUCTURE` | `.nbt` | 读取 Minecraft structure NBT。 |

文件必须在世界发布前完成解析。解析失败时不发布半成品世界，也不切换到未声明的 fallback 世界。

## 旧配置迁移

新配置优先。文件同时包含 `uworld:` 和旧 `limbo:` 根时，只读取 `uworld:` 并记录一次：

```text
Both uworld and legacy limbo configuration are present; uworld takes precedence
```

只有旧根时，将旧值映射到认证世界，并记录弃用警告。旧值不会成为所有 Uworld 的全局默认值。

`starx.limbo` 模块 id、`limbo:` 配置根、旧 core 路径和 `StarxLimboFactory` 别名只承诺在当前完整 Uworld 主版本内兼容；维护版本升级不会提前删除它们，最早在下一个 Uworld 主版本移除。运维方应在当前主版本完成迁移，不能把该窗口理解为永久兼容。

| 旧键 | 新键 |
|---|---|
| `modules.starx.limbo.enabled` | `modules.starx.uworld.enabled` |
| `limbo.enabled` | `uworld.enabled` |
| `limbo.auth-timeout-seconds` | `uworld.auth.timeout-seconds` |
| `limbo.hub-server` | `uworld.auth.target-server` |
| `limbo.dimension` | `uworld.auth.world.dimension` |
| `limbo.spawn-x/y/z` | `uworld.auth.world.spawn-x/y/z` |
| `limbo.spawn-yaw/pitch` | `uworld.auth.world.spawn-yaw/pitch` |
| `limbo.game-mode` | `uworld.auth.world.game-mode` |
| `limbo.world-loader-type` | `uworld.auth.world.loader-type` |
| `limbo.world-file-name` | `uworld.auth.world.file-name` |
| `limbo.world-offset-x/y/z` | `uworld.auth.world.offset-x/y/z` |
| `limbo.view-distance` | `uworld.auth.world.view-distance` |
| `limbo.simulation-distance` | `uworld.auth.world.simulation-distance` |
| `limbo.platform-size` | `uworld.auth.world.platform-radius` |

新 `uworld.auth.world.platform-size` 也作为 `platform-radius` 的过渡别名读取；新键存在时新键优先。该过渡别名遵循同一个完整 Uworld 主版本兼容窗口。

迁移步骤：

1. 停止 Velocity。
2. 备份 `plugins/starx/config.yml`。
3. 写入新 `modules.starx.uworld` 和 `uworld` 根。
4. 完整启动并检查警告、目标服务器和世界来源。
5. 完成冷启动与客户端验收后再删除旧键。

## `core.yml` 路径

路径选择是只读判定，不自动搬迁文件：

| 状态 | 使用路径 | 警告 |
|---|---|---|
| 两个文件都不存在 | `plugins/starx/uworld/core.yml` | 无；factory 首次初始化时创建。 |
| 只有新文件存在 | `plugins/starx/uworld/core.yml` | 无。 |
| 只有旧文件存在 | `plugins/starx/limbo/core.yml` | 记录旧路径迁移警告。 |
| 新旧文件都存在 | `plugins/starx/uworld/core.yml` | 无；两个文件均不改写。 |

迁移 core 文件时必须停止代理，复制并核对内容后再启动。不要在运行中移动文件，也不要删除旧文件后执行热重载。

## 诊断命令

三条命令共用权限 `starx.uworld.diagnostics`。只有 `modules.starx.uworld.enabled: true` 时才注册命令；`uworld.diagnostics.enabled` 只控制 `test` 和 `leave`，不隐藏 `status`。

| 命令 | 调用者 | 额外开关 | 作用 | 失败行为 |
|---|---|---|---|---|
| `/sxworld status` | 控制台或玩家 | 无 | 查看 runtime、世界和 session 计数 | 无权限时拒绝；模块未启用时命令不存在 |
| `/sxworld test` | 仅玩家 | `uworld.diagnostics.enabled: true` | 惰性创建或复用独立 diagnostics 世界并进入 session | 控制台、无权限、runtime 未 ready 或 diagnostics 关闭时明确拒绝 |
| `/sxworld leave` | 仅处于 diagnostics session 的玩家 | `uworld.diagnostics.enabled: true` | 返回进入前保存的服务器；无前序服时使用 diagnostics 初始化时保存的认证 target/hub 对象 | 目标缺失、转服失败或 session 已终止时 fail closed 并清理 |

`/sxworld leave` 的两类目标故障必须区分：返回目标名称未在 Velocity 注册时，流程在请求转服前失败并清理当前 session；目标已经注册但后端离线时，连接请求失败并清理 session。两种情况都不得回退到其他首服。

诊断流程不替代真实客户端验收。测试步骤和证据格式见 [Uworld 验收](UWORLD_ACCEPTANCE.md)。

## 相关文档

- [StarX Velocity 部署](../starx-plugins/starx-velocity/README.md)
- [Uworld 生产环境](UWORLD_ENVIRONMENT.md)
- [Uworld 公共 API](UWORLD_DEVELOPMENT.md)
- [Uworld 验收](UWORLD_ACCEPTANCE.md)
