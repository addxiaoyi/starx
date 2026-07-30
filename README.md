# StarX

StarX 是面向 Velocity、Paper 和 Folia 网络的 Java 21 插件。项目只发布一个可部署文件：`starx-universal-0.3.2.jar`。同一个 JAR 放入代理端和每个后端实例，由平台加载器选择对应入口。

当前插件版本：**0.3.2**

公共扩展 API：**1.0.0**

## 完整功能清单

本节以当前源码和 `schema-version: 5` 默认配置为准。这里列的是**插件具备的功能**，不代表每项都已在某一生产环境启用。

| 标记 | 含义 |
|---|---|
| 默认开启 | 默认配置中启用；仍可能因平台不兼容或缺少必需配置而降级 |
| 默认关闭 | 已实现，但必须由管理员显式开启 |
| 软依赖增强 | 仅在对应第三方插件已安装且兼容时激活；缺失时核心功能继续运行 |
| 需外部配置 | 必须提供网站、API、Webhook、FRP、证书或其他外部参数后才会发起连接 |

### 平台职责

| 能力 | Velocity | Paper | Folia |
|---|---|---|---|
| 玩家入口、身份判定和登录准入 | 权威执行 | 接收认证后的玩家 | 接收认证后的玩家 |
| Uworld 内置认证世界 | 创建并持有 | 不加载 | 不加载 |
| 注册、密码、TOTP、恢复码、正版/Floodgate 认证 | 执行 | 提供 `/sx` 账号安全界面 | 提供 `/sx` 账号安全界面 |
| Hub、重连、重定向、队列、维护和全局网络命令 | 执行 | 提供目标节点和本地状态 | 提供目标节点和本地状态 |
| 后端人数、版本、TPS、MSPT、内存、运行时间 | 汇总 | 采集并上报 | 采集并上报 |
| 调度模型 | Velocity scheduler | Bukkit 主线程 | global、region、entity scheduler |
| 第三方扩展服务 | `StarxServiceProvider` | Bukkit `ServicesManager` | Bukkit `ServicesManager` |

### 账号、身份与会话

| 功能 | 具体行为 | 状态/边界 |
|---|---|---|
| 离线账号注册与登录 | 未注册玩家在 Uworld 中通过聊天输入密码注册；已注册玩家输入密码登录，敏感内容不会广播到公共聊天 | 默认开启 |
| 正版自动认证 | Yggdrasil 身份通过可信链路时可免去离线密码流程 | 默认开启 |
| 基岩身份 | 通过 Floodgate 读取可信基岩身份并参与认证与变量渲染 | 软依赖增强 |
| 离线身份前缀 | 可识别带指定前缀的离线用户名，但前缀本身不会绕过密码 | 默认开启 |
| 密码免输窗口 | 同一账号、同一 IP 在最近成功输入密码后的有界时间内可复用认证 | 可配置，默认 30 分钟 |
| TOTP 二步验证 | 设置密钥、`otpauth://`、二维码地图、6 位验证码确认、关闭 TOTP、轮换恢复码 | 功能默认开放，按玩家自愿开启 |
| 一次性恢复码 | 生成、哈希保存、逐个消费；轮换后旧恢复码立即失效 | 随 TOTP 使用 |
| 邮箱验证 | 向邮箱发送挑战码并确认绑定；邮件通道未配置时明确降级 | 需 Webhook/邮件网关配置 |
| 跨设备审批 | 网站生成一次性审批链接，游戏内可获得二维码地图和可点击 URL；链接有时效且只能使用一次 | 需公开网站地址 |
| 网站账号绑定 | 生成、解析和消费短时绑定码，校验 Minecraft UUID、用户名与网站账号唯一性 | HTTP API + 网站配合 |
| QQ 绑定 | 通过 NapCat/QQ 流程生成和确认绑定信息 | 默认关闭，需 NapCat 配置 |
| 密码重置和凭据变更 | 管理 API 可重置密码；凭据变更后主动断开现有会话并要求重新登录 | 管理接口 |
| 玩家会话账本 | 记录登录来源、连接、退出原因、IP 会话、累计游玩和当前会话摘要 | 默认开启 |
| 可信设备与风险信号 | 记录受信设备/IP，会话变化可产生高风险和二次验证事件 | 默认开启；不会凭单一信号自动封禁 |
| 账号删除 | 支持查询、申请、取消和后台重试执行；失败请求保留以便后续重试 | HTTP API + 定时执行器 |
| UniAuth 迁移 | 可连接外部 UniAuth，执行账号迁移和迁移命令 | 默认关闭，需外部 API |

