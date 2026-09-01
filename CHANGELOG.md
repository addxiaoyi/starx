# Changelog

本文件记录 StarX 正式版本的用户可见变更。公共扩展 API 使用独立版本号，当前为 1.0.0。

## [0.6.4] - 2026-09-01

### Fixed

- 修复跨服 TAB/玩家列表刷新时的重复调度和无效网络请求，降低高并发下的代理线程与带宽开销。
- 完善旧版单文件配置、分片配置的自动迁移和缺失项补全，升级后保留现有设置。
- 优化皮肤解析：网站未绑定角色时优先回退到正版玩家名称皮肤，并兼容其他皮肤站提供的有效 authlib-injector 纹理。

### Added

- TAB 可在任意子服显示全网在线人数、当前子服人数及在线子服人数，支持配置子服别名优先显示。
- PAPI 可读取子服内存占用等节点运行数据，并通过缓存快照避免每次占位符解析触发网络请求。

### Upgrade

- 使用 `starx-universal-0.6.4.jar` 替换 Velocity 和每个 Paper/Folia 子服中的旧 JAR 后完整重启；不要使用 `/reload`。
- 首次启动会自动补全新配置项并迁移旧配置；建议检查 `config/modules.yml` 中的 `player-list.server-aliases` 映射。

## [0.6.3] - 2026-08-31

### Security

- **皮肤纹理分发**：网站皮肤档案只接受网站自身或 Mojang 官方纹理域名，阻止内网、任意外部域名和追踪地址被广播给客户端。
- **外部皮肤命令**：`/sxskin url` 仅接受 `textures.minecraft.net` 的 HTTPS 纹理地址。
- **账户删除**：关闭缺少可验证终端用户身份的自助删除 HTTP 路由，保留受保护的管理员删除入口。

### Fixed

- **第三方皮肤站兼容**：仅在登录档案包含结构有效的 HTTPS `SKIN` 纹理时保留 authlib-injector 属性；畸形属性会回落到正常皮肤解析流程。

## [0.6.2] - 2026-08-31

### Fixed

- **Velocity 登录崩溃**：修复 TabList 模块在 `PostLoginEvent` 中使用未注册模块实例创建调度任务的问题，改用已注册的 StarX 插件实例作为任务 owner。

## [0.6.1] - 2026-07-26

### Security

- **身份认证硬化**：修复 5 个安全漏洞
  - `AuthService.deleteUser` 添加操作者身份验证（密码+TOTP）和级联清理
  - `SessionManager` 添加会话固定攻击保护，防止会话被恶意覆盖
  - `TotpGenerator` 添加 TOTP 验证码重放保护，验证码只能用一次
  - `AuthService.logout` 添加可选的 lease 验证，新增 `forceLogoutInternal` 方法供系统内部使用
  - 恢复码消费添加 `synchronized` 锁，防止并发竞态条件

### Added

- **安全测试套件**：`SecurityVulnerabilityFixesTest` 提供 9 个安全修复验证测试
- **独立压力测试**：`StressTestRunner` 提供数据库和密码验证压力测试
- **压力测试套件**：
  - `JdbcSchemaStressTest` (5 tests) - 数据库并发索引创建、列添加、约束检测
  - `AuthServicePasswordVerificationStressTest` (7 tests) - BCrypt 密码哈希并发验证

### Changed

- **版本号更新**：从 0.5.3 更新至 0.6.1
- **公共扩展 API**：保持 1.0.0 兼容性

### Verification

- 所有 27 个测试全部通过（数据库并发测试 5/5、安全测试 9/9、密码验证测试 7/7、独立压力测试 6/6）
- 所有模块编译通过
- 建议所有用户尽快升级以获取安全修复

## [0.5.3] - 2026-07-26

### Security

- **身份认证硬化**：修复 3 个高危安全漏洞
  - `AuthService.deleteUser` 添加操作者身份验证（密码+TOTP）和级联清理
  - `SessionManager` 添加会话固定攻击保护，防止会话被恶意覆盖
  - `TotpGenerator` 添加 TOTP 验证码重放保护，验证码只能用一次
  - `AuthService.logout` 添加可选的 lease 验证，新增 `forceLogoutInternal` 方法供系统内部使用
  - 恢复码消费添加 `synchronized` 锁，防止并发竞态条件

### Added

- **安全测试套件**：`SecurityVulnerabilityFixesTest` 提供 9 个安全修复验证测试
- **独立压力测试**：`StressTestRunner` 提供数据库和密码验证压力测试

### Verification

- 所有 27 个测试全部通过（数据库并发测试 5/5、安全测试 9/9、密码验证测试 7/7、独立压力测试 6/6）
- 所有模块编译通过

## [0.6.0] - 2026-08-27

### Added

