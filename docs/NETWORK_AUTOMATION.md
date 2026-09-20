# StarX 网络自动化与免费证书

StarX Velocity 的 `network-automation` 提供类似服务器面板的网络诊断、FRP 配置辅助和免费证书申请。默认行为是**只检测、只生成报告**；除非管理员显式开启对应开关，否则不会重载 FRP，也不会调用 Certbot。

运行报告默认写入：

```text
Velocity/plugins/starx/network-automation.json
```

首次任务在 HTTP API 启动后异步执行，之后每 30 分钟刷新。报告和 StarX 管理指标中的 `networkAutomation` 使用同一份运行快照。

## 安全边界

网络自动化遵守以下规则：

1. 不扫描公网或 FRP 端口。
2. 不把“本机地址看起来像公网”直接判定为可从互联网访问。
3. 至少两个独立 HTTPS 服务返回相同地址，才确认出口公网地址。
4. 只有外部地址与本机网卡上的全局可路由地址一致，才标记 `directAddressConfirmed=true`。
5. `directAddressConfirmed` 只证明地址拓扑，不证明安全组、路由器、运营商或防火墙允许入站连接。
6. FRP 托管模式固定生成 `remotePort = 0`，由 frps 原子分配端口。
7. 只有 `frpc status` 在指定代理的状态行中返回有效端口，StarX 才公布 FRP URL。
8. HTTP-01 的公网 TCP 80 路由未经管理员确认时，不执行 Certbot。
9. 外部程序通过参数数组启动，不经过 Shell。
10. 报告采用临时文件加原子替换写入；命令诊断会脱敏 token、secret、password 和 Authorization。

因此报告中的：

```json
{
  "directAddressConfirmed": true,
  "inboundPortConfirmed": false
}
```

不是矛盾。前者表示外部观察到的地址与本机地址一致；后者表示尚未完成独立的互联网回连验证。

## 运行时端口避让与稳定租约

StarX 不会在已占用端口上重复启动监听器。HTTP 端口行为由 `http.port-conflict-policy` 控制：

| 策略 | 行为 |
|---|---|
| `strict` | 只允许配置端口；已占用则停止启动 |
| `fallback` | 配置端口冲突后，仅在指定范围内选择空闲端口 |
| `persist` | 优先配置端口；仍冲突时优先复用上次租约端口，再检查指定范围 |
| `ephemeral` | 与 `persist` 相同，但指定范围耗尽后允许操作系统分配临时端口 |

默认配置：

```yaml
http:
  bind: "127.0.0.1"
  port: 8788
  port-conflict-policy: "persist"
  fallback-range-start: 8788
  fallback-range-end: 8888
```

候选范围最多包含 4096 个端口，避免在整个端口空间中无约束漂移。`fallback` 和 `persist` 在范围耗尽时明确失败；只有管理员显式选择 `ephemeral` 时才允许越界使用系统临时端口。最终仍由 HTTP 服务执行真实原子绑定；如果探测与绑定之间出现竞态，StarX 会重新选择，不会覆盖已有监听器。

### 运行时文件

Velocity 数据目录中会维护：

```text
runtime-endpoint.json      当前活动 HTTP 端点，不含 API key
runtime-endpoint.lock      跨进程活动锁
runtime-port-lease.json    上一次成功端口租约，不含凭据
```

规则：

- 同一数据目录只能有一个 Velocity 实例持有活动锁；第二个实例会拒绝启动。
- 正常关闭时删除 `runtime-endpoint.json`，保留端口租约。
- 崩溃遗留的 JSON 因锁已释放，不会被 Paper/Folia 当作活动地址。
- 管理员修改 `http.port` 后，旧租约因配置端口不匹配而自动失效。
- Paper/Folia 只在进程仍存活且活动锁仍被持有时采用运行时端口；否则退回 `config.yml`。
- 如果 Paper/Folia 先启动、Velocity 后启动，heartbeat 首次失败后会重新发现活动端点并切换客户端，同时更新账号接口客户端。

### 组件协调