Paper/Folia 的 `/sx` 使用库存菜单和铁砧输入，不把密码、验证码或邮箱写入公共聊天。可完成邮箱绑定、TOTP 开启/确认/关闭、恢复码轮换和跨设备审批。

### Uworld 内置认证世界

- Uworld core 和底层 Limbo 实现直接内嵌在 Universal JAR，不需要外置 LimboAPI。
- 为认证流程创建受管虚拟世界，持有玩家会话、输入回调、倒计时、超时和终态清理。
- 支持 `VOID`、Schematic 和 Structure/NBT 世界加载；解析失败时关闭对应流程，不回退到未声明世界。
- 可配置维度、出生点、朝向、游戏模式、视距、模拟距离、平台半径和世界偏移。
- 认证成功后只连接启动时已解析的精确目标服务器，不执行任意 fallback。
- 诊断世界默认关闭，可通过 `/sxworld test` 惰性创建；退出、超时和插件关闭都会清理会话。
- 标题、操作栏、提示音、成功音和失败音均可配置，默认使用中文提示。

Uworld 不是多世界插件、后端服务器或通用 fallback，也不支持热重载。

### 路由、队列与玩家体验

| 功能 | 具体行为 | 默认状态 |
|---|---|---|
| Hub | `/sxhub` 将玩家发送到配置的认证目标/大厅 | 开启 |
| 踢出重定向 | 玩家被后端踢出时可重定向到大厅，避免回到同一个失败目标 | 开启 |
| 重连 | 记住最近子服，在下次进入时通过后端健康路由选择原服或可用替代节点 | 开启 |
| 普通队列 | 识别“服务器已满”原因，按服 FIFO 排队，显示位置与 ETA；连接异步执行并有独立 10 秒超时 | 开启 |
| 智能队列 | VIP 权重、优先级排序、动态放行速率、负载采样、并发放行上限和健康节点选择 | 关闭；与普通队列二选一配置 |
| 维护模式 | 状态持久化，登录拦截、权限/白名单绕过，并通过桥接同步到全部后端；每分钟重广播校准 | 开启，初始为关闭状态 |
| 新手引导 | 首次进入后显示可恢复步骤，支持查看、开始、下一步、重置和跳过，进度持久化 | 开启 |
| MOTD | 根据代理状态生成服务器列表响应 | 开启 |
| 内置玩家列表 | MiniMessage 页眉/页脚、每服与全网人数、身份和登录变量；不要求 TAB | 开启 |
| TAB 变量 | 将 StarX 变量注册给 TAB | 软依赖增强 |
| 全局在线列表 | 汇总网络玩家和服务器分布 | 开启 |
| 全局聊天与消息桥 | 代理聊天、跨服插件消息和事件转发 | 开启 |
| 欢迎信息 | 登录后展示基于账号状态的欢迎卡片 | 开启 |
| Forge/客户端模组兼容 | 识别客户端模组画像；可对纯净服配置允许、告警或拒绝策略 | 开启；严格 guard 默认关闭 |
| 地图模组兼容 | 识别 JourneyMap、Xaero、VoxelMap 等客户端地图模组，并保持其后端通道透明 | 开启；通知玩家默认关闭 |
| RakNet/Geyser 入口 | 检测 Geyser/RakNet provider 并声明基岩入口能力 | 开启；provider 默认非强制 |
| 文件清理器 | 清理配置范围内的陈旧文件 | 默认关闭 |
| 增强网络命令 | 全服列表、查找玩家、转服、广播、Ping 和排空指定子服 | 开启，受权限控制 |

普通队列和智能队列都使用非阻塞连接请求；慢连接不会串行阻塞整条队列，失败项可在后续周期重试。

### Velocity 与 Paper/Folia 桥接

双方通过版本化二进制通道 `starx:bridge` 协作：

- `proxy.hello` / `backend.hello` 握手；
- 状态请求与响应；
- 皮肤查询与响应；
- 维护配置同步；
- 后端命令投递与响应；
- 协议版本、字段长度、属性数量和包大小边界校验。

