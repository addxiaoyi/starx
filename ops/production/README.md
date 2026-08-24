# StarMC 生产控制面

该目录提供 Windows 上 Velocity + Paper + StarX 的生产生命周期控制面，覆盖不可变发布、配置门禁、密钥 ACL、冷备份、异地镜像、事务恢复、版本回滚、进程身份校验、watchdog 熔断和计划任务。

它不会替代云厂商防护、DNS、TLS、网络防火墙、容量规划或人工 EULA 决策。

## 安全默认值

- 公网 Velocity 默认 `online-mode=true`。
- Paper 只绑定回环地址并保持 `online-mode=false`，使用 modern forwarding。
- HTTP API 与 RCON 只允许回环绑定。
- 配置为公网离线模式时，控制面默认拒绝运行。
- RCON、API key、forwarding secret 从独立密钥文件读取。
- 承载密钥的运行时配置关闭 ACL 继承，仅允许当前服务身份、SYSTEM 和 Administrators。
- Paper EULA 必须由运营人员审阅后手动写入 `eula=true`，初始化器不会代为同意。
- 业务模块默认最小启用；Uworld、第三方认证、聊天桥接和外部集成必须逐项显式开启。

## 1. 全套代码验证

```powershell
npm run production:verify
```

只验证控制面，不重复运行 Gradle：

```powershell
npm run production:verify:control
```

验证内容包括：

- 所有生产 PowerShell 文件 AST；
- 安全配置模板；
- 两版不可变发布；
- 失败发布自动恢复当前/上一版本状态；
- 原子冷备份与独立镜像；
- 事务恢复与逐文件 SHA-256；
- 损坏备份拒绝；
- 公网离线模式拒绝；
- PID 重用/误杀防护；
- watchdog 熔断；
- Velocity 结构化健康接口测试和 JAR 构建。

## 2. 初始化生产目录

```powershell
Copy-Item .\ops\production\production.config.example.json .\ops\production\production.config.json

.\ops\production\Initialize-StarXProduction.ps1 `
  -ConfigPath .\ops\production\production.config.json `
  -ProductionRoot D:\StarMC
```

初始化后必须检查并修改：

- Java 21 / Java 25 路径；
- JVM 堆大小；
- nodeId、serverType、MOTD、最大人数；
- 公网监听地址和端口；
- `backup.mirrorRoot`，应指向不同磁盘、NAS 或受保护同步目录；
- Bedrock 是否启用。

然后审阅 Mojang EULA，并由运营人员自行创建：

```powershell
Set-Content D:\StarMC\runtime\paper\eula.txt 'eula=true' -Encoding ASCII
```

## 3. 构建不可变发布

发布必须包含与目标 Paper JAR 同版本生成的完整 `paper-global.yml`。不要手写精简版 Paper 全局配置。

```powershell
.\ops\production\New-StarXRelease.ps1 `
  -ConfigPath .\ops\production\production.config.json `
  -ReleaseId 2026.07.25-001 `
  -VelocityJar D:\artifacts\velocity.jar `
  -PaperJar D:\artifacts\paper.jar `
  -UniversalPlugin D:\artifacts\starx-universal.jar `
  -PaperGlobalTemplate D:\artifacts\paper-global.yml `
  -AdditionalArtifact @(
    'D:\artifacts\Geyser.jar|velocity/plugins/Geyser.jar',
    'D:\artifacts\floodgate.jar|velocity/plugins/floodgate.jar',
    'D:\artifacts\Geyser-Velocity|velocity/plugins/Geyser-Velocity|mutable'
  )
```

`mutable` 只用于部署后必须注入端口或密钥的配置模板。JAR 和其他可执行制品不得标记为 mutable。

## 4. 部署

```powershell
.\ops\production\Deploy-StarXRelease.ps1 `
  -ConfigPath .\ops\production\production.config.json `
  -ReleaseId 2026.07.25-001
```

部署过程：

1. 验证发布清单及哈希；
2. 身份安全停服；
3. 创建冷备份并校验异地镜像；
4. 原子复制完整版本；
5. 注入当前密钥和生产配置；
6. 静态门禁；
7. Velocity 先启动、Paper 后启动；
8. 进程身份、监听归属和 HTTP 健康门禁。

部署失败时自动恢复原运行文件以及原 `current-release.json` / `previous-release.json`。玩家数据不会被自动回退，避免覆盖部署期间产生的新数据；预部署备份路径会出现在错误和事件日志中。

## 5. 状态、停止和启动

```powershell
.\ops\production\Test-StarXProduction.ps1 `
  -ConfigPath .\ops\production\production.config.json

.\ops\production\Stop-StarXProduction.ps1 `
  -ConfigPath .\ops\production\production.config.json

.\ops\production\Start-StarXProduction.ps1 `
  -ConfigPath .\ops\production\production.config.json
```

`Stop` 会核对 PID、Java 路径、JAR 命令行、启动时间和端口所有权。状态陈旧或 PID 被复用时默认拒绝终止。

## 6. 备份和恢复

冷备份：

```powershell
.\ops\production\Invoke-StarXScheduledBackup.ps1 `
  -ConfigPath .\ops\production\production.config.json
```

恢复必须在生产栈停止时执行：

```powershell
.\ops\production\Restore-StarXProduction.ps1 `
  -ConfigPath .\ops\production\production.config.json `
  -BackupPath D:\StarMC\backups\<backup-id>
```

恢复会验证备份全部 SHA-256、事务替换数据集、验证恢复目标、失败时逆序恢复原数据，并重新注入当前密钥和收紧 ACL。

至少每季度从镜像备份在隔离主机完成一次真实恢复演练。

## 7. 版本回滚

```powershell
.\ops\production\Rollback-StarXProduction.ps1 `
  -ConfigPath .\ops\production\production.config.json
```

默认回到记录的上一版本。版本回滚不会回退玩家数据。只有确认数据结构不兼容时，才另行执行指定备份恢复。

## 8. Watchdog 与计划任务

先手动运行一次：

```powershell
.\ops\production\Watch-StarXProduction.ps1 `
  -ConfigPath .\ops\production\production.config.json `
  -Once
```

安装 SYSTEM 计划任务：

```powershell
.\ops\production\Install-StarXProductionTasks.ps1 `
  -ConfigPath .\ops\production\production.config.json `
  -BackupTime '04:00'
```

Watchdog 提供重启窗口、次数限制、熔断冷却和重复失败后的代码版本回滚。熔断时写入 `state/alert.json` 和 `logs/production-events.jsonl`，应由外部监控系统采集并发送告警。

## 9. 健康接口

`GET /v1/health` 返回 HTTP 200 JSON，包含代理在线人数、注册后端数、可接纳流量后端数、JVM 堆内存、处理器数与运行时长。

该接口不返回玩家 UUID、后端地址、API key 或密钥。生产配置只允许回环绑定；需要外部采集时，通过带认证和 TLS 的反向代理暴露。

## 10. 事件与审计

生产事件追加写入：

```text
<logRoot>/production-events.jsonl
```

重点采集：

- `release-deploy-failed`
- `release-auto-rollback`
- `backup-mirror-failed`
- `backup-restore-failed`
- `watchdog-restart-failed`
- `watchdog-breaker-open`

不要把 `production.config.json`、`secrets/`、运行状态或日志提交到 Git；仓库已配置忽略规则。
