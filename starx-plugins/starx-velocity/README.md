# StarX Velocity

`starx-velocity` 是通用 StarX 插件的 Velocity 内部入口模块，不再作为生产发行物单独部署。生产环境把 `starx-universal.jar` 放入 Velocity；Velocity 插件加载器读取其中的 `velocity-plugin.json` 并只启动 `StarxVelocityPlugin`。Uworld、Auth、Queue、Maintenance 和 Tutorial 仅在该代理入口中初始化。各消费者的权威状态见[插件产品边界](../README.md)。

## 运行要求

- Java 21。
- Velocity `3.5.0-SNAPSHOT`。当前项目的冷启动基线固定为 build 606；其他构建必须重新执行验收。
- 使用 Gradle Wrapper 8.10 构建。
- 代理配置中必须注册 `uworld.auth.target-server` 指向的后端。冷启动只检查注册名；生产环境 doctor 和真实客户端转服还要求该地址能从 Velocity 主机连接。
- 默认数据库为 SQLite `plugins/starx/data.db`，连接池上限为 2；当前发行物不把其他数据库驱动作为受支持部署。
- 不支持热重载。升级、回滚或重新启用 Uworld 时必须完整重启 Velocity。

配置入口、分片归属和 Uworld 投影文件的放置规则见[StarX 配置与运行时资源目录](../../docs/STARX_CONFIGURATION_LAYOUT.md)。

不要把外置 LimboAPI JAR 放进 `plugins/`。内置 Uworld 与外置 LimboAPI 同时加载会争用进程级协议和事件状态，属于不支持的部署方式。

## 自动探测与配置生成

默认启用 `auto-config`。Velocity 每次完整启动会读取已注册子服和已加载插件，并在受管字段中自动：

- 为空的根级 `api-key` 生成 384 位随机值；
- 按实际安装状态启用或关闭 LuckPerms、Floodgate、TAB、Plan 与 RakNet/Geyser 适配；
- 仅在检测到 SkinsRestorer 时启用网站纹理采集；
- 首次启动时从 `lobby`、`hub`、`main`、`survival` 或其他已注册子服中选择认证目标；
- 将空值或 `auto` 的网站节点 ID 解析为稳定代理节点名；
- 写出不含任何密钥的 `plugins/starx/auto-detection.json`。

管理员的业务参数不会被任意猜测。把对应 `generate-*`、`manage-*` 或 `select-*` 开关设为 `false` 后，该部分完全由人工配置维护。

每个 Paper/Folia 子服安装同一份 `starx-universal.jar` 后，Velocity 默认启用 `starx.backend-bridge`。使用 `/starxbackend status [server]` 或 `/starxnodes` 查看节点报告；启用认证 heartbeat 后，状态与皮肤控制指令可在无玩家时经每服独立 HTTP mailbox 交换。`UNSEEN` 只表示尚无新鲜 bridge/heartbeat 报告，不能单独判定子服离线。完整职责和子服安装见[多平台部署文档](../../docs/STARX_PLATFORMS.md)。

最小 `velocity.toml` 后端配置如下：

```toml
[servers]
lobby = "127.0.0.1:25566"
```

`plugins/starx/config.yml` 中的 `uworld.auth.target-server` 必须为 `lobby`。受控冷启动只要求名称已注册；环境 doctor 使用 `-RequireBackend` 和真实客户端验收时，`127.0.0.1:25566` 必须在线。

## 构建

从仓库根目录执行。Windows 源码路径包含非 ASCII 字符时使用仓库脚本：

```powershell
& .\scripts\invoke-gradle-ascii.ps1 `
  :starx-plugins:starx-limbo-api:test `
  :starx-plugins:starx-common:test `
  :starx-plugins:starx-standalone-limbo:test `
  :starx-plugins:starx-velocity:test `
  :starx-plugins:starx-universal:check `
  --no-parallel --no-daemon --console=plain
```

Linux 或纯 ASCII 路径可以直接执行：

```bash
./gradlew \
  :starx-plugins:starx-limbo-api:test \
  :starx-plugins:starx-common:test \
  :starx-plugins:starx-standalone-limbo:test \
  :starx-plugins:starx-velocity:test \
  :starx-plugins:starx-universal:check \
  --no-parallel --no-daemon --console=plain
```

唯一生产部署文件为：

```text
starx-plugins/starx-universal/build/libs/starx-universal.jar
```

`starx-velocity.jar`、`starx-server.jar`、`starx-standalone-limbo` 和 `starx-limbo-api` 都是内部构建或分端测试产物，不应单独复制到生产 `plugins/`。

## 生产部署命令

Windows 与 Linux 的完整部署、SQLite 一致性备份、候选/安装 SHA-256 核对、服务账户权限、外置 LimboAPI 隔离和回滚命令统一维护在 [Uworld 生产环境](../../docs/UWORLD_ENVIRONMENT.md) 中。不要从旧文档片段部署。

## 部署与首次启动

1. 停止 Velocity 进程。
2. 将 `plugins/` 中任何外置 LimboAPI JAR 和旧的 StarX 重复 JAR 移动到时间戳备份，不要直接删除。
3. 将 `starx-universal.jar` 放入 Velocity 的 `plugins/`。
4. 使用 Java 21 启动 Velocity。
5. 检查 `plugins/starx/config.yml` 及 `plugins/starx/config/`。新安装会写出入口索引和按职责拆分的五个配置文件。
6. 检查 `plugins/starx/config/uworld.yml` 中的 `uworld.auth.world.file-name`，并将投影放到 `plugins/starx/assets/uworld/`。该路径相对于 `plugins/starx/`。
7. 检查 `plugins/starx/uworld/core.yml`。如果只存在旧的 `plugins/starx/limbo/core.yml`，运行时会继续读取旧路径并记录迁移警告，不会自动移动或覆盖文件。
7. 核对候选和安装 JAR 的 SHA-256，运行环境 doctor；在允许玩家进入前完成冷启动和 25 项真实客户端验收。