- HTTP API 使用最终成功绑定的端口。
- `frp.local-port` 等于原 HTTP 端口时，自动跟随最终 HTTP 端口。
- 管理员显式配置为其他服务的 FRP 本地端口保持不变。
- HTTP-01 端口会避开 HTTP API 和当前已占用端口。
- HTTP-01 端口变化时，`http01-public-route-confirmed` 在运行时自动降为 `false`，防止 Certbot 使用尚未更新的公网 TCP 80 转发。
- RakNet、Geyser、Raknetify 的 UDP 监听由对应第三方插件所有，StarX 不会伪造所有权或擅自修改其端口。

选择结果写入 `network-automation.json -> ports`，其中包括冲突策略、候选范围、配置端口、最终端口、选择模式、占用端口、保留端口和运行时文件名。Paper/Folia 的 `auto-detection.json` 会记录 `velocityBaseUrl` 和 `velocityRuntimeEndpoint`，用于区分静态配置地址与活动运行时地址。

## 默认配置

```yaml
network-automation:
  enabled: true
  report-file: "network-automation.json"

  public-address:
    enabled: true
    minimum-agreement: 2
    timeout-ms: 2500
    endpoints:
      - "https://api64.ipify.org"
      - "https://checkip.amazonaws.com"
      - "https://icanhazip.com"

  frp:
    mode: "detect"
    public-host: ""
    public-scheme: "http"
    public-url: ""
    proxy-name: "starx-api"
    local-address: "127.0.0.1"
    local-port: 8788
    remote-port: 0
    frpc-command: "frpc"
    main-config-file: ""
    managed-config-file: "frp/starx-api.toml"
    auto-apply: false

  certificate:
    enabled: false
    domain: ""
    email: ""
    client: "auto"
    challenge: "http-01"
    staging-first: true
    auto-run: false
    http01-local-port: 8789
    http01-public-route-confirmed: false
    renew-before-days: 30
```

## 公网与假公网识别

### 地址分类

StarX 在本地识别：

- IPv4/IPv6 回环地址；
- RFC 1918 私网；
- RFC 6598 CGNAT；
- 链路本地地址；
- 文档、基准测试、组播和保留地址；
- 全局可路由地址。

`PUBLIC` 只表示地址策略上可全局路由，不表示端口可达。

### 外部共识

`public-address.endpoints` 必须使用 HTTPS。StarX 按服务商主机名去重，一个服务商最多一票。达到 `minimum-agreement` 后才返回 `CONFIRMED`。

常见结果：

| 状态 | 含义 |
|---|---|
| `CONFIRMED` | 至少两个独立来源返回同一有效公网地址 |
| `CONFLICT` | 有效来源返回了不同地址 |
| `INSUFFICIENT` | 有效来源数量不足 |
| `NO_VALID_OBSERVATION` | 没有可信的有效地址响应 |

拓扑结果：

| 类型 | 含义 |
|---|---|
| `DIRECT_PUBLIC_ADDRESS` | 外部地址与本机全局地址一致 |
| `TRANSLATED_OR_FAKE_PUBLIC` | 本机存在公网样式地址，但与外部地址不同 |
| `CGNAT` | 检测到 `100.64.0.0/10` |
| `PRIVATE_NAT` | 本机是私网地址，外部存在 NAT 地址 |
| `PRIVATE_ONLY` | 仅检测到私网地址 |
| `UNVERIFIED_PUBLIC` | 本机有公网样式地址，但外部共识不足 |
| `UNKNOWN` | 证据不足 |

## FRP

### 自动发现顺序

`main-config-file` 留空时，StarX 按有限范围查找，不遍历磁盘：

1. 正在运行的 `frpc` 进程，其 `-c`、`--config` 或 `--config=...` 参数；
2. StarX 数据目录及其 `frp/` 子目录；
3. 当前工作目录及其 `frp/` 子目录；
4. Linux 的 `/etc/frp`、`/usr/local/etc/frp`；
5. Windows 的 `%SystemDrive%\frp`。

检测模式可发现以下主配置文件名：

```text
frpc.toml
frpc.yaml
frpc.yml
frpc.ini
```