- **扩展依赖检查器**：`ExtensionDependencyChecker` 提供自动依赖解析、版本兼容性检查和加载顺序验证。
- **扩展热重载管理器**：`ExtensionHotReloadManager` 支持在不重启服务器的情况下动态重载扩展，包括状态保存与恢复。
- **扩展兼容性管理器**：`ExtensionCompatibilityManager` 提供平台兼容性检查、API兼容性检查、版本冲突检测和兼容性适配器注册。
- **扩展配置助手**：`ExtensionConfigurationHelper` 提供类型安全的配置访问、范围限制、枚举转换、配置验证等便捷功能。
- **扩展命令注册器**：`ExtensionCommandRegistrar` 提供命令注册、别名管理、权限控制、Tab补全等功能。
- **扩展依赖管理器**：`ExtensionDependencyManager` 提供依赖关系管理、循环依赖检测、版本冲突报告等功能。
- **标签页动画API**：完整的动画系统支持流动动画、脉冲动画、滚动文字动画、渐变动画，支持自定义速度、方向、颜色渐变等。
- **标签页图片API**：`TabImage` 接口和 `Base64TabImage`、`UrlTabImage` 实现，支持在标签页中嵌入图片。
- **标签页管理系统**：`DefaultTabList`、`TabListRegistry`、`TabListManager` 提供完整的标签页管理和更新机制。
- **Velocity热重载监听器**：Velocity平台的 `TabListModule` 支持玩家登录/登出事件监听和标签页自动刷新。
- **扩展开发指南**：完整的扩展开发文档，包括基础教程、最佳实践、API参考、故障排除等。

### Changed

- **starx-api模块**：添加 `adventure-api`、`adventure-text-serializer-legacy`、`adventure-text-serializer-plain` 和 `adventure-text-serializer-gson` 的 `compileOnly` 依赖。
- **版本号更新**：从 0.5.2.1 更新至 0.6.0。

### Fixed

- **TabPlayerEntry接口**：将内部接口移至 `TabList` 内部类，修复了直接导入导致的编译错误。
- **BuiltEntry记录**：将 `BuiltEntry` 从记录类改为普通类，解决了方法名与记录组件名冲突的问题。
- **GradientAnimation**：修复 `debugString()` 方法调用错误，改用 `LegacyComponentSerializer` 进行序列化。
- **ExtensionConfigurationHelper**：修复验证结果记录类中缺少 `List` 和 `Map` 导入的问题。
- **getEnum方法**：修复泛型类型擦除导致的 `T.valueOf()` 编译错误。
- **TabListModule**：修复 `EventListener` 接口错误，改用匿名类的方式注册事件监听器。
- **DatabaseConfig**：修复 `AccountIdentityResolverTest` 中 `DatabaseConfig` 构造函数缺少参数的问题。

### Verification

- 所有模块编译通过，包括 `starx-api`、`starx-common`、`starx-velocity` 等。
- 新增的扩展系统API全部编译通过。
- 标签页动画API、图片API、标签页管理API全部编译通过。

## [0.5.2.1] - 2026-08-26

### Security

- **YAML 加载器加固**：限制 `setMaxAliasesForCollections(100)`，防止 YAML BOMB（深度放大）攻击。

## [0.5.2] - 2026-08-25

### Fixed

- **ConfigLoaderUworldTest 断言损坏**：update.yml 分片未包裹在顶级 `update:` 键下，导致 config root 多出 7 个键，keySet 断言失败。
- **VelocityWebsiteSyncConfigParser 点号键解析错误**：解析器读取 `"circuit-breaker.enabled"` 等点号键，但 YAML 实际是嵌套结构，导致熔断器配置永远用默认值。
- **AsyncHttpClient.sync 最终块误记成功**：`finally` 块在异常情况下仍调用 `recordHeartbeatSuccess`，导致失败请求被错误计为成功。
- **publishHeartbeatInternal 无节流重入**：积压拉取循环在异常状态下可能形成持续紧密异步重入，导致 CPU 飙升；加 8 轮上限 + 500ms 间隔节流。
- **CircuitBreaker 死字段清理**：移除从来使用的 `lastFailureTime` 字段。
- **UpdateManager .tmp 残留**：下载失败或文件移动失败时不清理临时文件。

### Added

- 插件自动更新检查器（GitHub Releases / Maven Central），disabled by default。

## [0.5.1] - 2026-08-25

### Fixed

- 后端心跳命令解码补齐 `proxy.skin.update` 支持，修复 mailbox 中积压的皮肤推送因被判定为不支持命令而导致心跳交换失败、退回 player-carried bridge 的问题。
- 纹理同步在受限网络环境下默认禁用 Mojang API 回退，仅读取 SkinsRestorer 本地缓存，避免 JDK HttpClient 线程因 SSL 握手超时堆积导致 CPU 飙升。
- 纹理缓存从"满则清空"改为 LRU+TTL 淘汰策略，消除缓存抖动引发的重复下载风暴。

### Added

- 网站同步 HTTP 客户端接入熔断器与信号量限流：连续失败达到阈值后快速拒绝请求，防止雪崩式线程堆积；新增 `website-sync.circuit-breaker.*` 配置项。
- 新增运行时指标收集（心跳/清单/纹理上传成败、缓存命中率、熔断与限流拒绝计数、延迟），通过 `VelocityWebsiteSync.metrics()` 与 `circuitState()` 暴露。
- 新增插件自动更新检查器：支持 GitHub Releases 与 Maven Central 两个更新源，下载受熔断、信号量限流、超时与 64 MiB 大小上限保护；新增 `update.yml` 配置分片（默认关闭）。

