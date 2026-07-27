# StarX 兼容性与运行门禁

本文定义 StarX 的运行时兼容性状态、已认证范围、诊断入口和升级原则。
兼容性报告只包含平台、版本、组件和状态，不包含 API key、node token、bootstrap token、数据库密码或 webhook secret。

## 状态语义

| 状态 | 含义 | 默认处理 |
|---|---|---|
| `SUPPORTED` | 位于已认证矩阵 | 正常启动 |
| `UNKNOWN` | 已检测到组件，但无法可靠解析版本或没有稳定版本规则 | 启动并告警 |
| `DEGRADED` | 可能可运行，但未完成该版本的完整认证 | 启动并告警 |
| `UNSUPPORTED` | 明确超出平台硬边界 | `strict-platform: true` 时拒绝启动 |

`UNKNOWN` 和 `DEGRADED` 不会被伪装成“已支持”。`UNSUPPORTED` 也不会静默降级。

## 核心平台矩阵

| 组件 | 已认证范围 | 说明 |
|---|---|---|
| Java | 21 | Java 17 及以下拒绝；高于 21 标记为 `DEGRADED`，直到补充认证 |
| Velocity | `3.5.0-SNAPSHOT build 606` | Uworld 使用经过该构建验证的内部 API；其他构建必须重新验收 |
| Minecraft 后端 | `1.21.0` 至 `1.21.11` | Paper/Folia 入口共享；新的 1.21 补丁先标记为 `DEGRADED` |
| Paper | 1.21 系列，编译基线 1.21.11 | 使用 Paper API 和安全调度封装 |
| Folia | 1.21 系列，编译基线 1.21.11 | `folia-supported: true`，所有运行任务必须经过 Folia 调度器 |

Universal JAR 只保证同一文件能被三种加载器正确选择入口，不代表未经测试的平台版本自动获得认证。

## 第三方插件矩阵

第三方插件均为软依赖。未安装时报告为 `SUPPORTED`，表示 StarX 能安全关闭对应集成，而不是该插件功能存在。

| 集成 | 已认证主版本 |
|---|---|
| LuckPerms | 5.x |
| Floodgate | 2.x |
| TAB | 5.x–6.x |
| Plan | 5.x |
| Geyser | 2.x |
| SkinsRestorer | 15.x |
| PlaceholderAPI | `>=2.11,<3` |
| Raknetify | presence-only；版本标记为 `UNKNOWN` |

超出主版本矩阵时保持软依赖和安全降级，但状态为 `DEGRADED`，必须在 staging 重新验证后才能更新矩阵。

## 配置

Velocity：

```yaml
compatibility:
  strict-platform: true
  report-file: "compatibility-report.json"
```

Paper/Folia：

```yaml
schema-version: 1

compatibility:
  strict-platform: true
  report-file: "compatibility-report.json"
```

报告分别写入：

```text
plugins/starx/compatibility-report.json
plugins/StarXServer/compatibility-report.json
```

`report-file` 必须位于插件数据目录内部；路径逃逸会拒绝启动。

## 诊断命令

Velocity：

```text
/starx doctor
```

Paper/Folia：

```text
/starxserver doctor
```

命令显示总体状态、检测版本、支持范围和每项状态，不显示凭据。

## 后端配置升级

Paper/Folia 配置具有独立 schema。升级器会：

1. 拒绝比当前插件更新的 schema；
2. 保留管理员已有的有效值；
3. 损坏 YAML、结构冲突或非法 schema 会拒绝启动且不改写原文件；
4. 递归补齐新增默认键；
5. 对旧配置创建时间戳备份；
6. 原子写回；
7. 生成只包含路径、不包含配置值的迁移 JSON；
8. 首次启动不制造无意义备份。

Velocity 继续使用其现有的 `ConfigSchemaUpgrader`，两端都禁止依赖热重载完成核心迁移。

## 认证升级流程

任何平台或第三方插件版本升级都应按以下顺序处理：

1. 在隔离环境构建 Universal JAR；
2. 执行全部 Gradle 测试和 universal 合包校验；
3. 启动真实 Velocity、Paper/Folia；
4. 执行 `doctor`；
5. 完成登录、转服、Uworld、bridge、heartbeat、网站同步和皮肤同步；
6. 测试目标离线、网站离线、重启、凭据轮换与回滚；
7. 保存版本、JAR SHA-256 和验收证据；
8. 只有通过后才扩大 `CompatibilityRules` 中的认证范围。

## 验证边界

自动化测试和兼容性报告不能替代真实生产验收。以下状态必须独立记录：

```text
AUTOMATED_VERIFIED
STAGING_MULTI_JVM_VERIFIED
PRODUCTION_MULTI_JVM_VERIFIED
```

没有真实多 JVM、真实客户端和故障演练证据时，不得把后两项标为通过。