`detect` 可对这些格式执行只读 `frpc status`。`managed` 的自动应用当前只支持 TOML，因为 StarX 必须精确验证主配置的 `includes` 数组；YAML、YML 或 INI 会显式报告 `managed_config_format_unsupported`，不会尝试猜测或改写。

显式填写 `main-config-file` 后，该路径具有最高优先级；路径不存在时不会静默改用其他配置。

### 模式

#### `off`

关闭 FRP 检测和托管。仍可通过 `public-url` 或旧的 `http.frp-public-url` 提供手工地址，但报告会将入站可达性标记为 `UNVERIFIED`。

#### `detect`

不修改 FRP 配置。如果发现主配置，执行只读的：

```text
frpc status -c <主配置>
```

只有指定 `proxy-name` 的状态行返回有效端口时，才报告 `assigned_port_confirmed`。

#### `managed`

生成 StarX 自有片段：

```toml
[[proxies]]
name = "starx-api"
type = "tcp"
localIP = "127.0.0.1"
localPort = 8788
remotePort = 0
transport.useEncryption = true
transport.useCompression = true
healthCheck.type = "tcp"
healthCheck.timeoutSeconds = 3
healthCheck.maxFailed = 3
healthCheck.intervalSeconds = 10
```

主 `frpc.toml` 需要包含该文件，例如：

```toml
includes = ["frp/starx-api.toml"]
```

先保持：

```yaml
auto-apply: false
```

确认生成文件无误后再改为 `true`。自动应用顺序固定为：

```text
frpc verify -c <主配置>
frpc reload -c <主配置>
frpc status -c <主配置>
```

任何一步失败都会停止，且不会公布猜测端口。

自动应用还使用与主 `frpc` 配置同目录的跨进程锁。写入前和 reload 后都会请求本地 StarX API 的公开端点：

```text
GET http://<local-address>:<local-port>/v1/health
```

探测要求 HTTP 200，JSON 根对象中的 `status` 必须为 `ok`；不跟随重定向，响应上限为 16 KiB。这样可拒绝“端口可连接但实际由其他服务占用”、错误 JSON、降级状态和重定向目标。探测结果写入 `frp.localHealth` 与 `frp.localHealthAfterReload`。

若初始语义检查失败，不写入托管片段，也不执行 reload。若 verify、reload、status 或 reload 后语义检查失败，StarX 会恢复原托管片段，并在需要时重新 verify/reload 旧配置。报告中的 `rollback` 字段给出恢复结论。

自动应用使用持久化事务日志保护进程崩溃边界。日志和原片段备份位于：

```text
<StarX 数据目录>/frp/transactions/<配置标识>.transaction.json
<StarX 数据目录>/frp/transactions/<配置标识>.backup
```

事务阶段：

- `PREPARED`：原片段已备份并写入事务日志，但尚未调用 reload；崩溃恢复只需恢复文件。
- `RELOAD_REQUIRED`：即将或已经调用 reload；崩溃恢复必须恢复文件，并重新执行旧配置的 verify/reload。

新的自动应用开始前会先恢复未完成事务。事务日志、备份校验和、主配置路径或托管片段路径不一致时，自动应用立即停止。若当前托管片段既不匹配事务中的原内容，也不匹配候选内容，则视为管理员已人工修改，StarX 保留人工内容和事务日志，不进行覆盖。只有提交成功或旧配置恢复成功后才删除日志和备份。

### 公网 URL

已知 frps 主机名时：

```yaml
public-host: "frp.example.com"
public-scheme: "http"
```

StarX 会在状态回读端口后生成：

```text
http://frp.example.com:<服务器分配端口>
```

frps 前面已有反向代理或固定域名入口时，可填写：

```yaml
public-url: "https://panel.example.com/starx"
```

此 URL 被视为管理员提供的入口，不代表 StarX 已验证其入站可达性。

## 免费证书

当前自动执行器使用 Certbot 的 HTTP-01 standalone 模式。每个正式证书 lineage 使用独立的跨进程锁和持久化尝试状态：

```text
Velocity/plugins/starx/certificates/
├── automation/
│   ├── <lineage-hash>.lock
│   └── <lineage-hash>.state.json
├── staging/
└── production/
```

