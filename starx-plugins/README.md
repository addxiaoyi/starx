# StarX 多平台插件

`starx-plugins` 的唯一生产部署物是 `starx-universal.jar`。同一个文件分别放入 Velocity、Paper 或 Folia 的 `plugins/` 目录：Velocity 根据 `velocity-plugin.json` 启动代理模式，Paper/Folia 根据 `plugin.yml` 启动后端模式。两端通过 `starx:bridge` 协作；Uworld 只会在 Velocity 入口中初始化。

完整职责、安装顺序、命令和功能矩阵见[多平台部署文档](../docs/STARX_PLATFORMS.md)。

## 产品边界

### 产品目标

StarX Velocity 负责代理侧模块装配。Uworld 的目标是为 StarX 内部隔离流程提供受管虚拟世界、玩家会话、超时、fail-closed 失败清理和精确目标转服。世界创建、输入回调、终态收敛与进程级协议资源都由一个 runtime 统一持有，业务模块不直接管理底层 Limbo 对象。

### 产品非目标

Uworld 不是第二个插件，不依赖外置 LimboAPI，不支持热重载，也不是任意后端 fallback 或通用多世界服务器。Uworld 自动门禁通过不代表某个消费者已经接入，更不代表该业务模块已经完成真实客户端验收。

支持边界如下：

- 唯一生产部署物为 `starx-universal.jar`；每个独立 JVM 各放置一份相同文件。
- Uworld core 和同步的底层 Limbo 实现都内嵌在该 JAR 中。
- 不安装外置 LimboAPI，不部署 `starx-limbo-api` 或 `starx-standalone-limbo` JAR。
- 不支持插件热重载，升级和回滚都必须完整重启 Velocity。
- 不允许任意后端 fallback；流程只能连接启动时解析并保存的精确目标。
- Loader 文件解析失败时关闭对应流程，不回退到未声明的 VOID 世界。
- 核心功能不要求 LuckPerms、Floodgate、TAB 或 PlaceholderAPI；已安装时按平台自动解锁增强，StarX 不运行时下载外部 JAR。

### 默认玩家体验

- 离线玩家先进入 Velocity 内置 Uworld，在聊天栏完成注册、密码或 TOTP 流程。
- 标题、操作栏和提示/成功/失败音效默认开启，玩家提示默认中文。
- 内置玩家列表使用 MiniMessage，并共享 `starx_player`、`starx_auth_status`、`starx_server`、`starx_online` 等实时变量。
- TAB、LuckPerms、Floodgate、PlaceholderAPI 都是软依赖增强；未安装时分别回退到内置玩家列表、内置绑定状态、Uworld 登录和 bridge 状态命令。

### 消费者状态

| 消费者 | 接入状态 | 真实客户端状态 |
|---|---|---|
| Auth | 已接入：离线注册、密码、TOTP、恢复码与正版自动认证使用受管 auth 世界 | UNVERIFIED |
| Diagnostics | 已接入：默认关闭，通过 `/sxworld test` 惰性创建独立诊断世界 | UNVERIFIED |
| Queue | 已接入：满服原因识别、按服排队、断线清理与容量释放后自动重连；不依赖 Uworld | IMPLEMENTED |
| Maintenance | 已接入：权限命令、白名单登录、代理拦截与子服配置同步；不依赖 Uworld | IMPLEMENTED |
| Tutorial | 已接入：`/sxguide` 可恢复的分步引导、状态、重置与跳过；不依赖 Uworld | IMPLEMENTED |

`UNVERIFIED` 表示尚无与当前候选 SHA-256 绑定的真实玩家证据，不表示失败，也不能改写为 `PASS` 代替验收。

## 工程模块

| 模块 | 用途 | 是否部署 |
|---|---|---|
| `starx-common` | 配置、数据库和共享业务逻辑 | 否，内嵌 |
| `starx-limbo-api` | Uworld 公共契约和同步的底层协议 API | 否，内嵌 |
| `starx-standalone-limbo` | 内置 Uworld factory 与底层实现 | 否，内嵌 |
| `starx-api` | 共享 API 与 `starx:bridge` v1 二进制协议 | 否，分别内嵌 |
| `starx-velocity` | Velocity 入口、Uworld 生命周期和网络编排所有者 | 否，生成内部组装输入 `starx-velocity.jar` |
| `starx-server` | Paper/Folia 后端入口、能力与状态适配器 | 否，生成内部组装输入 `starx-server.jar` |
| `starx-universal` | 合并两个平台入口、双描述符和共享实现 | 是，生成唯一部署物 `starx-universal.jar` |

## 支持基线

- Java 21
- 编译 API：Velocity `3.5.0-SNAPSHOT`
- 运行与冷启动基线：Velocity `3.5.0-SNAPSHOT` build 606
- 子服编译 API：Paper 1.21.11；同一后端 JAR 声明 Folia 支持
- Gradle Wrapper 8.10
- Paper 后端启用 Velocity modern forwarding
- 默认数据库为 SQLite `plugins/starx/data.db`，连接池上限为 2

其他 Velocity 构建、Java 版本、数据库驱动或客户端版本只有在重新完成相应门禁后才能声明支持。当前 JAR 只捆绑 SQLite 驱动，不要把 MySQL、PostgreSQL 或 H2 配置写成受支持的默认部署。

## 文档入口

- [Velocity/Paper/Folia 多平台部署](../docs/STARX_PLATFORMS.md)
- [生产环境、部署与回滚](../docs/UWORLD_ENVIRONMENT.md)
- [StarX Velocity 构建说明](starx-velocity/README.md)
- [Uworld 配置](../docs/UWORLD_CONFIGURATION.md)
- [Uworld 公共 API](../docs/UWORLD_DEVELOPMENT.md)
- [Uworld 验收](../docs/UWORLD_ACCEPTANCE.md)
- [内嵌底层实现边界](starx-standalone-limbo/README.md)

文档、自动测试或冷启动通过都不能证明插件已经部署，也不能替代真实玩家验收。以 [Uworld 验收](../docs/UWORLD_ACCEPTANCE.md) 中与当前候选 SHA-256 绑定的证据为准。