启动必须 fail closed：Uworld core、世界生成、loader 文件或认证目标无效时，不得静默把待认证玩家送入任意后端。

默认与 Diagnostics profile 的冷启动都必须出现以下四条精确日志：

```text
Uworld core initialized
Uworld runtime ready
Generated a 11x11 Uworld authentication platform
Authentication Uworld ready
```

Diagnostics 世界由 `/uworld test` 惰性创建，因此仅启动代理时不要求诊断世界就绪日志。

## Diagnostics 快速开始

这条路径用于从干净安装证明插件能内置创建虚拟世界、接入真实玩家、接收聊天与移动，并回到精确后端。它不替代完整的 25 项真实客户端矩阵。

1. 按[生产环境](../../docs/UWORLD_ENVIRONMENT.md)配置 Java 21、Velocity build 606、私有 Paper、modern forwarding、匹配 secret 和可写 SQLite；在 `[servers]` 中注册在线的 `lobby`。
2. 保持 `modules.starx.auth.enabled: true`、`modules.starx.uworld.enabled: true`、`uworld.enabled: true`，并设置 `uworld.diagnostics.enabled: true`。Auth 为 true 时两个 Uworld 开关缺一不可。
3. 完整启动 Velocity，等待 `Uworld core initialized`、`Uworld runtime ready`、`Authentication Uworld ready`，确认没有模块回滚或 loader 错误。
4. 通过权限系统授予测试玩家 `starx.uworld.diagnostics`。LuckPerms Velocity 的示例为 `lpv user <player> permission set starx.uworld.diagnostics true`；使用其他权限系统时授予同一节点。
5. 在控制台执行 `/sxworld status`，记录 world/session 计数。玩家完成 Auth 并进入 `lobby` 后执行 `/sxworld test`。
6. 玩家应看到浅蓝混凝土 11x11 平台和 `Diagnostics Uworld ready`。发送普通聊天并移动至少一格，分别确认 chat 与 move callback 消息。
7. 在虚拟世界内输入 `/sxworld leave`。该文本由 diagnostics 聊天回调识别，session 只能返回进入前保存的同一个 `RegisteredServer`；没有前序服时使用 diagnostics 初始化时保存的 hub 对象。
8. 再执行 `/sxworld status`，确认 session 数恢复；保存候选 SHA-256、客户端版本、玩家账号类型、代理日志、时间戳与实际 outcome。缺少任一证据时状态保持 `UNVERIFIED`。

预期失败必须可见：无权限或 diagnostics 关闭时命令拒绝；目标未注册时在请求转服前失败；目标已注册但离线时连接 future 失败；timeout、wrong target 和 shutdown 都必须断开或转服到定义的唯一终态并清理 session。

## 升级

升级前备份以下内容：

- 当前 `starx-universal.jar`。
- `plugins/starx/config.yml` 和整个 `plugins/starx/config/` 配置分片目录。
- `plugins/starx/assets/uworld/` 下由 Uworld 引用的 `.schem`、`.schematic`、`.nbt` 或 `.litematic` 文件。
- `plugins/starx/uworld/core.yml`；仍使用兼容路径时同时备份 `plugins/starx/limbo/core.yml`。
- SQLite `data.db`、`data.db-wal` 和 `data.db-shm`；必须停止 Velocity 后作为同一批次备份。

停止代理后替换 JAR，确认服务账户拥有 `plugins/starx/` 写权限，再执行完整冷启动和环境 doctor。不要使用 Velocity 插件重载器。配置同时包含 `uworld` 和旧 `limbo` 根时，新根优先并产生一次警告；确认迁移结果后再删除旧键。

`starx.limbo`、`limbo:` 和旧 core 路径的迁移兼容只覆盖当前完整 Uworld 主版本，最早会在下一个 Uworld 主版本移除。

## 回滚

1. 停止 Velocity。
2. 恢复上一版 JAR、配置、core、loader 文件和同一批次 SQLite 文件组。
3. 保留失败版本的日志和配置快照用于诊断。
4. 完整重启代理，核对安装 SHA-256，并重新执行上一版的冷启动和环境检查。

不要在运行中的代理内替换 JAR，也不要把 Uworld core 从新旧路径之间来回复制后继续热运行。

## 文档

- [插件产品边界](../README.md)
- [Velocity/Paper/Folia 多平台部署](../../docs/STARX_PLATFORMS.md)
- [生产环境、部署与回滚](../../docs/UWORLD_ENVIRONMENT.md)
- [Uworld 配置](../../docs/UWORLD_CONFIGURATION.md)
- [配置与运行时资源目录](../../docs/STARX_CONFIGURATION_LAYOUT.md)
- [Uworld 开发接口](../../docs/UWORLD_DEVELOPMENT.md)
- [Uworld 验收](../../docs/UWORLD_ACCEPTANCE.md)
- [内嵌 Limbo 运行时](../starx-standalone-limbo/README.md)
- [第三方声明](../../NOTICE)
- [AGPL-3.0](../../LICENSES/AGPL-3.0.txt) 和 [MIT](../../LICENSES/MIT.txt)