锁覆盖 staging 与 production 的完整执行链。同一数据目录中的多个 JVM 不能同时操作相同 lineage，但不同 lineage 不会互相阻塞。状态文件使用原子替换写入；损坏或无法解析时安全停止并报告 `attempt_state_invalid`，不会猜测或覆盖历史状态。

StarX 在写入 `IN_PROGRESS` 状态后为执行设置 15 分钟崩溃保护。若 JVM 或 Certbot 在命令期间异常退出，后续实例会在保护窗口内报告 `backoff_active`，避免立即重复申请。

HTTP-01 本地端口会在预检、staging 进程启动前和 production 进程启动前分别重新绑定探测。只要任一时点端口已被占用，就停止并记录 `CHALLENGE_PORT_OCCUPIED`。StarX 不会静默切换端口，因为管理员确认的公网 TCP 80 路由指向的是准确的 `http01-local-port`。

staging 与 production 证书目录完全隔离，测试证书不会污染正式证书 lineage。

### HTTP-01 前置条件

配置示例：

```yaml
certificate:
  enabled: true
  domain: "panel.example.com"
  email: "admin@example.com"
  client: "auto"
  challenge: "http-01"
  staging-first: true
  auto-run: false
  http01-local-port: 8789
  http01-public-route-confirmed: true
  renew-before-days: 30
```

必须同时满足：

1. 域名 A/AAAA 记录指向实际公网入口；
2. 公网 TCP 80 能到达运行 StarX 的主机；
3. 公网 TCP 80 被转发到 `http01-local-port`；
4. 本地防火墙允许 Certbot 临时监听该端口；
5. 没有其他进程占用该本地端口；
6. 已安装并可执行 `certbot`。

先使用：

```yaml
auto-run: false
```

检查报告状态为 `READY`。随后可启用：

```yaml
auto-run: true
```

若 `staging-first: true`，StarX 先运行 staging 命令，成功后才运行 production 命令。

### FRP 与 HTTP-01

FRP 托管模式使用 `remotePort = 0`，通常得到任意高位端口。Let's Encrypt HTTP-01 仍要求公网 TCP 80，因此“FRP 已分配一个空闲端口”不等于可以完成 HTTP-01。

可选方案：

- 在 frps 或前置反向代理上将公网 80 路由到本地挑战端口；
- 使用管理员维护的固定 80 路由；
- 改用 DNS-01。

当前 DNS-01 会安全停止并报告 `DNS_PROVIDER_REQUIRED`，不会在没有具体 DNS 服务商插件和 API 凭据时尝试自动修改 DNS。

### 续期与失败退避

StarX 每 30 分钟刷新状态。正式证书存在时，会解析 X.509 `notAfter`，仅在距离到期不超过 `renew-before-days` 时调用 Certbot。

失败状态按 lineage 持久化，并使用指数退避，最长不超过 24 小时：

| 失败分类 | 首次退避 |
|---|---:|
| `CHALLENGE_PORT_OCCUPIED` | 5 分钟 |
| `TIMEOUT` | 15 分钟 |
| `VALIDATION_FAILURE` / `COMMAND_FAILED` | 30 分钟 |
| `CLIENT_UNAVAILABLE` / `DNS_FAILURE` | 1 小时 |
| `ACME_RATE_LIMIT` | 6 小时 |

连续失败会将退避时间加倍，成功的 production 执行会把失败计数和 `nextAllowedAt` 清零。

常见续期与执行状态：

| 状态 | 含义 |
|---|---|
| `MISSING` | 正式证书不存在，需要申请 |
| `INVALID` | 正式证书无法解析，需要替换 |
| `VALID` | 尚未进入续期窗口，不执行命令 |
| `DUE` | 已进入续期窗口 |
| `not_due` | 证书有效且未进入续期窗口 |
| `operation_locked` | 相同 lineage 正由另一个进程操作 |
| `backoff_active` | 持久化退避或崩溃保护窗口尚未结束 |
| `attempt_state_invalid` | 尝试状态文件损坏，已安全停止 |
| `http01_port_occupied` | 初始 HTTP-01 端口预检失败 |
| `staging_port_occupied` | staging 启动前端口被占用 |
| `production_port_occupied` | production 启动前端口被占用 |
| `staging_failed` / `production_failed` | Certbot 失败，已分类并写入退避状态 |
| `production_succeeded` | 正式证书申请或续期成功，退避状态已复位 |