有在线玩家时使用插件消息作为载体；空服时可使用认证 HTTP heartbeat 和每个节点独立的有界 mailbox。节点状态分为：

- `UNSEEN`：尚未收到有效报告，不等于已离线；
- `LINKED`：最近状态新鲜；
- `STALE`：最后报告超过允许窗口。

Paper/Folia 后端还提供：

- 自动生成稳定 `node-id` 和 `server-type`；
- 自动发现同机 Velocity 配置、活动运行时端点和 API Key；
- 无有效凭据时安全关闭空服 heartbeat，但保留插件消息桥；
- Paper 主线程与 Folia global/region/entity 调度隔离；
- 上报人数、容量、平台、Minecraft 版本、TPS、MSPT、内存、运行时间和能力；
- 接收维护状态及代理命令，并在正确 scheduler 执行后回传结果；
- PlaceholderAPI 扩展：`%starx_node%`、`%starx_platform%`、`%starx_execution%`、`%starx_capabilities%`、`%starx_online%`、`%starx_max%`、`%starx_proxy_status%`、`%starx_player%`；
- SkinsRestorer 反射软集成和签名 texture 查询。

### 皮肤与网站同步

| 功能 | 说明 |
|---|---|
| 代理皮肤桥 | 查询网站或后端皮肤，带有界缓存和 fallback；可向在线玩家请求刷新 |
| 后端皮肤解析 | 通过 SkinsRestorer 读取签名 texture；插件缺失或无数据时返回 `found=false` |
| 网站节点注册 | 使用一次性 bootstrap token 注册，成功后原子清空 bootstrap 并保存长期 node-token |
| 网站心跳 | 周期上报代理版本、Minecraft 版本、人数、维护状态、子服快照和能力 |
| 纹理清单 | 分页提交玩家皮肤/披风清单，批次和页数有上限 |
| 缺失纹理补传 | 网站返回缺失哈希后，插件只上传需要的 PNG；凭 SHA-256 校验内容 |
| 网站皮肤应用 | `/sxskin apply <皮肤编号>` 可为已绑定角色提交网站目录皮肤选择 |
| 失败恢复 | 凭据持久化、指数退避、请求超时和安全日志；令牌不会出现在状态命令或 Placeholder 中 |

`website-sync.enabled` 默认关闭；启用前必须配置网站地址和一次性 bootstrap token。网站同步与网站主动轮询插件是两个独立方向，可分别启用。

网站也可以主动连接插件：管理员在网站后台填写插件经公网、反向代理或 FRP 暴露的 HTTP API 根地址，以及与 Velocity 根配置一致的 `api-key`。网站随后可主动执行 `GET /v1/health`、无副作用 `POST /v1/admin/bridge/ping`、`GET /v1/network/status` 和既有受控管理操作，并可按配置周期拉取状态。插件只负责绑定本地 API、鉴权和返回结果；公网/FRP 穿透、防火墙、TLS 和外部可达性仍由部署者实测，填写 URL 不会被当作已经验证可达。

### 安全、风控与可靠性

| 模块 | 能力 | 默认状态 |
|---|---|---|
| 登录保护 | 密码尝试限流、暴力破解保护、会话租约和敏感操作独立限流 | 开启 |
| Bot 检测 | 以 IP 为维度统计 Ping/连接速率并发布安全事件；缓存容量和窗口有上限 | 开启；检测/上报，不自动封禁 |
| Crash 检测 | 检查超大插件消息、NBT 深度和异常数组尺寸并发布安全事件 | 开启；事件层保护，不替代协议层防火墙 |
| 风险评分 | 本地地址分类、新设备信号、高风险和二次验证事件 | 开启；ASN 查询默认关闭 |
| 反作弊汇聚 | 接收后端 `starx:anticheat` 检测消息、校验 JSON 大小、累计 VL 并产生告警 | 开启；不是独立移动检测引擎 |
| Blossom Guard | 调用外部风险服务阻止明确命中的 IP；服务不可用时 fail-open | 默认关闭，需外部服务 |
| 智能限流 | 按内存和估算负载动态调整 Ping/连接阈值并可直接拒绝超限登录 | 默认关闭 |
| 智能告警 | 聚合安全事件、抑制重复告警并输出统一告警 | 默认关闭 |
| HMAC 请求校验 | 时间戳漂移限制、请求目标签名、固定时间比较和有界重放缓存 | 管理 HTTP/Webhook 接口 |
| Webhook outbox | Webhook 失败时写入磁盘，启动后重放；文件和重试均有界 | 配置 Webhook 后启用 |
| 事件时间线 | 记录安全、管理和运行事件，提供 incidents/security events 查询 | 开启 |
| 兼容性门禁 | 检查 Java、Velocity/Paper/Folia 和可选插件版本；严格模式可阻止不支持的平台启动 | 开启 |
| 配置升级 | 根据 schema 备份旧配置、合并新增字段并写升级报告，保留未知自定义字段 | 开启 |

