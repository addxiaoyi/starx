# StarX

StarX 是面向 Velocity、Paper 和 Folia 网络的 Java 21 插件。项目只发布一个可部署文件：`starx-universal-0.3.1.jar`。同一个 JAR 放入代理端和每个后端实例，由平台加载器选择对应入口。

当前插件版本：**0.3.1**

公共扩展 API：**1.0.0**

## 功能分类

| 分类 | 主要能力 | 运行位置 |
|---|---|---|
| 网络入口与路由 | 登录准入、Hub、重连、重定向、普通队列、智能队列、维护模式、MOTD、全局玩家列表 | Velocity |
| 账号与认证 | 注册与登录、TOTP、邮箱验证、密码重置、跨设备审批、账号删除、QQ 绑定、Floodgate/Yggdrasil 身份接入 | Velocity；Paper/Folia 提供 `/sx` 交互入口 |
| Uworld | 内置认证世界、会话状态、倒计时、诊断模式、认证完成后的安全转服 | Velocity |
| 跨服桥接 | `starx:bridge` 版本化消息、后端能力上报、维护状态同步、皮肤查询、HTTP heartbeat、空服 mailbox | Velocity + Paper/Folia |
| 节点与运维 | 节点注册、在线人数、TPS、MSPT、内存、执行模型、兼容性报告、自动配置报告 | 全平台 |
| 皮肤与网站同步 | SkinsRestorer 软集成、签名纹理查询、网站纹理清单、纹理上传下载、离线补发 | 全平台 |
| 安全与风控 | 登录限流、Bot 过滤、风险事件、HMAC 重放保护、Webhook 持久化 outbox、智能告警 | 以 Velocity 为主 |
| 生态集成 | LuckPerms、Floodgate、TAB、Plan、Geyser/RakNet、PlaceholderAPI、SkinsRestorer、NapCat/QQ | 按插件安装位置启用 |
| 扩展开发 | 版本协商、能力查询、生命周期、事件订阅、Velocity Provider、Bukkit ServicesManager | StarX Extension API 1.0.0 |

各模块都有明确的平台边界和降级行为。完整说明见 [`docs/STARX_PLATFORMS.md`](docs/STARX_PLATFORMS.md)。

## 下载与安装

