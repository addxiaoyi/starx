# Changelog

本文件记录 StarX 正式版本的用户可见变更。公共扩展 API 使用独立版本号，当前为 1.0.0。

## [0.4.2] - 2026-08-08

### Added

- 配置入口拆分为 `config/core.yml`、`auth.yml`、`network.yml`、`modules.yml` 和 `uworld.yml`，默认模板加入中文保姆式注释。
- Uworld 支持从 `plugins/starx/assets/uworld/` 加载 `.schem`、`.schematic`、`.nbt` 和 `.litematic` 投影文件，并对配置路径做安全校验。
- 代理端生成并持久化外部握手密钥，受信连接可沿用现有可信登录流程免密进入。

### Fixed

- 修正正版、离线和外部可信身份进入认证流程时的身份来源判断，避免把正常正版登录当成普通注册流程或把离线连接误判为无效会话。
- 修正配置自动化、网站同步、皮肤资料和用户时间字段的兼容处理，并保留旧配置迁移能力。

### Verification

- 配置布局定向测试和 Universal JAR 校验通过；发布工作流会在 GitHub 上继续执行完整 `clean check`。
- 公共扩展 API 保持 1.0.0；真实客户端、线上资源加载和生产部署仍需独立验收。

## [0.4.1] - 2026-08-08

### Added

- Velocity 启动时生成并持久化外部握手密钥；带有正确原始握手字段的受信连接可以跳过密码认证。
- 外部握手认证沿用现有账号会话、重复登录保护和目标服务器路由，不改变公共扩展 API。

### Security

- 密钥使用 32 字节安全随机值，以无填充 URL-safe Base64 保存；文件创建采用原子写入，并拒绝非法内容和符号链接。
- 握手只接受非空服务器地址字段、精确的标记和密钥字段组合，密钥不会写入插件日志或 README。

### Verification

- 外部握手行为测试 4 项、Velocity 认证接线契约测试 1 项通过，0 failures、0 errors。
- 公共扩展 API 保持 1.0.0；完整多平台构建、Universal JAR 和真实外部客户端仍按发布验收流程独立验证。

## [0.4.0] - 2026-08-06

### Added

- 增加网页登录审批流程，已注册玩家可在认证世界中请求一次性网站确认链接。
- 增加已验证网站绑定的信任链路，并将公开在线人数能力同步到网站节点。
- 增加网站 heartbeat 防重复启动、异常恢复和失败原因日志。

### Changed

- 邮箱验证码统一为六位数字，游戏内输入校验与邮件挑战服务保持一致。
- UniAuth 返回“邮箱未验证”时仅允许已有本地档案迁移，不再为未验证外部账号新建本地档案。
- SkinsRestorer 兼容字符串标识和新版本对象标识，旧皮肤数据继续可读。
- JDBC 用户时间字段兼容 SQL 时间戳、ISO 时间、偏移时间和历史毫秒值。
- 在线人数优先汇总已连接后端的实际人数，避免服务器列表使用失真的代理统计。

### Fixed

- 修复网站同步重复启动导致的 heartbeat 调度重复问题。
- 修复能力上报包含未知能力值的问题，只发布 StarX 已支持的能力集合。

### Verification

- 9 个插件模块共 853 项 Java 测试通过，0 failures、0 errors、0 skipped。
- Public API、扩展示例、Paper 26.1/26.2 API 编译门禁和 Universal JAR 边界校验通过。
- 公共扩展 API 保持 1.0.0；真实多 JVM、真实客户端和生产环境部署仍需独立验收。

## [0.3.5] - 2026-08-01

### Added

- 认证 Uworld 增加 Adventure 状态/数据包强制、可配置虚空救援阈值与传送确认状态机。
- 启动时安全、幂等迁移 MiniMOTD HOCON 的 `motds` 数组、图标和最大人数配置。

### Fixed

- Proxy Ping 现在报告代理实际在线人数，动态修正非法的最大人数，同时保留原有样本玩家和其他 Ping 字段。
- MiniMOTD 迁移拒绝符号链接、越界文本、超大或非 PNG 图标，并不覆盖自定义 StarX 配置或现有图标。

### Security

- `starx:main` 消息桥现在只接受后端 `ServerConnection`，拒绝玩家客户端伪造的 Plan 统计和玩家状态事件。
- `starx:anticheat` 消息桥现在只接受后端 `ServerConnection`，拒绝客户端伪造任意玩家 UUID 的违规记录和安全告警。
- StarX 私有消息频道在消费后显式标记为 handled，避免可信消息继续被其他端点转发。

### Fixed

- 修复 BCrypt 严格模式在 UTF-8 密码达到 72 字节时抛出异常的问题；长密码使用 SHA-512 预处理后再执行 BCrypt，现有短密码哈希保持兼容。
- 无效、缺失或格式损坏的密码哈希现在按认证失败处理，不再把运行时异常传播到登录流程。

### Verification

- 新增插件消息来源信任契约测试和长密码、多字节密码、损坏哈希回归测试。
- Velocity 完整测试集、完整 `clean check`、Paper 26.1/26.2 API 编译门禁和 Universal JAR 校验必须在发布前通过。
- 公共扩展 API 保持 1.0.0，Paper/Folia 26.1.x–26.2.x 兼容范围不变。

## [0.3.4] - 2026-07-31

### Added

- 新增 Java 25 下的 Paper 26.1.2 build 71 与 Paper 26.2 build 84 双 API 编译门禁。
- 新增 Minecraft 26.1、26.2 与 Java 25 的运行时兼容规则和回归测试。

### Changed