### 运维、网络与自动配置

- HTTP API 默认仅绑定 `127.0.0.1`，支持 `strict`、`fallback`、`persist`、`ephemeral` 端口冲突策略和有界候选范围。
- `api-key` 留空时自动生成 384 位随机密钥；自动报告不写入密钥。
- 通过多个独立 HTTPS 来源达成共识后才确认公网出口地址，并区分公网、私网、CGNAT、链路本地、文档、测试、组播和保留地址。
- FRP 支持 `off`、`detect`、`managed`：托管模式只允许 `remotePort = 0`，使用专属配置片段、事务记录和原子恢复；默认只检测，不自动 reload。
- 证书助手支持 Let's Encrypt、HTTP-01、staging-first、到期窗口、失败退避和状态文件；默认关闭且不会擅自申请证书。
- 自动发现运行中的 frpc、有限常见配置目录、活动 HTTP 端点、跨进程锁和端口租约。
- 生成 `auto-detection.json`、`compatibility-report.json`、`network-automation.json`、`runtime-endpoint.json` 等不含秘密的诊断文件。
- HTTP 健康、网络状态、节点探针、ACK Ping 和事故时间线均可用于发布验收。

### 管理、审核与数据能力

Velocity 内置 SQLite 存储和对应仓库，覆盖：

- 用户、账号身份、绑定、可信设备、IP 会话和玩家会话；
- 处罚、举报、管理备注、公告和投票；
- 教程进度、运行时设置、账号删除请求和绑定挑战；
- 审计事件、Webhook outbox 和网络运行状态。

`/sxadmin` 与管理 HTTP API 提供举报、历史、备注、公告、绑定、封禁、踢出、处罚、审批、投票和账号操作。默认数据库为 SQLite，连接池上限为 2；当前 Universal JAR 不把 MySQL/PostgreSQL 写成默认受支持部署。

### Velocity HTTP API

仅 `GET /v1/health` 是公开端点，其余路由要求 `X-API-Key`。敏感路由还使用独立限流、HMAC 时间戳与重放保护。

| 接口组 | 方法与完整端点 |
|---|---|
| 健康与网络 | `GET /v1/health`、`GET /v1/network/status`、`POST /v1/admin/bridge/ping`、`GET /v1/admin/incidents`、`GET /v1/security/events` |
| 后端节点 | `POST /v1/backend/heartbeat`、`POST /v1/admin/backend/probe` |
| 用户查询 | `GET /v1/user/exists`、`GET /v1/user/detail`、`GET /v1/user/overview` |
| 密码与 TOTP | `POST /v1/admin/reset-password`、`POST /v1/admin/totp/setup`、`POST /v1/admin/totp/confirm`、`POST /v1/admin/totp/disable`、`POST /v1/admin/totp/recovery-codes/rotate` |
| 邮箱与审批 | `POST /v1/admin/email-challenge/send`、`POST /v1/admin/email-challenge/confirm`、`POST /v1/admin/approval/create`、`POST /v1/admin/approval/confirm` |
| 账号删除 | `GET /v1/user/deletion/status`、`POST /v1/user/deletion/request`、`POST /v1/user/deletion/cancel`、`POST /v1/admin/delete-user` |
| 绑定与外部账号 | `GET/POST /v1/admin/bindings`、`POST /v1/admin/bindings/verify-code`、`POST /v1/admin/bindings/verify`、`POST /v1/admin/bindings/resolve-code`、`POST /v1/user/bindings/unlink`、`POST /v1/admin/link-external-user` |
| 封禁与处罚 | `GET /v1/ban`、`POST /v1/admin/ban`、`POST /v1/admin/ban/player`、`POST /v1/admin/kick`、`GET/POST /v1/admin/punishments` |
| 举报、备注与公告 | `GET/POST /v1/admin/reports`、`POST /v1/admin/reports/resolve`、`POST /v1/admin/reports/dismiss`、`GET/POST /v1/admin/notes`、`GET/POST /v1/admin/announcements`、`POST /v1/admin/announcements/read` |
| 投票 | `GET /v1/admin/votes`、`GET /v1/admin/votes/active`、`POST /v1/admin/votes/cast` |
| 皮肤 | `POST /v1/admin/skin-refresh` |

