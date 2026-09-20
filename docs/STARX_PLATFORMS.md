# StarX 多平台部署与功能边界

StarX 的首选部署产物是一个三端通用文件：

```text
starx-plugins/starx-universal/build/libs/starx-universal.jar
```

同一文件分别放入 Velocity、Paper 和 Folia 的 `plugins/` 目录。它同时包含 `velocity-plugin.json` 与 `plugin.yml`，由平台加载器选择对应入口，��通过运行时反射猜测平台。

| 产物 | 用途 | 是否推荐生产部署 |
|---|---|---|
| `starx-universal.jar` | Velocity、Paper、Folia 三端通用部署 | 是 |
| `starx-velocity.jar` | Velocity 分端调试与边界测试 | 否，除非明确不用通用包 |
| `starx-server.jar` | Paper/Folia 分端调试与边界测试 | 否，除非明确不用通用包 |
| `starx-api-1.0.0.jar` | 第三方扩展编译依赖，不是可安装插件 | 否 |

Universal JAR 保持 Java 21 字节码。Velocity 编译和验收基线为 `3.5.0-SNAPSHOT` build 606；后端保留 Paper 1.21.11 生产编译基线，并使用 Java 25 对 Paper 26.1.2 build 71 与 26.2 build 84 执行双 API 编译门禁。Paper/Folia 26.x 实例使用 Java 25，且继续声明 `folia-supported: true`。

## 平台职责

| 能力 | Velocity | Paper | Folia |
|---|---|---|---|
| 玩家入口、跨服身份和注册服名 | 权威所有者 | 不持有 | 不持有 |
| Uworld 虚拟认证世界 | 内置运行 | 不加载 | 不加载 |
| 注册、密码、TOTP、恢复码、正版/Floodgate 认证 | 执行 | 接收认证后的玩家 | 接收认证后的玩家 |
| 跨服路由、Hub、重连、队列、维护状态 | 执行 | 提供目标节点 | 提供目标节点 |
| 网络安全、MOTD、全局聊天、在线同步 | 执行 | 配合桥接 | 配合桥接 |
| 本地人数、版本、TPS、MSPT、内存、运行时间 | 汇总 | 上报 | 上报 |
| 调度模型 | 不假定子服线程模型 | `main-thread` | `regionized` |
| 平台能力 | `starx.platform.velocity` | `scheduler.main`、`world.paper` | `scheduler.global`、`scheduler.region`、`world.regionized` |

Paper 与 Folia 共用后端入口。运行时检测 Folia，并将涉及服务端 API 的工作投递到对应 global、region 或 entity scheduler；HTTP 回调线程只负责网络 I/O。

## Velocity 与后端桥接

双方使用版本化二进制通道：

```text
starx:bridge
```

当前协议版本为 v1，支持：

- `proxy.hello` / `backend.hello`；
- 状态请求与响应；
- 皮肤查询与响应；
- 维护状态同步；
- 有界字段、包长、属性数量和版本校验。

插件消息有在线玩家时可直接传输；启用认证 HTTP heartbeat 后，空服也可通过 `/v1/backend/heartbeat` 的隔离 mailbox 交换状态和控制指令。节点状态含义：

- `UNSEEN`：尚未获得报告，不等同于离线；
- `LINKED`：最近报告新鲜；
- `STALE`：最后报告超过允许窗口。

## 安装

1. 构建：

   ```powershell
   npm run plugin:universal
   ```

2. 将完全相同的 `starx-universal.jar` 分别复制到：

   ```text
   Velocity/plugins/starx-universal.jar
   Paper/plugins/starx-universal.jar
   Folia/plugins/starx-universal.jar
   ```

3. 不要在同一实例同时安装通用 JAR 与对应分端 JAR，否则会形成重复插件 ID。
4. 在 Velocity `velocity.toml` 的 `[servers]` 中注册每个后端。
5. 在每个后端 `plugins/StarXServer/config.yml` 中设置唯一 `node-id`，建议与 Velocity 注册名一致。
6. 配置一致的 Velocity modern forwarding secret 与 Paper/Folia 代理转发设置。
7. 完整重启所有 JVM；不支持 `/reload` 或插件管理器热替换。

示例后端配置：

```yaml
node-id: lobby
server-type: lobby
bridge:
  enabled: true
  heartbeat:
    enabled: true
    velocity-url: "http://127.0.0.1:8788"
    api-key: "与 Velocity StarX api-key 相同"
```

## 可选生态插件

| 插件 | 安装位置 | 自动解锁能力 |
|---|---|---|
| LuckPerms | Velocity | 外部账号绑定上下文 |
| Floodgate | Velocity | 可信基岩身份认证 |
| TAB | Velocity | `%starx_*%` 全局变量 |
| PlaceholderAPI | Paper/Folia | 后端状态变量 |
| SkinsRestorer | Paper/Folia | 签名 texture 查询和皮肤桥接 |
| Geyser/RakNet provider | Velocity | 基岩入口能力 |

StarX 不运行时下载第三方 JAR，不使用 `URLClassLoader` 动态加载外部插件。缺失可选插件时保留核心功能。

## 运维命令

| 平台 | 命令 | 权限 |
|---|---|---|
| Velocity | `/sxnodes status [server]` | `starx.command.backend` |
| Paper/Folia | `/starxserver status` | `starx.command.server` |
| Paper/Folia | `/starxserver capabilities` | `starx.command.server` |
| Paper/Folia | `/starxserver skin <uuid> <name>` | `starx.command.server` |
| Paper/Folia | `/sx` | 玩家账号安全入口 |

后端 `/starxserver` 的别名为 `/starxbackend`。Velocity 的节点命令是 `/sxnodes`，不是旧文档中的 `/starxbackend`。

## 第三方扩展 Service API

公共 API 版本为 `1.0.0`，Maven 坐标：

```text
io.github.addxiaoyi.starx:starx-api:1.0.0
```

扩展必须以 `compileOnly` 依赖 API，不得将其 shade 或 relocate 到扩展 JAR。

### Velocity

Velocity 当前没有通用 ServiceManager。扩展应在描述符中硬依赖 `starx`，取得 StarX 插件实例并通过 `StarxServiceProvider#starxService()` 获取服务。

### Paper/Folia

后端通过 Bukkit `ServicesManager` 注册 `StarxService`。扩展应声明：

```yaml
depend: [StarXServer]
```

然后调用 `getServicesManager().getRegistration(StarxService.class)`。

API 提供：

- API 版本协商；
- 平台与能力查询；
- 扩展注册、状态和幂等注销；
- 事件订阅；
- 注册失败回滚；
- StarX 停止时逆序关闭扩展。

详细接入见 `docs/EXTENSION_API.md`，兼容承诺见 `docs/EXTENSION_COMPATIBILITY_POLICY.md`。

## 线程边界

公共事件回调运行在事件发布者线程，不保证是游戏线程：

- Velocity 扩展使用自身调度器访问代理 API；
- Paper 使用 Bukkit/global scheduler；
- Folia 对实体和区块操作使用 entity/region scheduler；
- 回调中不得阻塞网络、磁盘或数据库。

## 当前验证边界

自动门禁覆盖：

- API 1.0 公共签名基线；
- 扩展版本与能力协商；
- 生命周期回滚和订阅清理；
- Velocity Provider 契约；
- Paper/Folia Bukkit Service 契约；
- 通用 JAR 双描述符、单一 API 副本和平台 API 泄漏；
- Velocity、Paper、Folia 启动及平台识别。

分端产物和通用产物来自同一源码构建，但生产部署应以当前通用 JAR 的 SHA-256 和对应运行验收为准，旧候选证据不能自动继承。