正式证书路径在报告的 `certificate.fullChain` 和 `certificate.privateKey` 中给出。证书执行报告还包含 `lineage`、`operationLock`、`attemptStateFile`、`attemptStateStatus`、`attemptState` 和 `failureClass`。

## 报告排障

重点字段：

```text
publicAddress.status
topology.type
topology.directAddressConfirmed
inboundPortConfirmed
frp.discoverySource
frp.status
frp.mainConfigFormat
frp.localHealth
frp.localHealthAfterReload
frp.transactionStateFile
frp.transactionBackupFile
frp.transactionStateStatus
frp.transactionRecovery
frp.transaction
frp.transactionOutcome
frp.assignedPort
certificate.status
certificate.renewal.status
certificate.execution
certificate.failureClass
certificate.attemptStateStatus
certificate.attemptState
selectedEndpoint
recommendations
```

FRP 常见状态：

| 状态 | 处理 |
|---|---|
| `no_confirmed_assignment` | 未发现可回读的代理端口 |
| `config_written_awaiting_auto_apply` | 已生成片段，等待管理员启用自动应用 |
| `main_config_missing` | 未找到主配置 |
| `managed_config_format_unsupported` | 自动托管只支持 TOML 主配置；YAML/INI 可继续用于只读 detect |
| `managed_include_missing` | TOML 主配置没有包含 StarX 片段 |
| `local_target_unhealthy` | 写入前 `/v1/health` 未返回 HTTP 200 和 `status: ok` |
| `local_target_unhealthy_after_reload` | reload 后语义健康检查失败，已触发配置回滚 |
| `transaction_recovery_state_invalid` | 事务日志、路径或备份校验失败；保留文件并人工检查 |
| `transaction_recovery_conflict` | 托管片段被事务之外的内容修改；不会自动覆盖人工内容 |
| `transaction_recovery_failed` | 旧片段已恢复或正在恢复，但旧配置 verify/reload 尚未成功；保留事务日志供下次重试 |
| `verify_failed` | 修复 frpc 配置错误 |
| `reload_failed` | 检查 frpc 管理接口和运行状态 |
| `status_failed` | 检查 frpc status 是否可用 |
| `assigned_port_not_reported` | frps 尚未分配，或状态输出格式不含该代理端口 |
| `assigned_port_confirmed` | 已从指定代理状态行确认端口 |

证书常见状态：

| 状态 | 处理 |
|---|---|
| `MISSING_DOMAIN` | 填写域名 |
| `INVALID_DOMAIN` | 使用有效 DNS 名称，不要填写 IP |
| `MISSING_EMAIL` / `INVALID_EMAIL` | 填写有效 ACME 联系邮箱 |
| `HTTP_ROUTE_UNCONFIRMED` | 完成公网 80 路由后显式确认 |
| `WILDCARD_REQUIRES_DNS` | 通配符证书改用 DNS-01 |
| `DNS_PROVIDER_REQUIRED` | 配置具体 DNS 服务商自动化 |
| `READY` | 前置条件完整 |

## 部署检查清单

1. 保持 `mode: detect`，启动 Velocity。
2. 查看 `network-automation.json`。
3. 确认公网拓扑；不要把 `directAddressConfirmed` 当作端口回连成功。
4. 使用 FRP 时确认 `discoverySource` 和主配置路径。
5. 切换到 `managed`，但先保持 `auto-apply: false`。
6. 在主 `frpc.toml` 中加入 `includes`。
7. 检查生成的 `remotePort = 0` 片段。
8. 开启 `auto-apply`，确认 `assigned_port_confirmed`。
9. 配置域名和公网 80 路由。
10. 先以 `auto-run: false` 检查证书计划。
11. 开启 Certbot 自动执行，并确认 staging 后 production 成功。
12. 从互联网独立验证 API URL、证书链和重启恢复。

修改核心网络配置后，建议完整重启 Velocity，不使用插件热重载。