### 完整模块开关

下表覆盖默认配置中的全部 `modules.*` 项。模块开关控制模块是否注册；第三方服务仍需自己的配置和依赖。

| 配置键 | 默认 | 功能 |
|---|---:|---|
| `starx.auth` | 开 | 离线注册、密码登录、TOTP、认证准入和 Uworld 认证流程 |
| `starx.auth.yggdrasil` | 开 | 正版 Yggdrasil 身份接入 |
| `starx.auth.uniauth` | 关 | 外部 UniAuth 认证 |
| `starx.player-list` | 开 | 内置全局玩家列表和变量渲染 |
| `starx.player-sessions` | 开 | 玩家连接/退出会话账本 |
| `starx.auth.migration` | 关 | 外部账号迁移运行时 |
| `starx.auth.migration.commands` | 关 | `/sxmigrate` 迁移命令 |
| `starx.skin-bridge` | 开 | 网站/后端皮肤查询、缓存和刷新 |
| `starx.chat` | 开 | 代理全局聊天 |
| `starx.maintenance` | 开 | 持久化维护状态、登录拦截和后端同步 |
| `starx.motd` | 开 | 服务器列表 MOTD |
| `starx.redirect` | 开 | 被踢出后的大厅重定向 |
| `starx.queue` | 开 | 普通 FIFO 满服队列 |
| `starx.tutorial` | 开 | 可恢复的新手引导 |
| `starx.hub` | 开 | `/sxhub` 大厅命令 |
| `starx.uworld` | 开 | 内置认证和诊断世界 |
| `starx.reconnect` | 开 | 最近子服记忆和健康重连 |
| `starx.info` | 开 | `/starx` 状态、服务器和兼容性命令 |
| `starx.forge` | 开 | 客户端模组画像与纯净服策略 |
| `starx.proxytools.raknet` | 开 | Geyser/RakNet provider 探测 |
| `starx.online` | 开 | 全局在线玩家统计命令 |
| `starx.backend-bridge` | 开 | Velocity 与后端桥接、节点注册和 mailbox |
| `starx.messaging` | 开 | 跨服插件消息与事件桥 |
| `starx.welcome` | 开 | 登录欢迎信息 |
| `starx.admin` | 开 | 举报、备注、公告、绑定等管理命令 |
| `starx.enhanced` | 开 | `/sxnet` 网络运维命令 |
| `starx.proxytools.filecleaner` | 关 | 陈旧文件清理 |
| `starx.security.bot` | 开 | Ping/连接速率检测和事件 |
| `starx.security.crash` | 开 | 异常包边界检测和事件 |
| `starx.security.risk` | 开 | IP/设备风险评分事件 |
| `starx.security.anticheat` | 开 | 后端反作弊检测汇聚和告警 |
| `starx.security.blossom` | 关 | 外部 Blossom/Bush IP 风险守卫 |
| `starx.security.smart-rate` | 关 | 动态 Ping/登录限流 |
| `starx.security.smart-alert` | 关 | 安全事件聚合和告警抑制 |
| `starx.proxytools.smart-queue` | 关 | VIP 优先和动态放行智能队列 |
| `starx.integrations.qq` | 关 | QQ 业务事件集成 |
| `starx.integrations.plan` | 关 | Plan 指标摘要 |
| `starx.integrations.mapmod` | 开 | 客户端地图模组识别与透明兼容 |
| `starx.integrations.napcat` | 关 | NapCat WebSocket/HTTP 与 QQ 群转发 |
| `starx.integrations.luckperms` | 开 | LuckPerms 账号绑定上下文；缺失时自动降级 |
| `starx.integrations.floodgate` | 开 | Floodgate 基岩可信身份；缺失时自动降级 |
| `starx.integrations.tab` | 开 | TAB `%starx_*%` 变量；缺失时使用内置玩家列表 |
| `starx.vote` | 开 | 发起和参与在线投票 |

