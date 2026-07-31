# Changelog

本文件记录 StarX 正式版本的用户可见变更。公共扩展 API 使用独立版本号，当前为 1.0.0。

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
