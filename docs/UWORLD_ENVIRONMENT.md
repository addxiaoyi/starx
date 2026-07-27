# Uworld 环境与部署目标

本文定义 `starx-velocity.jar` 已提供并经静态门禁验证的运维目标拓扑、部署、回滚和环境验收。它是运维基线，不是平台认证或部署成功证明；Windows 与 Linux 的 staging/production 执行、备份恢复和失败回滚演练目前仍为 `UNVERIFIED`。只有安装的 JAR 与本次候选 SHA-256 一致、环境 doctor 通过，并完成 [25 项真实客户端验收](UWORLD_ACCEPTANCE.md#真实客户端验收) 后，才能宣称该候选已部署且功能完成。

## 支持范围

| 项目 | 生产要求 |
|---|---|
| Java | 21 |
| Velocity | `3.5.0-SNAPSHOT` build 606 |
| 构建 | Gradle Wrapper 8.10 |
| Velocity 插件 | `plugins/starx-velocity.jar`，只能有一份 StarX JAR |
| Uworld | 内嵌在 StarX Velocity 中，不单独部署 |
| LimboAPI | 不得安装外置 JAR |
| 后端 | Paper，启用 Velocity modern forwarding |
| 数据库 | SQLite `plugins/starx/data.db`，连接池上限 2 |
| 运维目标形态 | Windows Service；Linux systemd + GNU coreutils；当前仅有文档与静态门禁证据，实际部署/恢复演练为 `UNVERIFIED` |
| 运维 shell | Windows PowerShell 5.1+ 或 PowerShell 7；Linux 使用 Bash、`pwsh`、`runuser`、`realpath`、`sha256sum`、`install`、`systemctl` |
| 协议能力 | Velocity `ProtocolVersion.MAXIMUM_VERSION` 至少为 776；这不是客户端最低版本 |
| 热重载 | 不支持；必须完整停止和启动 Velocity |
| 容量承诺 | 暂无通用并发、内存、RTO 或客户端版本承诺；上线前必须按本站负载压测 |

当前发行物只捆绑 SQLite JDBC 驱动。MySQL、PostgreSQL 和 H2 不属于本基线，除非后续发行物明确捆绑驱动并提供集成测试。

Windows Service 与 Linux systemd 是当前唯一提供部署、备份、回滚和门禁脚本的运维目标形态，脚本结构已经静态验证，但尚未取得 staging/production 执行证据，因此不能标为已认证或已支持生产环境。macOS、Docker/Podman、Pterodactyl/Pelican 和其他面板同样为 `UNVERIFIED`；在补齐持久卷、UID/GID、SIGTERM 停机宽限、健康检查、重启策略和恢复演练前，不得标为受支持生产环境。操作系统发行版必须能够运行 Java 21 和上表工具；本文不把某个未实测的 OS 版本写成已认证。

客户端版本支持必须由 [Uworld 验收](UWORLD_ACCEPTANCE.md) 中同一候选的最低、最高和至少一个中间版本记录给出。底层可预生成某版本协议数据，不能替代真实客户端证据。

## 拓扑和信任边界

```text
Internet
   |
   v
public Velocity :25565 (or the published proxy port)
   |
   | modern forwarding, exact registered target
   v
private Paper :25566
   |
   v
local SQLite plugins/starx/data.db
```

- 只向公网开放 Velocity 的 Minecraft 端口。
- Paper 监听回环或私网地址，防火墙只允许 Velocity 主机访问；玩家不得绕过代理直连 Paper。
- StarX HTTP 管理端口默认绑定 `127.0.0.1`。需要跨主机访问时应置于经过认证的反向代理或管理网之后，不能直接暴露公网。
- Paper 的 RCON、query、JMX 和 management server 保持关闭，除非另有独立安全设计。
- SQLite 是本地文件，不放在网络共享目录，也不允许其他 StarX 进程同时写入同一数据库。

## Velocity 和 Paper

### Velocity build 606

使用 build 606 的规范 `[servers]` 映射语法。不要使用旧的数组式 server fixture，也不要配置 `try` 或任意首服 fallback：

```toml
bind = "0.0.0.0:25565"
online-mode = true
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"

[servers]
lobby = "127.0.0.1:25566"
```

`plugins/starx/config.yml` 的 `uworld.auth.target-server` 必须与这里的键完全一致：

```yaml
modules:
  starx.auth:
    enabled: true
  starx.uworld:
    enabled: true

uworld:
  enabled: true
  auth:
    target-server: "lobby"
```

冷启动只要求目标名称已在 `[servers]` 注册，因为受控 smoke 环境不建立真实玩家转服。环境 doctor 传入 `-RequireBackend` 时还要求该地址可从 Velocity 主机建立 TCP 连接；真实客户端的认证和 `/uworld leave` 验收也必须实际连接同一个 `RegisteredServer`。

### Paper modern forwarding

Paper 的 `server.properties`：

```properties
online-mode=false
server-ip=127.0.0.1
server-port=25566
```

Paper 的 `config/paper-global.yml`：

```yaml
proxies:
  velocity:
    enabled: true
    online-mode: true
    secret: "由部署系统注入的转发密钥"
```

Paper 的 `proxies.velocity.online-mode` 必须与 Velocity 的 `online-mode` 一致。`proxies.velocity.secret` 必须与 Velocity `forwarding-secret-file` 中的非空值逐字节一致。密钥只从密码管理系统部署；不要放入 Git、命令行参数、工单、截图或日志。环境 doctor 只在内存中比较两者，不输出值。

### 防火墙

| 流量 | 规则 |
|---|---|
| 玩家到 Velocity Minecraft | 允许发布端口 |
| Velocity 到 Paper | 仅允许明确的代理主机和后端端口 |
| 公网到 Paper | 拒绝 |
| 公网到 StarX HTTP/RCON/query/management | 拒绝 |
| 运维到管理接口 | 仅管理网或本机，另加认证 |

跨主机部署时用 Paper 私网地址替换 `127.0.0.1`，同时保持相同的访问控制。不要通过把后端端口公开来解决连通性问题。

网络边界不能只靠配置推断。每次生产验收必须保存以下人工证据；环境 doctor 不扫描云安全组、宿主机外层 NAT 或面板端口映射：

| 检查 | 证据 |
|---|---|
| Velocity 公网监听 | `Get-NetTCPConnection` 或 `ss -ltnp` 显示仅发布的 Minecraft 端口；记录进程与地址 |
| Paper 私网监听 | `server-ip` 为明确回环/私网地址，`server-port` 与 `[servers]` 目标端口一致 |
| Paper 直连拒绝 | 从公网或隔离测试主机连接 Paper 端口失败；从 Velocity 主机连接成功 |
| 管理面拒绝 | HTTP、RCON、query、JMX、management 端口未公网监听或被防火墙拒绝 |
| 云/容器规则 | 保存安全组、Windows Firewall、nftables/iptables 或端口映射的规则导出路径 |

## 文件和权限

受支持的目录形态：

```text
VELOCITY_HOME/
  velocity-3.5.0-SNAPSHOT-606.jar
  velocity.toml
  forwarding.secret
  plugins/
    starx-velocity.jar
    starx/
      config.yml
      data.db
      data.db-wal            # SQLite 运行时可能存在
      data.db-shm            # SQLite 运行时可能存在
      uworld/core.yml
      *.schem|*.schematic|*.nbt
```

运行 Velocity 的服务账户必须：

- 对 Velocity JAR、插件 JAR、配置、转发密钥和 loader 文件有读取权限。
- 对 `logs/`、`plugins/starx/` 和 SQLite 父目录有创建、写入、重命名和删除权限。
- 是这些运行时目录的实际所有者；不要依赖管理员或 root 启动一次后留下的混合所有权。
- 不向交互式普通用户开放 `forwarding.secret`、StarX 密钥或数据库读取权限。

Linux 示例：

```bash
export VELOCITY_HOME=/srv/velocity
export VELOCITY_USER=velocity
export VELOCITY_GROUP=velocity

sudo chown -R "$VELOCITY_USER:$VELOCITY_GROUP" \
  "$VELOCITY_HOME/logs" "$VELOCITY_HOME/plugins/starx"
sudo chmod 750 "$VELOCITY_HOME/plugins/starx"
sudo chown "$VELOCITY_USER:$VELOCITY_GROUP" \
  "$VELOCITY_HOME/forwarding.secret"
sudo chmod 640 "$VELOCITY_HOME/forwarding.secret"
```

Windows 示例应在 Velocity 停止后，以提升权限的 PowerShell 执行，并使用真实服务身份。`/reset` 清除已有显式 ACE，根目录随后停止继承；最终只保留服务账户、SYSTEM (`S-1-5-18`) 和本机管理员组 (`S-1-5-32-544`)：

```powershell
$VelocityHome = 'D:\Minecraft\Velocity'
$ServiceAccount = 'NT SERVICE\Velocity'

function Invoke-Icacls([string[]] $Arguments) {
  & icacls @Arguments | Out-Null
  if ($LASTEXITCODE -ne 0) {
    throw "icacls failed with exit code $LASTEXITCODE"
  }
}

$StarxHome = Join-Path $VelocityHome 'plugins\starx'
$ForwardingSecret = Join-Path $VelocityHome 'forwarding.secret'
Invoke-Icacls -Arguments @($StarxHome, '/reset', '/T', '/C')
Invoke-Icacls -Arguments @($StarxHome, '/inheritance:r')
Invoke-Icacls -Arguments @(
  $StarxHome,
  '/grant:r',
  "$($ServiceAccount):(OI)(CI)M",
  '*S-1-5-18:(OI)(CI)F',
  '*S-1-5-32-544:(OI)(CI)F'
)
Invoke-Icacls -Arguments @($ForwardingSecret, '/reset')
Invoke-Icacls -Arguments @($ForwardingSecret, '/inheritance:r')
Invoke-Icacls -Arguments @(
  $ForwardingSecret,
  '/grant:r',
  "$($ServiceAccount):R",
  '*S-1-5-18:F',
  '*S-1-5-32-544:F'
)
```

ACL 变更后用 `icacls` 复查，任何不在上述允许列表中的显式授权都必须移除。再以服务账户启动并运行环境 doctor。不要用管理员常驻运行来绕过权限错误。

还必须核对服务定义本身，避免“doctor 使用正确 Java，但服务实际使用另一套命令”的假阳性：

| 项目 | Windows | Linux |
|---|---|---|
| 运行身份 | 服务 `ObjectName`/登录账户等于 `-ServiceIdentity` | systemd `User=`/`Group=` 等于 doctor 参数 |
| Java 与 JAR | `ImagePath` 或包装器最终指向 Java 21 和目标 Velocity JAR | `ExecStart=` 指向同一 Java 21 与 Velocity JAR |
| 工作目录 | 服务工作目录为 `VELOCITY_HOME` | `WorkingDirectory=` 为 `VELOCITY_HOME` |
| 文件权限 | 以服务身份实测读取 JAR/config/secret/loader，并写入 logs、core 与 SQLite 父目录 | 同左；root 检查不能替代 `runuser -u "$VELOCITY_USER"` 探针 |

当前 doctor 自动验证 SQLite 父目录的服务身份写权限，但不会解析所有第三方 Windows service wrapper 或面板配置。上述服务定义与非 SQLite 路径证据必须人工记录到发布单。

## SQLite 一致性备份

`data.db`、`data.db-wal` 和 `data.db-shm` 属于同一个 SQLite 状态。升级和回滚必须：

1. 停止 Velocity，并确认进程已经退出。
2. 把存在的 `.db`、`-wal` 和 `-shm` 作为同一批次复制到同一个备份目录。
3. 同时备份 `config.yml`、Uworld core 配置和所有 loader 文件。
4. 记录备份时间、候选 SHA-256、安装 SHA-256 和 Velocity build。

不要在 Velocity 运行时用普通 `Copy-Item` 或 `cp` 单独复制 `data.db`。需要在线备份时必须另行采用 SQLite online backup API；该流程不在本部署基线内。恢复时也必须在服务停止状态下把数据库文件组作为一个整体恢复，不能混用不同备份批次的 WAL/SHM。

最低运维策略：每次部署前保留一份可验证的停服备份；至少保留最近 7 次部署备份和 4 份异机或离线副本；备份目录使用访问控制和静态加密；每季度在隔离环境执行一次完整恢复演练并记录实际 RPO/RTO。本站如果要求更严格的保留期或恢复时间，以站点策略为准。磁盘余量不足、manifest 校验失败或未完成恢复演练时不得继续部署。

## 环境 doctor

从仓库根目录执行。`-ServiceIdentity` 必须填写实际运行 Velocity 的服务账户，不能省略或改成当前管理员/root 账户。Windows 在 doctor 由该账户执行时使用实时写探针；提升权限执行时验证该账户是 SQLite 父目录所有者且拥有 `Modify` 权限。Linux 在同一账户下执行时使用实时写探针；root 执行时通过 `runuser` 以该账户创建、重命名并删除探针，其他身份不匹配会失败。该命令还检查 Java 21、Velocity build 606、唯一 StarX JAR、无外置 LimboAPI、候选/安装哈希、新 Uworld 配置根、目标注册，以及 Paper modern forwarding。`-RequireBackend` 额外执行一秒 TCP 可达性检查。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File scripts/check-uworld-environment.ps1 `
  -VelocityHome 'D:\Minecraft\Velocity' `
  -CandidateJar 'starx-plugins/starx-velocity/build/libs/starx-velocity.jar' `
  -ServiceIdentity 'NT SERVICE\Velocity' `
  -VelocityJar 'D:\Minecraft\Velocity\velocity-3.5.0-SNAPSHOT-606.jar' `
  -JavaExecutable 'C:\Program Files\Zulu\zulu-21\bin\java.exe' `
  -PaperGlobalConfig 'D:\Minecraft\Paper\config\paper-global.yml' `
  -PaperServerProperties 'D:\Minecraft\Paper\server.properties' `
  -RequireBackend
```

成功时最后一行必须为：

```text
UWORLD_ENVIRONMENT=PASS
```

doctor 只输出检查名、状态、路径、哈希、端口和布尔值。SQLite 权限检查只在父目录创建并删除临时探针，不修改数据库。任何失败都必须修复后重跑；不能删除检查或关闭 `-RequireBackend` 来把生产环境变绿。

自动 doctor 与人工验收的边界如下：

| 范围 | 自动检查 | 仍需人工留证 |
|---|---|---|
| 进程与制品 | Java 21、Velocity build 606、唯一 StarX、无外置 LimboAPI、候选/安装 hash | 服务定义实际使用的 Java、JAR、工作目录与启动参数 |
| Uworld 配置 | 主配置根、目标注册、可选 TCP 可达、SQLite 父目录服务身份写探针 | logs/core/loader/secret 的实际服务身份权限，完整业务配置语义由插件冷启动验证 |
| Forwarding | Velocity/Paper modern 开关、两侧 `online-mode` 一致、secret 内存比较、Paper 端口与非通配私网绑定 | 云安全组、NAT、容器/面板映射和公网直连拒绝实测 |
| 运行状态 | 不覆盖启动后的业务 ready | 监听端口、四条 ready 日志、`uworld status`、玩家流程与错误日志 |

候选和安装文件必须逐字节一致：

```powershell
$Candidate = 'starx-plugins\starx-velocity\build\libs\starx-velocity.jar'
$Installed = 'D:\Minecraft\Velocity\plugins\starx-velocity.jar'
$CandidateSha = (Get-FileHash -LiteralPath $Candidate -Algorithm SHA256).Hash
$InstalledSha = (Get-FileHash -LiteralPath $Installed -Algorithm SHA256).Hash
if ($CandidateSha -cne $InstalledSha) {
  throw "Installed StarX JAR does not match the accepted candidate"
}
```

```bash
candidate_sha="$(sha256sum "$RELEASE_JAR" | awk '{print $1}')"
installed_sha="$(sha256sum "$VELOCITY_HOME/plugins/starx-velocity.jar" | awk '{print $1}')"
test "$candidate_sha" = "$installed_sha"
```

## 启动后 readiness 与失败回滚

环境 doctor 在停服部署阶段验证静态环境，不证明插件已成功初始化。Windows 和 Linux 的部署/回滚代码启动服务后，都必须在 60 秒内完成以下检查：

1. 服务仍为 Running/active，Java 进程没有退出或重启循环。
2. Velocity 发布端口由本次启动的进程监听。
3. 日志包含 `Uworld core initialized`、`Uworld runtime ready`、`Generated a 11x11 Uworld authentication platform`、`Authentication Uworld ready`。
4. 日志不包含 StarX 模块初始化回滚、非法 Uworld 配置、loader 失败、数据库失败或 forwarding 拒绝。
5. 控制台 `uworld status` 返回 ready、world 和 session 计数；Diagnostics 世界仍只在 `/uworld test` 时惰性创建。
6. 重新计算安装 SHA-256 并确认仍等于已接受候选，然后保存日志时间范围与候选 SHA。

任一检查超时或失败时，立即阻止玩家流量，保留完整日志和失败配置快照，并执行本文已经验证的回滚块。不能因为 service manager 显示 Running 就继续发布；也不能修改文档或关闭检查来把失败状态改绿。

## 容量与运行基线

Uworld 当前没有可移植到所有服务器的固定并发或内存承诺。生产发布单必须记录站点自己的压测结果；缺少以下指标时，只能标为 `UNVERIFIED`：

| 指标 | 最低要求 |
|---|---|
| CPU/heap | 记录 Java `-Xms/-Xmx`、峰值 heap、GC pause 和 event-loop 延迟；峰值后仍有明确余量 |
| session/world | 记录同时 ACTIVE/TRANSFERRING session、已创建 world 数和超时清理后的归零结果 |
| 文件与 loader | 记录最大 loader 文件、生成耗时、打开文件数和系统 FD/handle 上限 |
| SQLite | `PRAGMA integrity_check` 为 `ok`，记录 schema/migration、pool=2、锁等待和 WAL 峰值 |
| 磁盘 | 候选、日志、数据库/WAL、失败快照和至少一次完整备份后仍有站点定义的告警余量 |
| 网络 | 记录 Velocity 到 Paper 的连接延迟、失败率以及 Paper 拒绝/踢出时的清理结果 |

容量告警阈值必须写入站点监控，而不是藏在本通用文档。任何 session 持续增长、WAL 无界增长、event-loop 阻塞或磁盘低于站点阈值都应停止扩流并触发排障。

## Windows 部署与回滚

先完成 [自动门禁](UWORLD_ACCEPTANCE.md#自动门禁)，再在维护窗口执行。升级命令要求当前安装已经包含 JAR、`config.yml`、Uworld core 和 `data.db`；首次安装应在全新目录中完成，不能伪造一个可回滚备份。

固定备份根为 `VELOCITY_HOME\backups\starx`，服务账户不得写入该目录。每个备份包含只读 `payload/`、逐文件 SHA-256 的 `manifest.json`，以及带 manifest SHA-256 的原子指针。先在同一个提升权限的 PowerShell 会话载入验证器；部署和回滚代码都会调用它：

```powershell
function Get-BackupRelativePath([string] $Root, [string] $Path) {
  $Prefix = $Root.TrimEnd('\') + '\'
  $FullPath = [System.IO.Path]::GetFullPath($Path)
  if (-not $FullPath.StartsWith(
      $Prefix,
      [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Backup entry escaped payload root: $FullPath"
  }
  return $FullPath.Substring($Prefix.Length).Replace('\', '/')
}

function Assert-UworldBackup([string] $BackupRoot, [string] $PointerPath) {
  $Root = [System.IO.Path]::GetFullPath($BackupRoot)
  $RootItem = Get-Item -LiteralPath $Root -Force
  if (($RootItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw 'Backup root must not be a reparse point'
  }

  $Pointer = [System.IO.Path]::GetFullPath($PointerPath)
  if ([System.IO.Path]::GetDirectoryName($Pointer) -cne $Root) {
    throw 'Backup pointer is outside the fixed backup root'
  }
  $PointerItem = Get-Item -LiteralPath $Pointer -Force
  if (($PointerItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw 'Backup pointer must not be a reparse point'
  }

  $PointerText = (Get-Content -LiteralPath $Pointer -Raw).Trim()
  $PointerFields = $PointerText.Split("`t")
  if ($PointerFields.Count -ne 2 -or
      $PointerFields[1] -notmatch '^[0-9A-Fa-f]{64}$') {
    throw 'Backup pointer format is invalid'
  }

  $Backup = [System.IO.Path]::GetFullPath($PointerFields[0])
  if ([System.IO.Path]::GetDirectoryName($Backup) -cne $Root -or
      [System.IO.Path]::GetFileName($Backup) -notmatch '^\d{8}T\d{6}Z$') {
    throw 'Backup pointer does not name a direct release backup child'
  }
  $BackupItem = Get-Item -LiteralPath $Backup -Force
  if (-not $BackupItem.PSIsContainer -or
      ($BackupItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw 'Backup directory is invalid or redirected'
  }

  $ManifestPath = Join-Path $Backup 'manifest.json'
  $ManifestItem = Get-Item -LiteralPath $ManifestPath -Force
  if (($ManifestItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw 'Backup manifest must not be a reparse point'
  }
  $ManifestSha = (Get-FileHash -LiteralPath $ManifestPath -Algorithm SHA256).Hash
  if ($ManifestSha -cne $PointerFields[1]) {
    throw 'Backup manifest hash does not match the trusted pointer'
  }

  $Manifest = Get-Content -LiteralPath $ManifestPath -Raw | ConvertFrom-Json
  if ($Manifest.format -cne 'starx-backup-v1' -or
      $Manifest.previousJar -cne 'plugins/starx-velocity.jar' -or
      $Manifest.config -cne 'starx/config.yml') {
    throw 'Backup manifest identity is invalid'
  }

  $Payload = Join-Path $Backup 'payload'
  $PayloadItem = Get-Item -LiteralPath $Payload -Force
  if (-not $PayloadItem.PSIsContainer -or
      ($PayloadItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw 'Backup payload is invalid or redirected'
  }
  $Redirected = @(Get-ChildItem -LiteralPath $Payload -Force -Recurse | Where-Object {
    ($_.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0
  })
  if ($Redirected.Count -ne 0) { throw 'Backup payload contains a reparse point' }

  $Entries = @($Manifest.files)
  $ManifestPaths = @($Entries | ForEach-Object { [string] $_.path })
  if ($Entries.Count -eq 0 -or
      ($ManifestPaths | Sort-Object -Unique).Count -ne $ManifestPaths.Count) {
    throw 'Backup manifest file list is empty or contains duplicates'
  }

  $ActualFiles = @(Get-ChildItem -LiteralPath $Payload -Force -Recurse -File)
  $ActualPaths = @($ActualFiles | ForEach-Object {
    Get-BackupRelativePath $Payload $_.FullName
  })
  if ((($ManifestPaths | Sort-Object) -join "`n") -cne
      (($ActualPaths | Sort-Object) -join "`n")) {
    throw 'Backup manifest does not cover the payload exactly'
  }

  foreach ($Entry in $Entries) {
    $Relative = [string] $Entry.path
    if ([System.IO.Path]::IsPathRooted($Relative) -or
        $Relative -match '(^|/)\.\.(/|$)') {
      throw "Unsafe backup manifest path: $Relative"
    }
    $Native = $Relative.Replace('/', [System.IO.Path]::DirectorySeparatorChar)
    $FullPath = [System.IO.Path]::GetFullPath((Join-Path $Payload $Native))
    [void] (Get-BackupRelativePath $Payload $FullPath)
    $Item = Get-Item -LiteralPath $FullPath -Force
    if ($Item.Length -ne [long] $Entry.length -or
        (Get-FileHash -LiteralPath $FullPath -Algorithm SHA256).Hash -cne [string] $Entry.sha256) {
      throw "Backup file hash or length mismatch: $Relative"
    }
  }

  $JarEntry = @($Entries | Where-Object { $_.path -ceq $Manifest.previousJar })
  if ($JarEntry.Count -ne 1 -or
      $JarEntry[0].sha256 -cne $Manifest.previousJarSha256 -or
      $ManifestPaths -cnotcontains $Manifest.config) {
    throw 'Backup JAR or StarX config is missing from the manifest'
  }

  $ActualCore = @($ActualPaths | Where-Object {
    $_ -cin @('starx/uworld/core.yml', 'starx/limbo/core.yml')
  } | Sort-Object)
  $ManifestCore = @($Manifest.coreFiles | ForEach-Object { [string] $_ } |
    Sort-Object)
  if ($ActualCore.Count -eq 0 -or
      ($ManifestCore -join "`n") -cne ($ActualCore -join "`n")) {
    throw 'Uworld core file set is incomplete'
  }

  $ActualLoaders = @($ActualPaths | Where-Object {
    [System.IO.Path]::GetExtension($_).ToLowerInvariant() -in
      @('.schem', '.schematic', '.nbt')
  } | Sort-Object)
  $ManifestLoaders = @($Manifest.loaderFiles | ForEach-Object { [string] $_ } |
    Sort-Object)
  if (($ManifestLoaders -join "`n") -cne ($ActualLoaders -join "`n")) {
    throw 'Uworld loader file set is incomplete'
  }

  $ActualSqlite = @(
    @('starx/data.db', 'starx/data.db-wal', 'starx/data.db-shm') |
      Where-Object { $ActualPaths -ccontains $_ }
  )
  $ManifestSqlite = @($Manifest.sqliteFiles | ForEach-Object { [string] $_ })
  if ($ActualSqlite -cnotcontains 'starx/data.db' -or
      (($ManifestSqlite | Sort-Object) -join "`n") -cne
        (($ActualSqlite | Sort-Object) -join "`n")) {
    throw 'SQLite db/wal/shm file group is incomplete'
  }

  return [pscustomobject]@{
    Backup = $Backup
    Payload = $Payload
    Manifest = $Manifest
  }
}
```

部署先用 doctor 的 JAR 内容检查确认当前安装只有一份可识别的 StarX、没有外置 LimboAPI，且固定路径确实对应该 StarX JAR。该检查在停服前完成；随后复制一致的 SQLite 文件组，生成 manifest 并重新逐文件验证。候选安装后必须通过完整环境 doctor，才能重新启动 Velocity：

<!-- UWORLD_WINDOWS_DEPLOYMENT -->
```powershell
$ErrorActionPreference = 'Stop'
$VelocityHome = 'D:\Minecraft\Velocity'
$CandidateJar = (Resolve-Path -LiteralPath `
  'starx-plugins\starx-velocity\build\libs\starx-velocity.jar').Path
$VelocityService = 'Velocity'
$ServiceIdentity = 'NT SERVICE\Velocity'
$Stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
$VelocityHome = [System.IO.Path]::GetFullPath($VelocityHome)
$PluginDir = [System.IO.Path]::GetFullPath((Join-Path $VelocityHome 'plugins'))
$Doctor = (Resolve-Path -LiteralPath 'scripts\check-uworld-environment.ps1').Path
$PowerShell = (Get-Process -Id $PID).Path
$VelocityJar = Join-Path $VelocityHome 'velocity.jar'
$JavaExecutable = (Get-Command java.exe -CommandType Application |
  Select-Object -First 1).Path
$PaperGlobalConfig = 'D:\Minecraft\Paper\config\paper-global.yml'
$PaperServerProperties = 'D:\Minecraft\Paper\server.properties'
$BackupRoot = [System.IO.Path]::GetFullPath(
  (Join-Path $VelocityHome 'backups\starx'))
$Backup = Join-Path $BackupRoot $Stamp
$Pointer = Join-Path $BackupRoot 'last-starx-backup.tsv'
$Payload = Join-Path $Backup 'payload'
$StarxHome = Join-Path $PluginDir 'starx'
$CurrentJar = Join-Path $PluginDir 'starx-velocity.jar'
$ConfigPath = Join-Path $StarxHome 'config.yml'
$DatabasePath = Join-Path $StarxHome 'data.db'

function Invoke-UworldDoctor([string] $Jar, [bool] $RequireBackend) {
  $DoctorArguments = @(
    '-NoProfile',
    '-ExecutionPolicy', 'Bypass',
    '-File', $Doctor,
    '-VelocityHome', $VelocityHome,
    '-CandidateJar', $Jar,
    '-ServiceIdentity', $ServiceIdentity,
    '-VelocityJar', $VelocityJar,
    '-JavaExecutable', $JavaExecutable,
    '-PaperGlobalConfig', $PaperGlobalConfig,
    '-PaperServerProperties', $PaperServerProperties
  )
  if ($RequireBackend) {
    $DoctorArguments += '-RequireBackend'
  }
  $PreviousErrorAction = $ErrorActionPreference
  try {
    $ErrorActionPreference = 'Continue'
    $Output = @(& $PowerShell @DoctorArguments 2>&1)
    $ExitCode = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $PreviousErrorAction
  }
  return [pscustomobject]@{
    ExitCode = $ExitCode
    Lines = @($Output | ForEach-Object { [string] $_ })
  }
}

function Invoke-Icacls([string[]] $Arguments) {
  & icacls @Arguments | Out-Null
  if ($LASTEXITCODE -ne 0) {
    throw "icacls failed with exit code $LASTEXITCODE"
  }
}

function Assert-UworldJarIdentity([string] $Jar) {
  $Probe = Invoke-UworldDoctor $Jar $false
  $Probe.Lines | ForEach-Object { Write-Host $_ }
  if ($Probe.Lines.Count -eq 0 -or
      $Probe.Lines[-1] -notin @('UWORLD_ENVIRONMENT=PASS', 'UWORLD_ENVIRONMENT=FAIL')) {
    throw 'Uworld environment doctor did not finish cleanly'
  }
  $Text = $Probe.Lines -join "`n"
  foreach ($Check in @(
    'plugin_jar_inspection',
    'starx_jar_count',
    'external_limboapi',
    'candidate_hash'
  )) {
    if ($Text -notmatch "(?m)^CHECK name=$Check status=PASS(?: detail=.*)?$") {
      throw "Current plugin inventory failed content check: $Check"
    }
  }
}

function Assert-UworldEnvironment([string] $Jar) {
  $Probe = Invoke-UworldDoctor $Jar $true
  $Probe.Lines | ForEach-Object { Write-Host $_ }
  if ($Probe.ExitCode -ne 0 -or $Probe.Lines.Count -eq 0 -or
      $Probe.Lines[-1] -cne 'UWORLD_ENVIRONMENT=PASS') {
    throw 'Installed Uworld candidate failed the full environment doctor'
  }
}

$PluginPrefix = $PluginDir.TrimEnd('\') + '\'
if ($CandidateJar.StartsWith(
    $PluginPrefix,
    [System.StringComparison]::OrdinalIgnoreCase)) {
  throw 'Candidate JAR must be outside the live plugins directory'
}
foreach ($Required in @($CurrentJar, $ConfigPath, $DatabasePath)) {
  if (-not (Test-Path -LiteralPath $Required -PathType Leaf)) {
    throw "Required rollback source is missing: $Required"
  }
}
$CurrentCore = @(
  Join-Path $StarxHome 'uworld\core.yml'
  Join-Path $StarxHome 'limbo\core.yml'
) | Where-Object {
    Test-Path -LiteralPath $_ -PathType Leaf
  }
if ($CurrentCore.Count -eq 0) { throw 'No Uworld core file is available to back up' }

Assert-UworldJarIdentity $CurrentJar

New-Item -ItemType Directory -Path $BackupRoot -Force | Out-Null
$BackupRootItem = Get-Item -LiteralPath $BackupRoot -Force
if (($BackupRootItem.Attributes -band
    [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
  throw 'Fixed backup root must not be a reparse point'
}
Invoke-Icacls -Arguments @($BackupRoot, '/reset', '/T', '/C')
Invoke-Icacls -Arguments @($BackupRoot, '/inheritance:r')
Invoke-Icacls -Arguments @(
  $BackupRoot,
  '/grant:r',
  '*S-1-5-18:(OI)(CI)F',
  '*S-1-5-32-544:(OI)(CI)F'
)
if (Test-Path -LiteralPath $Backup) { throw "Backup already exists: $Backup" }
New-Item -ItemType Directory -Path (Join-Path $Payload 'plugins') -Force |
  Out-Null

Stop-Service -Name $VelocityService `
  -ErrorAction Stop -WhatIf:$false -Confirm:$false
(Get-Service -Name $VelocityService).WaitForStatus('Stopped', '00:01:00')

Copy-Item -LiteralPath $StarxHome -Destination (Join-Path $Payload 'starx') `
  -Recurse
Copy-Item -LiteralPath $CurrentJar `
  -Destination (Join-Path $Payload 'plugins\starx-velocity.jar')

$PayloadFiles = @(Get-ChildItem -LiteralPath $Payload -Force -Recurse -File)
$Entries = @($PayloadFiles | ForEach-Object {
  [ordered]@{
    path = Get-BackupRelativePath $Payload $_.FullName
    length = $_.Length
    sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
  }
})
$Paths = @($Entries | ForEach-Object { $_.path })
$CoreFiles = @($Paths | Where-Object {
  $_ -cin @('starx/uworld/core.yml', 'starx/limbo/core.yml')
} | Sort-Object)
$LoaderFiles = @($Paths | Where-Object {
  [System.IO.Path]::GetExtension($_).ToLowerInvariant() -in
    @('.schem', '.schematic', '.nbt')
} | Sort-Object)
$SqliteFiles = @(
  @('starx/data.db', 'starx/data.db-wal', 'starx/data.db-shm') |
    Where-Object { $Paths -ccontains $_ }
)
$UsesFileLoader = Select-String -LiteralPath (Join-Path $Payload 'starx\config.yml') `
  -Pattern '(?i)^\s*(?:world-)?loader-type:\s*.*(?:SCHEMATIC|WORLDEDIT_SCHEM|STRUCTURE)' `
  -Quiet
if ($UsesFileLoader -and $LoaderFiles.Count -eq 0) {
  throw 'Configured file loader has no backed-up loader file'
}
$PreviousJarEntry = @($Entries | Where-Object {
  $_.path -ceq 'plugins/starx-velocity.jar'
})
if ($PreviousJarEntry.Count -ne 1 -or $CoreFiles.Count -eq 0 -or
    $SqliteFiles -cnotcontains 'starx/data.db') {
  throw 'Backup prerequisites are incomplete'
}

$Manifest = [ordered]@{
  format = 'starx-backup-v1'
  createdUtc = (Get-Date).ToUniversalTime().ToString('o')
  candidateSha256 = (Get-FileHash -LiteralPath $CandidateJar -Algorithm SHA256).Hash
  previousJar = 'plugins/starx-velocity.jar'
  previousJarSha256 = $PreviousJarEntry[0].sha256
  config = 'starx/config.yml'
  coreFiles = $CoreFiles
  loaderFiles = $LoaderFiles
  sqliteFiles = $SqliteFiles
  files = $Entries
}
$ManifestPath = Join-Path $Backup 'manifest.json'
$ManifestJson = $Manifest | ConvertTo-Json -Depth 6
[System.IO.File]::WriteAllText(
  $ManifestPath,
  $ManifestJson,
  [System.Text.UTF8Encoding]::new($false))
$ManifestSha = (Get-FileHash -LiteralPath $ManifestPath -Algorithm SHA256).Hash
$PointerTemp = "$Pointer.tmp-$PID"
[System.IO.File]::WriteAllText(
  $PointerTemp,
  "$Backup`t$ManifestSha`r`n",
  [System.Text.UTF8Encoding]::new($false))
Move-Item -LiteralPath $PointerTemp -Destination $Pointer -Force

$Verified = Assert-UworldBackup $BackupRoot $Pointer
if ($Verified.Backup -cne $Backup) { throw 'Verified backup identity changed' }

$Quarantine = Join-Path $Backup 'quarantine'
New-Item -ItemType Directory -Path $Quarantine | Out-Null
Move-Item -LiteralPath $CurrentJar `
  -Destination (Join-Path $Quarantine 'starx-velocity.jar')
Copy-Item -LiteralPath $CandidateJar `
  -Destination (Join-Path $PluginDir 'starx-velocity.jar') `
  -ErrorAction Stop -WhatIf:$false -Confirm:$false

$CandidateSha = (Get-FileHash -LiteralPath $CandidateJar -Algorithm SHA256).Hash
$InstalledSha = (Get-FileHash -LiteralPath `
  (Join-Path $PluginDir 'starx-velocity.jar') `
  -Algorithm SHA256).Hash
if ($CandidateSha -cne $InstalledSha) { throw 'Installed JAR hash mismatch' }

Assert-UworldEnvironment $CandidateJar
Start-Service -Name $VelocityService `
  -ErrorAction Stop -WhatIf:$false -Confirm:$false
(Get-Service -Name $VelocityService).WaitForStatus('Running', '00:01:00')
```
<!-- /UWORLD_WINDOWS_DEPLOYMENT -->

部署脚本不会根据文件名猜测插件身份。若存在改名后的第二份 StarX、外置 LimboAPI 或不可读 JAR，停服前的内容检查会直接中止；先人工核对并清理插件目录，再重新运行 doctor。备份验证失败时当前安装仍在原位；不要继续替换，必要时直接重新启动原服务。

回滚必须先调用验证器。路径越界、reparse point、指针或 manifest 被改写、文件缺失、哈希不符、loader/core 清单变化，或 SQLite 文件组不一致时，命令会在创建失败快照、停止服务或移动现有文件之前退出：

```powershell
$ErrorActionPreference = 'Stop'
$VelocityHome = 'D:\Minecraft\Velocity'
$VelocityService = 'Velocity'
$VelocityHome = [System.IO.Path]::GetFullPath($VelocityHome)
$PluginDir = [System.IO.Path]::GetFullPath((Join-Path $VelocityHome 'plugins'))
$BackupRoot = [System.IO.Path]::GetFullPath(
  (Join-Path $VelocityHome 'backups\starx'))
$Pointer = Join-Path $BackupRoot 'last-starx-backup.tsv'

# This is the fail-fast boundary. Nothing above or inside validation stops services.
$Verified = Assert-UworldBackup $BackupRoot $Pointer
$Backup = $Verified.Backup
$Payload = $Verified.Payload
$Manifest = $Verified.Manifest
$Failure = Join-Path $BackupRoot (
  'failed-' + (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ'))

if (Test-Path -LiteralPath $Failure) { throw "Failure path exists: $Failure" }
New-Item -ItemType Directory -Path $Failure | Out-Null
Stop-Service -Name $VelocityService `
  -ErrorAction Stop -WhatIf:$false -Confirm:$false
(Get-Service -Name $VelocityService).WaitForStatus('Stopped', '00:01:00')

$InstalledJar = Join-Path $PluginDir 'starx-velocity.jar'
if (Test-Path -LiteralPath $InstalledJar) {
  Move-Item -LiteralPath $InstalledJar -Destination $Failure
}
$StarxHome = Join-Path $PluginDir 'starx'
if (Test-Path -LiteralPath $StarxHome) {
  Move-Item -LiteralPath $StarxHome -Destination $Failure
}

Copy-Item -LiteralPath (Join-Path $Payload 'plugins\starx-velocity.jar') `
  -Destination $InstalledJar `
  -ErrorAction Stop -WhatIf:$false -Confirm:$false
Copy-Item -LiteralPath (Join-Path $Payload 'starx') `
  -Destination $StarxHome -Recurse `
  -ErrorAction Stop -WhatIf:$false -Confirm:$false

foreach ($Entry in @($Manifest.files | Where-Object {
  $_.path -ceq 'plugins/starx-velocity.jar' -or
  $_.path.StartsWith('starx/')
})) {
  $Destination = if ($Entry.path -ceq 'plugins/starx-velocity.jar') {
    $InstalledJar
  } else {
    Join-Path $PluginDir ($Entry.path.Replace(
      '/', [System.IO.Path]::DirectorySeparatorChar))
  }
  if ((Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash -cne $Entry.sha256) {
    throw "Restored file hash mismatch: $($Entry.path)"
  }
}

Start-Service -Name $VelocityService `
  -ErrorAction Stop -WhatIf:$false -Confirm:$false
(Get-Service -Name $VelocityService).WaitForStatus('Running', '00:01:00')
```

只在上一版发布清单明确要求外置 LimboAPI 时才随回滚恢复它；Uworld 版本始终要求外置 LimboAPI 为零。

## Linux 部署与回滚

以下代码在 root shell 中执行；先运行 `sudo -i`，再载入验证函数。固定根为 `$VELOCITY_HOME/backups/starx`，mode 必须为 `0700`。验证器先 canonicalize 指针和备份目录，要求备份是固定根的直接子目录，再验证 pointer -> manifest/SHA256SUMS -> payload 的完整哈希链：

```bash
verify_uworld_backup() {
  local root_input=$1 pointer_input=$2 root pointer
  local raw_backup expected_manifest_sha expected_sums_sha extra
  local backup manifest sums payload actual_sums

  [ -d "$root_input" ] && [ ! -L "$root_input" ] || {
    echo 'Invalid fixed backup root' >&2
    return 1
  }
  root="$(realpath -e -- "$root_input")"
  [ -f "$pointer_input" ] && [ ! -L "$pointer_input" ] || {
    echo 'Invalid backup pointer' >&2
    return 1
  }
  pointer="$(realpath -e -- "$pointer_input")"
  [ "$(dirname -- "$pointer")" = "$root" ] || {
    echo 'Backup pointer escaped fixed root' >&2
    return 1
  }
  [ "$(wc -l < "$pointer")" -eq 1 ] || {
    echo 'Backup pointer must contain one record' >&2
    return 1
  }
  IFS=$'\t' read -r raw_backup expected_manifest_sha expected_sums_sha extra \
    < "$pointer"
  [ -n "$raw_backup" ] && [ -z "${extra:-}" ] &&
    [[ "$expected_manifest_sha" =~ ^[0-9a-f]{64}$ ]] &&
    [[ "$expected_sums_sha" =~ ^[0-9a-f]{64}$ ]] || {
      echo 'Backup pointer fields are invalid' >&2
      return 1
    }

  [ -d "$raw_backup" ] && [ ! -L "$raw_backup" ] || {
    echo 'Backup directory is missing or redirected' >&2
    return 1
  }
  backup="$(realpath -e -- "$raw_backup")"
  [ "$(dirname -- "$backup")" = "$root" ] &&
    [[ "$(basename -- "$backup")" =~ ^[0-9]{8}T[0-9]{6}Z$ ]] || {
      echo 'Backup is not a direct release child of fixed root' >&2
      return 1
    }

  manifest="$backup/manifest.meta"
  sums="$backup/SHA256SUMS"
  payload="$backup/payload"
  [ -f "$manifest" ] && [ ! -L "$manifest" ] &&
    [ -f "$sums" ] && [ ! -L "$sums" ] &&
    [ -d "$payload" ] && [ ! -L "$payload" ] || {
      echo 'Backup manifest or payload is incomplete' >&2
      return 1
    }
  [ "$(sha256sum "$manifest" | awk '{print $1}')" = \
      "$expected_manifest_sha" ] &&
    [ "$(sha256sum "$sums" | awk '{print $1}')" = \
      "$expected_sums_sha" ] || {
      echo 'Backup manifest hash chain is invalid' >&2
      return 1
    }
  if find "$payload" -type l -print -quit | grep -q .; then
    echo 'Backup payload contains a symlink' >&2
    return 1
  fi

  meta_value() {
    awk -F= -v key="$1" '
      $1 == key { count++; value = substr($0, length(key) + 2) }
      END { if (count != 1) exit 2; print value }
    ' "$manifest"
  }
  local format created_utc candidate_sha previous_jar previous_sha
  local config core_count loader_count
  local wal_present shm_present actual_core actual_loader actual_wal actual_shm
  format="$(meta_value format)" || return 1
  created_utc="$(meta_value created_utc)" || return 1
  candidate_sha="$(meta_value candidate_sha256)" || return 1
  previous_jar="$(meta_value previous_jar)" || return 1
  previous_sha="$(meta_value previous_jar_sha256)" || return 1
  config="$(meta_value config)" || return 1
  core_count="$(meta_value core_count)" || return 1
  loader_count="$(meta_value loader_count)" || return 1
  wal_present="$(meta_value sqlite_wal_present)" || return 1
  shm_present="$(meta_value sqlite_shm_present)" || return 1
  [ "$format" = starx-backup-v1 ] &&
    [[ "$created_utc" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T ]] &&
    [[ "$candidate_sha" =~ ^[0-9a-f]{64}$ ]] &&
    [ "$previous_jar" = plugins/starx-velocity.jar ] &&
    [ "$config" = starx/config.yml ] &&
    [[ "$previous_sha" =~ ^[0-9a-f]{64}$ ]] &&
    [[ "$core_count" =~ ^[1-9][0-9]*$ ]] &&
    [[ "$loader_count" =~ ^[0-9]+$ ]] &&
    [[ "$wal_present" =~ ^[01]$ ]] &&
    [[ "$shm_present" =~ ^[01]$ ]] || {
      echo 'Backup metadata is invalid' >&2
      return 1
    }

  [ -f "$payload/$previous_jar" ] &&
    [ -f "$payload/$config" ] &&
    [ -f "$payload/starx/data.db" ] || {
      echo 'Backup JAR, config, or SQLite database is missing' >&2
      return 1
    }
  [ "$(sha256sum "$payload/$previous_jar" | awk '{print $1}')" = \
      "$previous_sha" ] || {
      echo 'Backup JAR hash does not match metadata' >&2
      return 1
    }

  actual_core="$(find "$payload/starx" -type f \
    \( -path '*/uworld/core.yml' -o -path '*/limbo/core.yml' \) |
    wc -l)"
  actual_loader="$(find "$payload/starx" -type f \
    \( -iname '*.schem' -o -iname '*.schematic' -o -iname '*.nbt' \) |
    wc -l)"
  [ "$actual_core" -eq "$core_count" ] &&
    [ "$actual_loader" -eq "$loader_count" ] || {
      echo 'Uworld core or loader file set is incomplete' >&2
      return 1
    }
  actual_wal=0; [ -f "$payload/starx/data.db-wal" ] && actual_wal=1
  actual_shm=0; [ -f "$payload/starx/data.db-shm" ] && actual_shm=1
  [ "$actual_wal" -eq "$wal_present" ] &&
    [ "$actual_shm" -eq "$shm_present" ] || {
      echo 'SQLite db/wal/shm file group changed' >&2
      return 1
    }

  actual_sums="$(mktemp)" || return 1
  if ! (
    cd "$payload"
    find . -type f -print0 | LC_ALL=C sort -z |
      xargs -0 -r sha256sum --
  ) > "$actual_sums"; then
    rm -f -- "$actual_sums"
    return 1
  fi
  if ! cmp -s -- "$sums" "$actual_sums"; then
    rm -f -- "$actual_sums"
    echo 'Backup file list or payload hash is incomplete' >&2
    return 1
  fi
  rm -f -- "$actual_sums"

  VERIFIED_BACKUP=$backup
  VERIFIED_PAYLOAD=$payload
  VERIFIED_PREVIOUS_SHA=$previous_sha
}
```

部署时先通过 doctor 的内容身份检查，再验证当前可回滚输入。停止 Velocity 后复制完整 payload，生成 `manifest.meta` 和覆盖全部 payload 的 `SHA256SUMS`；只有 `verify_uworld_backup` 重新计算出的清单完全一致后才移动当前安装。候选安装后必须通过完整环境 doctor 才能启动：

<!-- UWORLD_LINUX_DEPLOYMENT -->
```bash
set -euo pipefail
test "$(id -u)" -eq 0
export VELOCITY_HOME=/srv/velocity
export RELEASE_JAR=/srv/releases/starx-velocity.jar
export VELOCITY_SERVICE=velocity
export VELOCITY_USER=velocity
export VELOCITY_GROUP=velocity
export VELOCITY_JAR=/srv/velocity/velocity.jar
export JAVA_EXECUTABLE=/usr/bin/java
export PAPER_GLOBAL_CONFIG=/srv/paper/config/paper-global.yml
export PAPER_SERVER_PROPERTIES=/srv/paper/server.properties
export UWORLD_DOCTOR=/srv/starx-release/scripts/check-uworld-environment.ps1

command -v pwsh >/dev/null
VELOCITY_HOME="$(realpath -e -- "$VELOCITY_HOME")"
RELEASE_JAR="$(realpath -e -- "$RELEASE_JAR")"
plugin_dir="$VELOCITY_HOME/plugins"
starx_home="$plugin_dir/starx"
current_jar="$plugin_dir/starx-velocity.jar"
backup_root="$VELOCITY_HOME/backups/starx"
pointer="$backup_root/last-starx-backup.tsv"

run_uworld_doctor() {
  local candidate=$1 require_backend=$2 output code
  local args=(
    -NoProfile
    -File "$UWORLD_DOCTOR"
    -VelocityHome "$VELOCITY_HOME"
    -CandidateJar "$candidate"
    -ServiceIdentity "$VELOCITY_USER"
    -VelocityJar "$VELOCITY_JAR"
    -JavaExecutable "$JAVA_EXECUTABLE"
    -PaperGlobalConfig "$PAPER_GLOBAL_CONFIG"
    -PaperServerProperties "$PAPER_SERVER_PROPERTIES"
  )
  if [ "$require_backend" -eq 1 ]; then
    args+=(-RequireBackend)
  fi
  if output="$(pwsh "${args[@]}" 2>&1)"; then
    code=0
  else
    code=$?
  fi
  UWORLD_DOCTOR_OUTPUT=$output
  UWORLD_DOCTOR_CODE=$code
}

assert_uworld_jar_identity() {
  local candidate=$1 check last
  run_uworld_doctor "$candidate" 0
  printf '%s\n' "$UWORLD_DOCTOR_OUTPUT"
  last="$(printf '%s\n' "$UWORLD_DOCTOR_OUTPUT" | tail -n 1)"
  case "$last" in
    UWORLD_ENVIRONMENT=PASS|UWORLD_ENVIRONMENT=FAIL) ;;
    *) echo 'Uworld environment doctor did not finish cleanly' >&2; return 1 ;;
  esac
  for check in plugin_jar_inspection starx_jar_count external_limboapi candidate_hash; do
    printf '%s\n' "$UWORLD_DOCTOR_OUTPUT" |
      grep -Eq "^CHECK name=$check status=PASS( detail=.*)?$" || {
        echo "Current plugin inventory failed content check: $check" >&2
        return 1
      }
  done
}

assert_uworld_environment() {
  local candidate=$1 last
  run_uworld_doctor "$candidate" 1
  printf '%s\n' "$UWORLD_DOCTOR_OUTPUT"
  last="$(printf '%s\n' "$UWORLD_DOCTOR_OUTPUT" | tail -n 1)"
  [ "$UWORLD_DOCTOR_CODE" -eq 0 ] &&
    [ "$last" = UWORLD_ENVIRONMENT=PASS ] || {
      echo 'Installed Uworld candidate failed the full environment doctor' >&2
      return 1
    }
}

case "$RELEASE_JAR" in "$plugin_dir"/*)
  echo 'Candidate JAR must be outside live plugins' >&2; exit 1 ;;
esac
test -f "$current_jar"
test -f "$starx_home/config.yml"
test -f "$starx_home/data.db"
test -n "$(find "$starx_home" -type f \
  \( -path '*/uworld/core.yml' -o -path '*/limbo/core.yml' \) \
  -print -quit)"

assert_uworld_jar_identity "$current_jar"

install -d -m 0700 -o root -g root "$backup_root"
test ! -L "$backup_root"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
backup="$backup_root/$stamp"
payload="$backup/payload"
test ! -e "$backup"
install -d -m 0700 "$payload/plugins"

systemctl stop "$VELOCITY_SERVICE"
test "$(systemctl is-active "$VELOCITY_SERVICE" || true)" != active

cp -a "$starx_home" "$payload/starx"
cp -a "$current_jar" "$payload/plugins/starx-velocity.jar"

core_count="$(find "$payload/starx" -type f \
  \( -path '*/uworld/core.yml' -o -path '*/limbo/core.yml' \) |
  wc -l)"
loader_count="$(find "$payload/starx" -type f \
  \( -iname '*.schem' -o -iname '*.schematic' -o -iname '*.nbt' \) |
  wc -l)"
if grep -Eiq '^[[:space:]]*(world-)?loader-type:[[:space:]]*.*(SCHEMATIC|WORLDEDIT_SCHEM|STRUCTURE)' \
    "$payload/starx/config.yml" && [ "$loader_count" -eq 0 ]; then
  echo 'Configured file loader has no backed-up loader file' >&2
  exit 1
fi
wal_present=0; [ -f "$payload/starx/data.db-wal" ] && wal_present=1
shm_present=0; [ -f "$payload/starx/data.db-shm" ] && shm_present=1
previous_sha="$(sha256sum "$payload/plugins/starx-velocity.jar" |
  awk '{print $1}')"
candidate_sha="$(sha256sum "$RELEASE_JAR" | awk '{print $1}')"

sums="$backup/SHA256SUMS"
(
  cd "$payload"
  find . -type f -print0 | LC_ALL=C sort -z |
    xargs -0 -r sha256sum --
) > "$sums"
manifest="$backup/manifest.meta"
{
  printf 'format=starx-backup-v1\n'
  printf 'created_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf 'candidate_sha256=%s\n' "$candidate_sha"
  printf 'previous_jar=plugins/starx-velocity.jar\n'
  printf 'previous_jar_sha256=%s\n' "$previous_sha"
  printf 'config=starx/config.yml\n'
  printf 'core_count=%s\n' "$core_count"
  printf 'loader_count=%s\n' "$loader_count"
  printf 'sqlite_wal_present=%s\n' "$wal_present"
  printf 'sqlite_shm_present=%s\n' "$shm_present"
} > "$manifest"
chmod 0600 "$manifest" "$sums"

manifest_sha="$(sha256sum "$manifest" | awk '{print $1}')"
sums_sha="$(sha256sum "$sums" | awk '{print $1}')"
pointer_temp="$pointer.tmp-$$"
printf '%s\t%s\t%s\n' "$backup" "$manifest_sha" "$sums_sha" \
  > "$pointer_temp"
chmod 0600 "$pointer_temp"
mv -f -- "$pointer_temp" "$pointer"

verify_uworld_backup "$backup_root" "$pointer"
[ "$VERIFIED_BACKUP" = "$backup" ]

quarantine="$backup/quarantine"
install -d -m 0700 "$quarantine"
mv -- "$current_jar" "$quarantine/starx-velocity.jar"
install -o "$VELOCITY_USER" -g "$VELOCITY_GROUP" -m 0640 \
  "$RELEASE_JAR" "$VELOCITY_HOME/plugins/starx-velocity.jar"

installed_sha="$(sha256sum "$VELOCITY_HOME/plugins/starx-velocity.jar" | awk '{print $1}')"
test "$candidate_sha" = "$installed_sha"
assert_uworld_environment "$RELEASE_JAR"
systemctl start "$VELOCITY_SERVICE"
systemctl is-active --quiet "$VELOCITY_SERVICE"
```
<!-- /UWORLD_LINUX_DEPLOYMENT -->

回滚首先完整验证备份并设置已验证的 `VERIFIED_*` 变量。只有该调用成功后才创建失败快照并停止服务：

<!-- UWORLD_LINUX_ROLLBACK -->
```bash
set -euo pipefail
test "$(id -u)" -eq 0
export VELOCITY_HOME=/srv/velocity
export VELOCITY_SERVICE=velocity
export VELOCITY_USER=velocity
export VELOCITY_GROUP=velocity

VELOCITY_HOME="$(realpath -e -- "$VELOCITY_HOME")"
plugin_dir="$VELOCITY_HOME/plugins"
backup_root="$VELOCITY_HOME/backups/starx"
pointer="$backup_root/last-starx-backup.tsv"

# This is the fail-fast boundary. Validation runs before mkdir or systemctl stop.
verify_uworld_backup "$backup_root" "$pointer"
backup="$VERIFIED_BACKUP"
payload="$VERIFIED_PAYLOAD"
failure="$backup_root/failed-$(date -u +%Y%m%dT%H%M%SZ)"
test ! -e "$failure"
install -d -m 0700 "$failure"
systemctl stop "$VELOCITY_SERVICE"

if [ -f "$VELOCITY_HOME/plugins/starx-velocity.jar" ]; then
  mv "$VELOCITY_HOME/plugins/starx-velocity.jar" "$failure/"
fi
if [ -d "$VELOCITY_HOME/plugins/starx" ]; then
  mv "$VELOCITY_HOME/plugins/starx" "$failure/starx"
fi
cp -a "$payload/plugins/starx-velocity.jar" \
  "$plugin_dir/starx-velocity.jar"
cp -a "$payload/starx" "$plugin_dir/starx"

restored_sha="$(sha256sum "$plugin_dir/starx-velocity.jar" | awk '{print $1}')"
test "$restored_sha" = "$VERIFIED_PREVIOUS_SHA"
diff -qr -- "$payload/starx" "$plugin_dir/starx" >/dev/null
chown -R "$VELOCITY_USER:$VELOCITY_GROUP" "$plugin_dir/starx"

systemctl start "$VELOCITY_SERVICE"
systemctl is-active --quiet "$VELOCITY_SERVICE"
```
<!-- /UWORLD_LINUX_ROLLBACK -->

无效、越界或不完整的指针/manifest 永远不能通过手工修改检查结果来继续回滚。部署或回滚后重新应用服务账户权限，并重新执行环境 doctor。不要在正在运行的 JVM 中替换 JAR、数据库或 Uworld core 文件。

## 故障排查

先保留候选 SHA、安装 SHA、服务定义、doctor 全量输出、代理日志时间范围和相关配置快照；不得记录 forwarding secret、密码或数据库凭据。只有原因消除后才能重跑，原失败证据继续保留。

| 症状或失败 check | 常见原因 | 处理与回滚阈值 |
|---|---|---|
| `java_21` / `velocity_build_606` | 服务与交互 shell 使用不同 Java/JAR | 修正服务定义并完整重启；不能在错误 runtime 上继续 |
| `starx_jar_count` / `external_limboapi` | 重复 StarX、改名副本或外置 LimboAPI | 停服后移入隔离备份，重新做内容身份检查 |
| `candidate_hash` | live 未部署当前候选或复制后被改写 | 不开放玩家；重新安装或回滚到 manifest 验证通过的版本 |
| `starx_config_syntax` / `uworld_config` | YAML 类型、Auth/Uworld 开关组合、范围或旧根错误 | 修正完整 key；Auth 无 ready Uworld 时必须保持 fail closed |
| core/loader 初始化失败 | 文件缺失、扩展名不匹配、路径逃逸、权限或格式错误 | 不允许回退 VOID；恢复同批次 core/loader 或回滚 |
| forwarding / online-mode / binding 失败 | 两侧开关不一致、secret 为空/不匹配、Paper 通配监听或端口不一致 | 阻止公网流量；修正后重跑 doctor 和直连拒绝实测 |
| `backend_reachable` | target 未启动、地址/端口错误或防火墙拒绝 | 区分未注册与已注册离线；不得改用任意首服 fallback |
| SQLite lock/permission/integrity | 混合所有权、并发写者、磁盘满、WAL 批次损坏 | 停止 Velocity，保留文件组；从已验证同批次备份恢复并演练 |
| 协议不兼容 | Velocity 最大协议能力低于 776 或客户端版本未验 | 升级到受支持 runtime；客户端保持 `UNVERIFIED` 直到矩阵留证 |
| service Running 但无 ready 日志 | 插件初始化回滚、数据库或 loader 错误 | 60 秒 readiness 失败即停止流量并执行已验证回滚 |
| session/world 计数不归零 | timeout、disconnect、shutdown 或目标终态清理异常 | 保存线程/日志证据，停止扩流；不能仅重启掩盖泄漏 |

## 发布阶段和可声明状态

| 阶段 | 最低证据 | 可以声明 |
|---|---|---|
| 候选 | 完整 Gradle 门禁、静态门禁、候选 SHA | 候选构建通过 |
| 已安装 | 安装 SHA 与候选 SHA 相等；唯一 StarX JAR；无外置 LimboAPI | 精确候选已安装 |
| 冷启动 | build 606 临时环境的 Default/Diagnostics smoke | 候选可在受控环境启动 |
| 环境就绪 | 生产目标注册、TCP 可达、modern forwarding、权限和 hash doctor 全部通过 | 生产环境满足前置条件 |
| 功能完成 | 与同一 SHA 绑定的 25 项真实客户端记录全部 `PASS` | 该候选已部署且 Uworld 功能完成 |

任一较低阶段不能替代较高阶段。尤其不能用单元测试、文档完成、冷启动或 doctor 结果把未执行的玩家流程改成 `PASS`。

## 当前工作区状态

`velocity-test` 是受保护的运行中测试环境，不在本文档修改范围内。2026-07-16 使用当前候选 `5D31352D...3C96C9` 和 `-RequireBackend` 实测返回 `UWORLD_ENVIRONMENT=FAIL`：已安装 JAR 仍为 `D68EE357...19B2A`；配置只有旧 `starx.limbo` 根与数组式 server；目标未注册且不可达；Paper forwarding 关闭且 secret 为空；Velocity `online-mode` 未显式配置，Paper forwarding 侧为 true；`server.properties` 仍为 `online-mode=true`、端口 25565、未配置明确私网绑定；`NT SERVICE\Velocity` 在本机无法解析。Java 21、build 606、唯一 StarX、无外置 LimboAPI、Velocity modern 模式和配置语法检查通过。完整输出保存在 `tmp/uworld-final-evidence/live-doctor-5D31352D.log`。

这些失败证明候选尚未部署，也证明 live 环境不满足 production forwarding、目标、服务身份和权限前置条件。维护窗口前不得修改 PID `82260`、复制候选到 live `plugins/` 或伪造 doctor 结果；失败证据必须保留，不能通过删除检查、关闭 `-RequireBackend` 或改写文档来消除。

当前真实客户端矩阵中 D02、D04、D05 已由隔离的 1.21.11 客户端流程留证；其余 22 项仍为 `UNVERIFIED`。因此可以陈述 diagnostics 世界进入、chat/move callback、2.895 格实际位移和返回保存的 lobby 已通过，但不能宣称当前候选已部署，也不能宣称平台目视、独立注册、密码、TOTP、恢复码、双连接、超时、错误目标或 shutdown 场景已经由真实玩家验证。

## 相关文档

- [插件产品边界](../starx-plugins/README.md)
- [StarX Velocity 构建](../starx-plugins/starx-velocity/README.md)
- [Uworld 配置](UWORLD_CONFIGURATION.md)
- [Uworld 验收](UWORLD_ACCEPTANCE.md)
- [Uworld 公共 API](UWORLD_DEVELOPMENT.md)
