# Changelog

本文件记录 StarX 正式版本的用户可见变更。公共扩展 API 使用独立版本号，当前为 1.0.0。

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