### Verification

- 全部模块编译通过；心跳交换、皮肤桥接与网站同步相关测试门禁保持绿色。

## [0.5.0] - 2026-08-18

### Fixed

- 管理 API 统一按请求目标的 UUID 归属执行查询、绑定、处罚、投票、皮肤和删除操作，避免仅凭玩家名误操作同名账户。
- 账户身份、挑战、会话、可信设备、IP、绑定、网站绑定及管理记录的擦除与删除任务完成标记在同一数据库事务中提交，避免完成标记失败后重放删除新注册账户。
- 收紧跨设备挑战、外部身份、会话替换和连接队列的 UUID 生命周期校验，阻止旧连接异步结果影响新连接。

### Verification

- 增加账户删除事务回滚、管理 API 身份归属、连接替换和外部身份 UUID 一致性的回归覆盖。
- 公共扩展 API 保持 1.0.0；生产部署和真实客户端验收仍需独立执行。

## [0.4.9] - 2026-08-13

### Fixed

- 外部 Yggdrasil 身份校验改为严格比较 UUID，不再用响应正文中的玩家名片段判断身份。
- Premium、UniAuth 和网页登录兼容路径不再接受任意同名账号；离线兼容别名只接受确定性的 OfflinePlayer UUID。
- 增加不同 UUID 同名、同 UUID 改名和第三方身份冲突的回归测试。

### Verification

- `:starx-plugins:starx-common:test` 与 `:starx-plugins:starx-velocity:test` 通过。
- universal JAR 的平台描述符、版本和重复类边界由发布校验验证。

## [0.4.8] - 2026-08-13

### Fixed

- 拒绝已确认但已过期的跨设备绑定挑战，避免过期令牌继续执行登录、邮箱或皮肤操作。
- 账号注销时同步删除网站绑定，避免注销后残留网站信任关系。
- 让 Premium 与 Floodgate 自动登录严格遵守各自的 bypass 开关，并修复绑定冲突仍返回成功的问题。

### Verification

- common 与 velocity 主代码、测试代码编译通过。
- 定向测试因本机 Gradle worker 缺失 `worker.org.gradle.process.internal.worker.GradleWorkerMain` 暂未能运行。

## [0.4.5] - 2026-08-12

### Fixed

- 统一本地密码和已验证身份的后续认证决策，确保 TOTP、风险校验和网页登录确认不会因认证来源不同而被跳过。
- 修复账号持久化 UUID 与当前连接 UUID 不一致时的登录、TOTP、恢复码和网页确认流程，避免误报无效会话。
- 修复密码修改后的迁移状态未完成导致后续认证路径不一致的问题。
- 修复外部认证服务异常被错误显示为密码错误的问题；服务不可用时返回明确的服务状态并保持拒绝准入。

### Verification

- 增加认证来源、账号别名 UUID、迁移状态和外部服务异常的回归覆盖。
- 公共扩展 API 保持 1.0.0；生产部署和真实客户端验收仍需独立执行。

## [0.4.4] - 2026-08-11

### Added

- 新增管理员密码修改命令：`/sxadmin setpassword <玩家> <新密码>`。
- 新增权限 `starx.password.reset`；控制台可执行，玩家必须拥有该权限。

### Security

- 命令复用认证服务的密码校验、哈希和现有信任撤销流程。
- 新密码不会回显到命令反馈或插件日志。

## [0.4.3] - 2026-08-10

### Changed

- 强化 SkinsRestorer 兼容性，兼容当前及旧版皮肤存储接口，并根据运行时参数类型选择可用的皮肤数据写入方式。
- 皮肤查询增加 UUID、玩家名和旧版参数签名的回退路径；可选存储接口缺失时继续降级运行，不因单个兼容接口缺失中断插件启动。

### Fixed

- 修复新版 SkinsRestorer 不再提供 `setSkinData` 时产生 `NoSuchMethodException` 并导致皮肤同步失败的问题。
- 皮肤绑定清理会在目标版本支持时同步清理自定义皮肤数据，旧版本仍保留玩家绑定清理能力。

### Verification

- 运行 common/server 皮肤兼容性测试和 Universal JAR 边界检查。
- 公共扩展 API 保持 1.0.0；生产部署和真实客户端验收仍需独立执行。

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
# StarX Changelog

## [0.4.6] - 2026-08-13

- 修复未注册网站的老游戏账号在高风险登录时被错误送入网页确认、导致无法登录的问题。
- 只有已验证网站绑定的账号才触发网页审批；未绑定账号按本地密码/TOTP流程继续认证。
## [0.4.7] - 2026-08-13

- 修复未完成网站绑定时手动 `/login web` 仍进入等待确认的问题。
- 修复全局关闭 TOTP 后高风险账号仍被要求输入不可用验证码的问题。
- 修复认证异步派发异常和网页登录审批超时后残留认证会话的问题。
- 修复管理端网站绑定状态与已验证绑定表不一致的问题，并让正版免密开关真正生效。