### 变量与扩展 API

内置/TAB 变量包括：`starx_player`、`starx_auth_status`、`starx_registered`、`starx_2fa_enabled`、`starx_last_login`、`starx_login_source`、`starx_client_platform`、`starx_bedrock`、`starx_bind_qq`、`starx_bind_discord`、`starx_playtime`、`starx_first_join`、`starx_server`、`starx_online`、`starx_network_online`、`starx_network_max`、`starx_server_online`、`starx_server_max`、`starx_playtime_total`、`starx_server_footprint`、`starx_reputation` 和 `starx_trust_level`。

Extension API 1.0.0 提供版本协商、平台与能力查询、扩展注册、状态快照、幂等注销、事件订阅、失败回滚和 StarX 停止时逆序关闭。Velocity 通过 `StarxServiceProvider` 暴露服务；Paper/Folia 通过 Bukkit `ServicesManager` 注册 `StarxService`。

各模块都有明确的平台边界和降级行为。架构、安装顺序和线程规则见 [`docs/STARX_PLATFORMS.md`](docs/STARX_PLATFORMS.md)。

## 下载与安装

从 [GitHub Releases](https://github.com/addxiaoyi/starx/releases) 下载：

```text
starx-universal-0.3.2.jar
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
- 通过多个独立 HTTPS 来源判断出口公网地址，并区分 CGNAT、NAT 和“公网样式但不可确认”的地址；
- 在有限范围内识别运行中的 frpc 或常见 FRP 配置；托管模式只使用 `remotePort = 0` 由 frps 原子分配端口；
- 检查 HTTP-01、证书到期窗口和 Certbot 前置条件；
- 按 `strict`、`fallback`、`persist` 或 `ephemeral` 策略处理端口冲突，并在有界范围内稳定复用运行时端口；
- 写入不含密钥的自动探测报告。默认只检测，不执行 frpc reload 或 Certbot。

Paper/Folia 首次启动会：

- 检测 Paper 或 Folia；
- 生成稳定的 `node-id` 和 `server-type`；
- 在标准同机目录中查找 Velocity 配置和活动运行时端点；
- 校验 Velocity 进程与跨进程锁后复制 heartbeat 地址和 API key；
- heartbeat 失败时重新发现端点，支持 Paper/Folia 先于 Velocity 启动；
- 写入不含密钥的自动探测报告。

管理员可以关闭对应 `manage-*` 开关，保留手工配置所有权。网站地址、数据库、远程凭据、转发密钥和跨机器拓扑不会被猜测。

主要文件：

```text
Velocity/plugins/starx/config.yml
Velocity/plugins/starx/auto-detection.json
Velocity/plugins/starx/compatibility-report.json
Velocity/plugins/starx/network-automation.json
Velocity/plugins/starx/runtime-endpoint.json
Velocity/plugins/starx/runtime-endpoint.lock
Velocity/plugins/starx/runtime-port-lease.json

Paper/plugins/StarXServer/config.yml
Paper/plugins/StarXServer/auto-detection.json
Paper/plugins/StarXServer/compatibility-report.json
```

## 命令清单

命令只在对应模块启用时注册；管理命令还要求相应权限节点。

| 平台 | 命令 | 用途 |
|---|---|---|
| Velocity | `/starx [info|uptime|servers|doctor]` | 查看代理状态、运行时间、子服列表和兼容性 |
| Velocity | `/sxnodes status [server]` | 查看后端节点、平台、能力和 heartbeat 状态 |
| Velocity | `/sxworld <status|test|leave>` | 查看 Uworld、启动诊断世界或离开诊断世界 |
| Velocity | `/sxhub` | 返回配置的大厅/认证目标 |
| Velocity | `/sxguide [status|start|reset|next|skip]` | 查看和控制新手引导进度 |
| Velocity | `/sxmaintain <on|off>` | 开关维护模式并同步后端 |
| Velocity | `/sxonline` | 查看全网在线玩家分布 |
| Velocity | `/sxnet list` | 查看全服与玩家列表 |
| Velocity | `/sxnet find <玩家>` | 查找玩家所在子服 |
| Velocity | `/sxnet send <玩家> <服务器>` | 将玩家发送到指定子服 |
| Velocity | `/sxnet alert <消息>` | 向全网广播管理消息 |
| Velocity | `/sxnet ping` | 查看代理延迟/运行状态 |
| Velocity | `/sxnet drain <服务器> [原因]` | 排空指定子服的在线玩家 |
| Velocity | `/sxadmin report <玩家> <分类> [详情]` | 提交举报；分类含作弊、聊天滥用、刷屏、名称和其他 |
| Velocity | `/sxadmin history <玩家>` | 查看处罚、备注和举报历史 |
| Velocity | `/sxadmin note <玩家> <内容> [-s 级别]` | 添加 INFO/WARNING/CRITICAL 管理备注 |
| Velocity | `/sxadmin notes <玩家>` | 查看管理备注 |
| Velocity | `/sxadmin announce <标题> <内容>` | 持久化并广播公告 |
| Velocity | `/sxadmin bind qq` | 启动 QQ 绑定流程 |
| Velocity | `/sxskin` | 查看皮肤桥状态/帮助 |
| Velocity | `/sxskin apply <皮肤编号>` | 为已绑定角色应用网站目录皮肤 |
| Velocity | `/sxsecure` | 查看 TOTP 状态并打开账号安全入口；不接受敏感参数 |
| Velocity | `/sxvote start <玩家> <原因>` | 发起投票 |
| Velocity | `/sxvote <yes|no>` | 参与当前投票 |
| Velocity | `/sxguard anticheat [stats]` | 查看反作弊检测汇聚统计 |
| Velocity | `/sxguard anticheat player <玩家>` | 查看指定玩家的检测类型、VL 和最近记录 |
| Velocity | `/sxguard anticheat clear [玩家]` | 清理指定玩家或全部聚合记录 |
| Velocity | `/sxmigrate status` | 查看 StarVC 元数据迁移统计；模块默认关闭 |
| Velocity | `/sxmigrate import starvc [--dry-run]` | 导入 StarVC 用户元数据；建议先试运行，需 `starx.admin.migrate` |
| Paper/Folia | `/starxserver status` | 查看节点、平台、执行模型、人数和最后代理联系时间 |
| Paper/Folia | `/starxserver doctor` | 查看兼容性报告 |
| Paper/Folia | `/starxserver capabilities` | 查看后端调度模型和平台能力 |
| Paper/Folia | `/starxserver skin <uuid> <name>` | 调试 SkinsRestorer/后端皮肤解析 |
| Paper/Folia | `/sx` | 打开账号安全菜单 |
| Paper/Folia | `/sx 邮箱` | 绑定邮箱并确认邮件验证码 |
| Paper/Folia | `/sx 验证` | 开启并确认 TOTP |
| Paper/Folia | `/sx 关闭` | 使用当前密码关闭 TOTP |
| Paper/Folia | `/sx 重置` | 使用当前 TOTP 轮换恢复码 |
| Paper/Folia | `/sx approve-email` | 创建一次性网站邮箱绑定二维码 |
| Paper/Folia | `/sx approve-2fa` | 创建一次性网站二步验证审批二维码 |
| Paper/Folia | `/sx approve-skin` | 创建一次性皮肤站账号绑定二维码 |

`/starxserver` 的别名为 `/starxbackend`；`/sx` 的别名为 `/starxaccount` 和 `/account`。邮箱、TOTP、跨设备审批和网站皮肤操作都要求后端 heartbeat API 与对应外部服务已经配置并可达。

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

- 808 项 Java 测试；
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
- [`docs/NETWORK_AUTOMATION.md`](docs/NETWORK_AUTOMATION.md)：公网识别、FRP 原子端口和免费证书自动化
- [`docs/UWORLD_CONFIGURATION.md`](docs/UWORLD_CONFIGURATION.md)：Uworld 配置
- [`docs/UWORLD_ACCEPTANCE.md`](docs/UWORLD_ACCEPTANCE.md)：Uworld 验收
- [`docs/UWORLD_ENVIRONMENT.md`](docs/UWORLD_ENVIRONMENT.md)：部署和回滚
- [`starx-plugins/starx-universal/README.md`](starx-plugins/starx-universal/README.md)：Universal JAR 结构

## 许可证

StarX 自有代码采用 GNU Affero General Public License v3。内置或派生的第三方组件保留各自许可证和声明，见 `LICENSE`、`LICENSES/`、`NOTICE` 以及源码文件头。