从 [GitHub Releases](https://github.com/addxiaoyi/starx/releases) 下载：

```text
starx-universal-0.3.1.jar
```

将同一个文件分别放入：

```text
Velocity/plugins/
Paper/plugins/
Folia/plugins/
```

安装时注意：

1. 所有实例使用 Java 21。
2. 不要在同一实例同时安装 `starx-universal.jar` 和分端调试 JAR。
3. 先启动 Velocity，再启动 Paper/Folia，便于同机部署自动发现代理配置。
4. 配置 Velocity modern forwarding，并确保后端转发密钥一致。
5. 修改核心配置后完整重启 JVM；不支持 `/reload` 或插件管理器热替换。

非标准目录或跨容器部署可显式指定 Velocity 配置：

```text
-Dstarx.velocity.config=/path/to/velocity/plugins/starx/config.yml
STARX_VELOCITY_CONFIG=/path/to/velocity/plugins/starx/config.yml
```

## 兼容范围

| 项目 | 已认证范围 |
|---|---|
| Java | 21 |
| Velocity | `3.5.0-SNAPSHOT` build 606 |
| Minecraft 后端 | 1.21.0–1.21.11 |
| Paper | 1.21 系列，编译基线 1.21.11 |
| Folia | 1.21 系列，`folia-supported: true` |

第三方软依赖的已认证主版本：

| 集成 | 范围 |
|---|---|
| LuckPerms | 5.x |
| Floodgate | 2.x |
| TAB | 5.x–6.x |
| Plan | 5.x |
| Geyser | 2.x |
| SkinsRestorer | 15.x |
| PlaceholderAPI | `>=2.11,<3` |
| Raknetify | 仅检测是否存在，版本状态为 `UNKNOWN` |

未安装软依赖不会阻止核心功能启动。超出已认证范围时，插件会在兼容性报告和 `doctor` 中标记 `UNKNOWN`、`DEGRADED` 或 `UNSUPPORTED`。详见 [`docs/COMPATIBILITY.md`](docs/COMPATIBILITY.md)。

## 自动配置

Velocity 首次启动会：

- 生成随机根 `api-key`；
- 检测已安装的可选插件；
- 根据注册服务器选择认证目标；
- 生成稳定的代理节点 ID；
- 写入不含密钥的自动探测报告。

Paper/Folia 首次启动会：

- 检测 Paper 或 Folia；
- 生成稳定的 `node-id` 和 `server-type`；
- 在标准同机目录中查找 Velocity 配置；
- 复制 heartbeat 地址和 API key；
- 写入不含密钥的自动探测报告。

管理员可以关闭对应 `manage-*` 开关，保留手工配置所有权。网站地址、数据库、远程凭据、转发密钥和跨机器拓扑不会被猜测。

主要文件：

```text
Velocity/plugins/starx/config.yml
Velocity/plugins/starx/auto-detection.json
Velocity/plugins/starx/compatibility-report.json

Paper/plugins/StarXServer/config.yml
Paper/plugins/StarXServer/auto-detection.json
Paper/plugins/StarXServer/compatibility-report.json
```

## 常用命令

| 平台 | 命令 | 用途 |
|---|---|---|
| Velocity | `/starx doctor` | 查看平台、Java、第三方插件和兼容状态 |
| Velocity | `/sxnodes status [server]` | 查看后端节点与 heartbeat 状态 |
| Paper/Folia | `/starxserver status` | 查看桥接和 heartbeat 状态 |
| Paper/Folia | `/starxserver doctor` | 查看兼容性报告 |
| Paper/Folia | `/starxserver capabilities` | 查看调度模型和平台能力 |
| Paper/Folia | `/starxserver skin <uuid> <name>` | 调试后端皮肤解析 |
| Paper/Folia | `/sx` | 打开玩家账号安全入口 |

`/starxserver` 的别名为 `/starxbackend`。管理、公告、处罚、举报、绑定和投票命令由对应 Velocity 模块注册，并受权限节点控制。

## 扩展 API

Maven 坐标：

```text
io.github.addxiaoyi.starx:starx-api:1.0.0
```

扩展必须将 API 声明为 `compileOnly`，不得 shade 或 relocate。接入方式和兼容承诺：

- [`docs/EXTENSION_API.md`](docs/EXTENSION_API.md)
- [`docs/EXTENSION_COMPATIBILITY_POLICY.md`](docs/EXTENSION_COMPATIBILITY_POLICY.md)
- [`starx-plugins/starx-extension-example/`](starx-plugins/starx-extension-example/)

## 构建与验证

```bash
./gradlew clean check \
  :starx-plugins:starx-universal:check \
  --warning-mode all \
  --no-daemon \
  --console=plain \
  --non-interactive
```

构建门禁覆盖：

- 701 项 Java 测试；
- Linux 和 Windows GitHub Actions；
- Velocity build 606 与 fastutil 输入哈希；
- Paper/Folia 平台识别和配置迁移；
- Extension API 1.0 公共边界；
- Universal JAR 双描述符、重复类、嵌套 JAR 和平台 API 泄漏；
- README、CHANGELOG、发布说明与项目版本一致性；
- Trivy 对最终 JAR 依赖、源码密钥和配置问题的扫描。

最终产物：

```text
starx-plugins/starx-universal/build/libs/starx-universal.jar
```

## 发布文件

每个正式版本发布：

```text
starx-universal-<version>.jar
SHA256SUMS
release-manifest.json
```

变更记录见 [`CHANGELOG.md`](CHANGELOG.md)。

## 验证边界

自动测试已经覆盖代码、打包、跨系统构建、兼容性规则和安全扫描，但不替代真实服务器验收。

当前可确认：

```text
AUTOMATED_VERIFIED
```

仍需在实际部署中单独确认：

```text
STAGING_MULTI_JVM_VERIFIED
PRODUCTION_MULTI_JVM_VERIFIED
REAL_CLIENT_UWORLD_ACCEPTANCE_VERIFIED
```

生产部署前应完成真实 Velocity、Paper/Folia、网站、第三方插件、玩家客户端、重启恢复、凭据轮换和回滚演练。

## 文档

- [`docs/STARX_PLATFORMS.md`](docs/STARX_PLATFORMS.md)：平台职责、安装和线程边界
- [`docs/COMPATIBILITY.md`](docs/COMPATIBILITY.md)：版本矩阵和运行门禁
- [`docs/UWORLD_CONFIGURATION.md`](docs/UWORLD_CONFIGURATION.md)：Uworld 配置
- [`docs/UWORLD_ACCEPTANCE.md`](docs/UWORLD_ACCEPTANCE.md)：Uworld 验收
- [`docs/UWORLD_ENVIRONMENT.md`](docs/UWORLD_ENVIRONMENT.md)：部署和回滚
- [`starx-plugins/starx-universal/README.md`](starx-plugins/starx-universal/README.md)：Universal JAR 结构

## 许可证

StarX 自有代码采用 GNU Affero General Public License v3。内置或派生的第三方组件保留各自许可证和声明，见 `LICENSE`、`LICENSES/`、`NOTICE` 以及源码文件头。