- Minecraft 26.1.x–26.2.x 现在标记为 `SUPPORTED`；更高的 26.x 允许启动并标记为 `DEGRADED`。
- Java 21 与 Java 25 均标记为 `SUPPORTED`；Universal JAR 继续输出 Java 21 字节码。
- Plugin CI 与 Release 验证改用 Java 25，以便读取 Paper 26.x API，同时保留 Java 21 产物基线。

### Fixed

- 修复 Paper 26.1.2 服务端因兼容矩阵仅允许 Minecraft 1.21.x 而在 `onEnable` 阶段主动退出的问题。

### Verification

- 后端源码通过 Paper `26.1.2.build.71-stable` 与 `26.2.build.84-stable` 编译。
- 完整 `clean check` 与 Universal JAR 校验必须在发布前通过；Paper 26.1.2 生产加载作为发布后部署验收。
- 公共扩展 API 保持 1.0.0，无破坏性 API 变更。

## [0.3.3] - 2026-07-31

### Added

- 新增 UniAuth 玩家资料同步，可按配置在登录成功后补全邮箱和外部用户 ID。
- 新增 AuthX/UniAuth 渐进迁移支持：待迁移账号可继续通过 UniAuth 登录，成功后安全写入本地 BCrypt 密码。
- 新增 UniAuth 响应结构、资料字段兼容和 Velocity 认证模块接线测试。

### Changed

- UniAuth 客户端支持嵌套与多种兼容字段名，并在公钥轮换后自动刷新重试。
- 资料同步默认不覆盖已有本地值，邮箱写入继续执行唯一性保护。
- UniAuth 登录与本地账号迁移的失败路径增加安全降级和脱敏日志。

### Fixed

- 修复仅同步 StarX 已有用户、无法补全 AuthX 历史账号资料的问题。
- 修复 UniAuth 返回校验头不完整或校验失败时可能被当作普通重试的问题。
- 修复认证模块未完整注入 UniAuth 配置和资料同步服务的接线问题。

### Verification

- 完整 `clean test`、发布元数据和 Universal JAR 校验通过。
- 公共扩展 API 保持 1.0.0，无破坏性 API 变更。
- 生产迁移仍需先备份数据库，并使用试运行统计确认账号数量和 UUID 来源。

## [0.3.2] - 2026-07-31

### Added

- 新增安全受限的网络自动化助手：多来源公网地址共识、FRP 检测/托管事务、运行时端口租约、HTTP-01 前置检查和证书续期策略。
- 新增 Bridge Ping 管理端点，以及持久化绑定、邮件验证和跨设备审批挑战的恢复与执行租约。
- 新增后端命令邮箱、节点注册表、运行时端点和网站同步链路的可靠性测试。

### Changed

- Velocity 默认配置升级到 schema 5；HTTP API 继续默认绑定 `127.0.0.1`，FRP 默认仅检测且不自动应用，证书自动执行默认关闭。
- 队列与智能队列增加连接超时、故障切换、状态恢复和并发安全处理。
- Paper/Folia 自动配置改进 heartbeat 发现、节点身份推断和启动顺序容错。
- 皮肤桥、NapCat、网站同步和兼容性报告增加更严格的降级与错误处理。

### Fixed

- 修复绑定挑战重复执行、重启后恢复和并发领取时的竞态条件。
- 修复有效的 Paper/Folia `/starxaccount` 别名被 Velocity 文档契约误判为已删除命令。
- 修复动态 HTTP 端口、FRP 公网地址和后端桥接状态不一致时的错误报告。

### Verification

- 完整插件 `clean test`、Public API、扩展示例和 Universal JAR 校验通过。
- 公共扩展 API 保持 1.0.0，无破坏性 API 变更。
- 自动化验证不替代真实多 JVM、公网路由、FRP 和证书签发环境验收。
## [0.3.1] - 2026-07-28

### Changed

- 增加统一运行时兼容性报告和严格平台门禁。
- 明确区分 Paper、Folia 与不受支持的 Spigot/CraftBukkit。
- 为 Velocity、Paper/Folia 和第三方软依赖增加版本状态。
- Paper/Folia 配置加入 schema、备份、原子迁移和无敏感值迁移报告。
- Gradle Wrapper 升级至 9.6.1，Shadow 升级至 9.6.1。
- GitHub Actions 改为 Linux/Windows 全量 `clean check`。
- 重写 README，按部署、功能、兼容、命令和验证边界组织内容。

### Added

- Velocity `/starx doctor` 和 Paper/Folia `/starxserver doctor`。
- Dependabot、Dependency Review、私有仓库可用的 Trivy 安全扫描。
- 最终 Universal JAR 依赖覆盖检查。
- 自动 GitHub Release、SHA256SUMS、发布清单和人工维护的版本说明。
- README、CHANGELOG、发布说明与项目版本一致性门禁。

### Fixed

- 修复 Windows CI 中许可证文件 CRLF 差异。
- 修复 Trivy severity 配置和 Java JAR 扫描模式。
- 收紧异常版本字符串、损坏 YAML、配置结构冲突和未来 schema 的处理。

### Verification

- Java tests: 701 passed, 0 failed, 0 skipped.
- Ubuntu GitHub Actions: passed.
- Windows GitHub Actions: passed.
- Universal JAR verification: passed.
- Trivy packaged dependencies identified: 5.
- Trivy HIGH findings: 0.
- Trivy CRITICAL findings: 0.

生产多 JVM、真实玩家客户端和故障回滚仍需在实际环境独立验收。

## [0.3.0] - 2026-07-27

- 发布单一 `starx-universal.jar`，同时支持 Velocity、Paper 和 Folia 加载。
- 发布 StarX Extension API 1.0.0。
- 加入网站节点同步、皮肤纹理同步和永久归档。
- 加入 Velocity 与后端自动配置、节点发现和 heartbeat 配置。
- 完成 Universal JAR 重复类、描述符和平台 API 边界校验。
