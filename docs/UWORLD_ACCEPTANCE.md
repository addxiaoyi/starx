# Uworld 验收

本文件定义 `starx-velocity.jar` 的发布门禁。自动测试、冷启动和真实客户端验收是三个独立层级；一个层级通过不能替代另一个层级。

状态只使用：

- `PASS`：本次候选 JAR 已执行，存在可定位证据。
- `FAIL`：已执行且结果不满足要求。
- `UNVERIFIED`：未执行、证据缺失或证据不属于本次候选 JAR。

不得把预期结果、历史结果、单元测试或文档完成度写成真实客户端 `PASS`。

## 候选物身份

每轮验收先记录：

<!-- UWORLD_CURRENT_CANDIDATE -->
```text
status=PASS
artifact=starx-plugins/starx-velocity/build/libs/starx-velocity.jar
sha256=C5CBFC4887B7B3ED228B9AFEEA2092EE81213EB99E6D201B4DFABEB0E648D695
size=17835926
java=21
velocity=3.5.0-SNAPSHOT build 606
timestamp=2026-07-23T23:31:27+08:00
operator=Codex local verification
```
<!-- /UWORLD_CURRENT_CANDIDATE -->

该身份来自本轮 clean build 后的实际文件。后续自动门禁、冷启动、环境和客户端证据都必须使用同一 SHA-256；重新构建产生不同 hash 时，本页对应层级自动失效。

2026-07-14 曾验证 SHA-256 `C2F9E82EAFF56B62E94DB76596309ED5C2FA5BF29068F2483854E611FEC3749D`、大小 `17850166` 的历史候选。该历史身份不能与上面的当前候选或其测试结果混用。

Uworld 的唯一部署物必须是 `starx-velocity.jar`。Paper/Folia 的 `starx-server.jar` 是不含 Uworld 的后端适配器；Velocity `plugins/` 中不得存在外置 LimboAPI、第二个 StarX Velocity JAR 或嵌套 LimboAPI JAR。

## 自动门禁

### 1. 上游同步测试

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File scripts/tests/sync-starx-limbo.Tests.ps1
```

要求：固定 commit、vendor checksum、保留 override 和 mapping 导入全部通过。

### 2. 干净构建与测试

```powershell
& .\scripts\invoke-gradle-ascii.ps1 `
  :starx-plugins:starx-limbo-api:clean `
  :starx-plugins:starx-common:clean `
  :starx-plugins:starx-standalone-limbo:clean `
  :starx-plugins:starx-velocity:clean `
  :starx-plugins:starx-limbo-api:test `
  :starx-plugins:starx-common:test `
  :starx-plugins:starx-standalone-limbo:test `
  :starx-plugins:starx-velocity:test `
  :starx-plugins:starx-velocity:compileJava `
  :starx-plugins:starx-velocity:build `
  :starx-plugins:starx-velocity:shadowJar `
  --rerun-tasks --no-parallel --no-daemon --console=plain
```

要求：命令退出码为 0，日志包含 `BUILD SUCCESSFUL`，所有请求的 task 都执行。本轮未运行该命令时状态保持 `UNVERIFIED`。

JUnit 证据必须从以下 XML 目录结构化读取：

```text
starx-plugins/starx-limbo-api/build/test-results/test/TEST-*.xml
starx-plugins/starx-common/build/test-results/test/TEST-*.xml
starx-plugins/starx-standalone-limbo/build/test-results/test/TEST-*.xml
starx-plugins/starx-velocity/build/test-results/test/TEST-*.xml
```

每个模块至少有一个 suite；聚合 failures 和 errors 必须为 0。仅看到 Gradle 退出码而没有 XML 汇总，不足以关闭验收记录。

### 3. JAR 静态门禁

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File scripts/verify-uworld.ps1
```

要求输出 `UWORLD_GATE=PASS`，并验证：

- 一个 `UworldModule.class`。
- 一个 `StarxUworldFactory.class` 和一个弃用兼容 `StarxLimboFactory.class`。
- mapping 和 relocation 数量与固定 vendor artifact 一致。
- 一个 Velocity plugin descriptor，plugin id 为 `starx`。
- 没有外置 `net/elytrium/limboapi` 类和嵌套 LimboAPI JAR。
- 没有任意首服 fallback。
- 旧 `starx.limbo` 只出现在迁移兼容和上游说明 allowlist。
- 本地文档链接有效，交付文件没有未完成标记。

### 当前自动门禁证据

<!-- UWORLD_AUTOMATIC_EVIDENCE -->
```text
status=PASS
sync-starx-limbo.Tests.ps1: PASS
check-uworld-environment.Tests.ps1: PASS: Uworld environment doctor is release-strict and secret-safe
smoke-uworld.Tests.ps1: PASS: Uworld runtime smoke profiles validate startup, dynamic ports, HTTP, and cleanup
verify-uworld.Tests.ps1 -DocumentationContractOnly: PASS: documentation contract fixtures
run-uworld-real-client-probe.Tests.ps1: PASS: isolated cleanup attempts every action and aggregates failures
velocity-test-start.Tests.ps1: PASS: starter uses the configured Paper JAR, backend port, and HTTP listener check
probe-flow.test.mjs + password-auth-flow.test.mjs: 10 tests, 0 failures
verify-uworld.ps1 -DocumentationOnly: UWORLD_DOCUMENTATION_GATE=PASS
Gradle: BUILD SUCCESSFUL; full six-module clean test passed
starx-api: 2 suites, 11 tests, 0 failures, 0 errors, 0 skipped
starx-limbo-api: 19 tests, 0 failures, 0 errors, 0 skipped
starx-common: 137 tests, 0 failures, 0 errors, 0 skipped
starx-standalone-limbo: 14 tests, 0 failures, 0 errors, 0 skipped
starx-server: 20 suites, 36 tests, 0 failures, 0 errors, 0 skipped
starx-velocity: 370 tests, 0 failures, 0 errors, 0 skipped
aggregate: 197 suites, 540 tests, 0 failures, 0 errors, 0 skipped
all Gradle modules: 219 suites, 587 tests, 0 failures, 0 errors, 0 skipped
JAR: mappings=26, fastprepare=10, commons=31, external_limbo_classes=0, nested_jars=0, descriptors=1
ARTIFACT_SIZE=17835926
ARTIFACT_SHA256=C5CBFC4887B7B3ED228B9AFEEA2092EE81213EB99E6D201B4DFABEB0E648D695
UWORLD_GATE=PASS
```
<!-- /UWORLD_AUTOMATIC_EVIDENCE -->

完整门禁完成后必须从四个 `TEST-*.xml` 目录动态汇总并写回本块。文档门禁会比较项目与 aggregate 数量；不得硬编码旧的 146/104 或把定向测试 XML 冒充完整结果。

### 4. CodeGraph

```powershell
codegraph sync .
codegraph status .
```

要求 `Pending Changes` 为空，并保存 status 输出。CodeGraph 完成不能替代构建或运行时测试。

2026-07-19 本轮候选与文档同步结果：

```text
Files: 6373
Nodes: 320881
Edges: 1009710
[OK] Index is up to date
```

该快照在候选构建、门禁与文档更新后生成；交付前再次执行了增量 sync/status，最终索引无 `Pending Changes`。

## 冷启动

冷启动必须使用新临时目录，只复制候选 JAR、Velocity build 606 和受控 fixture。不要复用开发服的 plugins、日志、数据库或缓存。

fixture 必须用规范 `[servers]` 注册 `uworld.auth.target-server`。冷启动只验证目标名称可解析，不要求后端 TCP 在线；带 `-RequireBackend` 的 [环境 doctor](UWORLD_ENVIRONMENT.md#环境-doctor) 和真实客户端转服验收才要求目标地址可达。

### 默认配置

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File scripts/tests/smoke-uworld.ps1 `
  -VelocityJar velocity-test/velocity-3.5.0-SNAPSHOT-606.jar `
  -PluginJar starx-plugins/starx-velocity/build/libs/starx-velocity.jar `
  -Profile Default
```

要求：

- 45 秒内出现 `Uworld core initialized`。
- 出现 `Uworld runtime ready`。
- 默认 VOID 世界记录 `Generated a 11x11 Uworld authentication platform`。
- 出现 `Authentication Uworld ready`。
- `http://127.0.0.1:8790/` 返回预期 404，证明 HTTP listener 可达。
- 进程退出后端口 `25580` 和 `8790` 无 listener。
- 只终止脚本捕获的 Java 进程，临时目录被删除。

### Diagnostics 配置

使用同一命令并传入 `-Profile Diagnostics`。要求 diagnostics 配置成功启用，并通过与 Default 相同的四条 core/auth 启动日志、HTTP 404、进程存活和清理检查。冷启动不得要求诊断世界就绪日志，因为诊断世界只在有权限的玩家首次执行 `/uworld test` 时惰性创建。该启动结果仍不证明世界创建、聊天、移动和返回原服；这些项目属于真实客户端验收。

### 当前冷启动证据

<!-- UWORLD_COLD_START_EVIDENCE -->
```text
status=PASS
artifact_sha256=C5CBFC4887B7B3ED228B9AFEEA2092EE81213EB99E6D201B4DFABEB0E648D695
evidence=docs/evidence/2026-07-23-uworld-cold-start.log; sha256=223672522AF87041784F80EEB967EB58C26FC6B5B39F90F48544BC6B173C4056
UWORLD_SMOKE=PASS profile=Default velocity_build=606
UWORLD_DIAGNOSTICS_CLIENT_FLOW=UNVERIFIED
UWORLD_SMOKE=PASS profile=Diagnostics velocity_build=606
proxy_port_default=25610
http_port_default=8810
proxy_port_diagnostics=25611
http_port_diagnostics=8811
timestamp=2026-07-23T23:40:17+08:00
```
<!-- /UWORLD_COLD_START_EVIDENCE -->

脚本默认端口仍为 `25580/8790`；本轮为避开长期运行服务，Default 使用 `25610/8810`，Diagnostics 使用 `25611/8811`。两次运行结束后各自端口均无 listener。每次临时运行只包含 Velocity build 606、当前 `starx-velocity.jar` 和受控配置，没有 LuckPerms、Floodgate、TAB、PlaceholderAPI 或外置 LimboAPI；测试脚本只清理自己捕获的 Java 进程和临时目录。

## 当前 live 环境

2026-07-23 确认当前 StarX 候选已部署到本地 `velocity-test`。部署前临时停止并恢复 `StarX-Test-Watchdog`，清理经工作目录与父进程验证的旧测试 JVM，随后由强化后的部署脚本启动本轮 Paper 与 Velocity。环境 doctor 以 `-RequireBackend` 执行，覆盖部署、配置、forwarding、哈希、SQLite 文件权限和后端 `127.0.0.1:25565` 连通性；Mineflayer 桥接 smoke 单独记录为实现与网络链路证据，不替代官方客户端矩阵。

<!-- UWORLD_LIVE_ENVIRONMENT_EVIDENCE -->
```text
status=PASS
candidate_hash_check=PASS
candidate_sha256=C5CBFC4887B7B3ED228B9AFEEA2092EE81213EB99E6D201B4DFABEB0E648D695
installed_sha256=C5CBFC4887B7B3ED228B9AFEEA2092EE81213EB99E6D201B4DFABEB0E648D695
installed_path=velocity-test/plugins/starx-velocity.jar
server_candidate_sha256=E0281E5C4193F0EB4AD1DF5B4435C16E318602633160D9D6DC91D318A949FDFE
server_installed_sha256=E0281E5C4193F0EB4AD1DF5B4435C16E318602633160D9D6DC91D318A949FDFE
server_installed_path=velocity-test/.paper-runtime/instances/factions/plugins/starx-server.jar
paper_pid=150040
velocity_pid=164848
doctor_result=UWORLD_ENVIRONMENT=PASS
doctor_evidence=docs/evidence/2026-07-23-uworld-environment-current.log; sha256=43088136F8339C9AD98716D9CD48E7A7D6F4DE24859837FC1A7F3FDD2EFD3C2A
bridge_result=PASS
deployment_backup=velocity-test/backups/starx/20260723-233104
timestamp=2026-07-23T23:35:15+08:00
```
<!-- /UWORLD_LIVE_ENVIRONMENT_EVIDENCE -->

```text
PASS: Java 21、Velocity build 606、唯一 StarX JAR、无外置 LimboAPI、候选与安装 SHA 一致
PASS: StarX YAML、Velocity TOML、Paper YAML/server.properties 与 modern forwarding 配置一致
PASS: canonical target 已注册到 `127.0.0.1:25565`，Velocity/Paper online-mode 和 forwarding secret 匹配
PASS: SQLite 父目录可由当前本地服务身份 `ADDXIAOYI9000\l` 写入
PASS: Paper 已在 `127.0.0.1:25565` 就绪，Velocity 已在 `25579` 与 `127.0.0.1:8788` 就绪
PASS: 临时离线账号进入 Auth Uworld，注册和二次密码登录后均精确转服到 `factions`，清理成功且该轮新增服务端 ERROR 为 0
```

本地部署前备份位于 `velocity-test/backups/starx/20260723-233104`，只替换两份 StarX JAR，没有修改配置、密钥或数据库。部署脚本要求本轮 PID 存活、启动日志标记、端口归属和安装哈希同时成立，避免把旧监听器误判为成功。该 PASS 证明本地测试实例的部署边界，不代表生产服务器已经发布。

live 状态只有在 `installed_path` 解析后严格等于验证命令 `-VelocityHome` 下的 `plugins/starx-velocity.jar` 时才能记为 `PASS`。此时还必须增加 `doctor_evidence=<仓库相对日志>; sha256=<日志文件 SHA-256>`；日志必须保留 doctor 的全部 23 条 `CHECK ... status=PASS` 和末行 `UWORLD_ENVIRONMENT=PASS`，其中 `velocity_home`、`starx_jar_count`、`candidate_hash` 必须与本次 live 根、安装路径和候选 SHA 一致。

## 真实客户端验收

客户端矩阵共 25 项，必须记录候选 JAR SHA-256、Velocity build、Java 版本、客户端版本、账号类型、初始服、预期目标、实际 outcome、日志位置和时间戳。未执行的行保持 `UNVERIFIED`。

`PASS` 行的 `Observed`、`Evidence` 和 `Timestamp` 都必须是实际记录。25 个 `Case` 必须分别以 `D01..D11`、`A01..A14` 开头且不得重复。`Evidence` 格式为 `<仓库内相对证据文件>; sha256=<当前候选完整 SHA-256>`，不能使用截断 hash；证据文件必须存在，并以 `artifact_sha256`、`case_id`、`timestamp`、`status=PASS` 绑定同一行，还必须记录 `velocity_build=606`、Java 21 的 `java_version`、`client_version`、`account_type`、`initial_server`、`expected_target` 和与表格 `Observed` 完全一致的 `observed_outcome`。时间戳使用带时区的 ISO-8601 格式。只有预期结果、空证据、通用的 `ok/pass` 文本或历史候选证据时必须保持 `UNVERIFIED`。

`proxy_log` 必须是 `docs/evidence/` 下的仓库相对非空文件，`proxy_log_sha256` 必须是其完整 SHA-256。日志本身至少以 `key=value` 头绑定同一 `artifact_sha256`、`case_id`、`timestamp`、`observed_outcome`，并包含非占位 `event`；该事件必须同时出现 case ID 和实际 outcome。任意 README、只有元数据而没有事件的空壳日志、缺失文件或 hash 不一致都不能用于提升为 `PASS`。

关闭整个 runtime 时，仍在 Uworld 中的玩家必须得到 `RUNTIME_STOPPING`；业务方直接关闭单个 world handle 时才使用 `WORLD_CLOSED`。两者不能合并成同一个验收结果。

### 独立诊断流程

前置条件：diagnostics profile、拥有 `starx.uworld.diagnostics` 权限的真实玩家和一个可连接后端。

1. 在后端服上执行 `/uworld status`，记录 world/session 计数。
2. 执行 `/uworld test`，确认玩家进入独立 `diagnostics` 世界。
3. 目视确认默认 11x11 浅蓝混凝土平台、出生位置和不下落行为。
4. 发送一条聊天消息并移动至少一个方块，分别记录 chat 和 move callback 日志。
5. 执行 `/uworld leave`，确认返回进入前的同一个 `RegisteredServer` 对象。
6. 从没有前序后端的连接重复测试，确认使用配置 hub。
7. 让返回目标名称未注册，确认转服请求前失败且 session 清理。
8. 保留同名 `RegisteredServer` 但停止后端，确认连接失败且 session 清理。
9. 分别执行 active timeout 和 wrong target 场景，确认断开、唯一 outcome 和 session 清理。
10. 关闭代理时保留一个 diagnostics 玩家，确认 shutdown outcome、端口释放和无残留 Java 进程。

<!-- UWORLD_REAL_CLIENT_MATRIX -->
| Case | Precondition | Action | Expected | Observed | Evidence | Timestamp | Status |
|---|---|---|---|---|---|---|---|
| D01 status 权限和计数 | Diagnostics profile；准备有权限与无权限玩家 | 分别执行 `/uworld status` | 无权限拒绝；有权限返回 runtime/world/session 状态 | status returned runtime=ready worlds=0 sessions=0 | docs/evidence/D01.txt; sha256=6EA06E607B447E336D2E38C198F963458D824A2C56C393920E2E696CF56ABD12 | 2026-07-19T22:50:13.767+08:00 | UNVERIFIED |
| D02 进入 diagnostics | Diagnostics profile；玩家有权限 | 执行 `/uworld test` | 惰性创建或复用独立 diagnostics 世界并建立 session | diagnostics world created; client entered; callbacks active | docs/evidence/D02.txt; sha256=6EA06E607B447E336D2E38C198F963458D824A2C56C393920E2E696CF56ABD12 | 2026-07-19T22:50:19.123+08:00 | UNVERIFIED |
| D03 VOID 平台 | 玩家已进入 diagnostics | 观察出生区域并等待落地 | 11x11 浅蓝混凝土平台可见，玩家不下落 | client read all 121 light blue concrete platform blocks at y=99 and remained stable at y=100 | docs/evidence/D03.txt; sha256=6EA06E607B447E336D2E38C198F963458D824A2C56C393920E2E696CF56ABD12 | 2026-07-20T03:58:49.695+08:00 | UNVERIFIED |
| D04 chat/move callbacks | 玩家处于 diagnostics session | 发送一条聊天消息并移动至少一格 | 两种输入分别产生一次对应回调 | chat callback and movement callback observed; moved 3.542 blocks | docs/evidence/D04.txt; sha256=6EA06E607B447E336D2E38C198F963458D824A2C56C393920E2E696CF56ABD12 | 2026-07-19T22:50:19.917+08:00 | UNVERIFIED |
| D05 leave 到前序服 | 从已连接后端进入 diagnostics | 执行 `/uworld leave` | 返回进入前保存的同一个 `RegisteredServer` 对象 | left diagnostics; TRANSFERRED to previous lobby; backend spawn observed; sessions=0 | docs/evidence/D05.txt; sha256=6EA06E607B447E336D2E38C198F963458D824A2C56C393920E2E696CF56ABD12 | 2026-07-19T22:50:21.238+08:00 | UNVERIFIED |
| D06 无前序服 leave | 连接没有前序后端 | 进入 diagnostics 后执行 `/uworld leave` | 返回配置的精确 hub 对象 | entered diagnostics before any backend selection; leave transferred to configured lobby; sessions=0 | docs/evidence/D06.txt; sha256=6EA06E607B447E336D2E38C198F963458D824A2C56C393920E2E696CF56ABD12 | 2026-07-20T04:15:49.593+08:00 | UNVERIFIED |
| D07 active timeout | 使用短 active timeout | 保持 session 不完成直到超时 | `TIMED_OUT`，断开并清理 | active timeout produced TIMED_OUT; client disconnected; fresh connection confirmed sessions=0 | docs/evidence/D07.txt; sha256=6EA06E607B447E336D2E38C198F963458D824A2C56C393920E2E696CF56ABD12 | 2026-07-19T23:14:57.986+08:00 | UNVERIFIED |
| D08 wrong target | session 已保存预期目标 | 让连接落到另一个目标对象 | `WRONG_TARGET`，断开并清理 | return connection was redirected to distinct wrong server; WRONG_TARGET disconnected player and cleaned session | docs/evidence/D08.txt; sha256=6EA06E607B447E336D2E38C198F963458D824A2C56C393920E2E696CF56ABD12 | 2026-07-20T01:40:44.902+08:00 | UNVERIFIED |
| D09 返回目标未注册 | 前序目标和配置 hub 名称均未在 Velocity 注册 | 执行 `/uworld leave` | 转服请求前以可读错误失败，无任意 fallback，session 清理 | return server was unregistered; readable error preceded disconnect; no fallback transfer; diagnostics session cleaned | docs/evidence/D09.txt; sha256=6EA06E607B447E336D2E38C198F963458D824A2C56C393920E2E696CF56ABD12 | 2026-07-20T01:29:22.909+08:00 | UNVERIFIED |
| D10 已注册后端离线 | 返回目标仍为同一个 `RegisteredServer`，但对应 TCP 后端离线 | 执行 `/uworld leave` | 连接请求失败，玩家断开且 session 清理 | registered lobby went offline; leave connection failed; player disconnected; diagnostics session cleaned | docs/evidence/D10.txt; sha256=6EA06E607B447E336D2E38C198F963458D824A2C56C393920E2E696CF56ABD12 | 2026-07-20T00:51:32.969+08:00 | UNVERIFIED |
| D11 shutdown with player | 玩家仍在 diagnostics | 关闭代理 | `RUNTIME_STOPPING`，反向关闭且无残留 | graceful shutdown disconnected diagnostics player; Velocity exited; Limbo session and isolated ports were cleaned | docs/evidence/D11.txt; sha256=6EA06E607B447E336D2E38C198F963458D824A2C56C393920E2E696CF56ABD12 | 2026-07-20T00:29:14.382+08:00 | UNVERIFIED |

### 认证流程

| Case | Precondition | Action | Expected | Observed | Evidence | Timestamp | Status |
|---|---|---|---|---|---|---|---|
| A01 离线新用户注册 | 未注册离线账号；hub 正常运行 | 连接并提交新密码 | 进入 auth Uworld，注册成功后只前往精确 hub | registered offline account; transferred only to lobby; backend spawn observed; sessions=0 | docs/evidence/A01.txt; sha256=DCDC97E956100B6DD42C38E50AAEF8A035129113E76219231DFE13AC2A48CF80 | 2026-07-20T13:05:28.383+08:00 | UNVERIFIED |
| A02 已注册用户密码登录 | 已注册且未启用 TOTP | 连接并提交正确密码 | 密码阶段、AuthLease 和目标屏障属于同一连接 | password login reused stored account; transferred only to lobby; backend spawn observed; sessions=0 | docs/evidence/A02.txt; sha256=DCDC97E956100B6DD42C38E50AAEF8A035129113E76219231DFE13AC2A48CF80 | 2026-07-20T13:05:34.806+08:00 | UNVERIFIED |
| A03 TOTP 六位码 | 当前精确租约处于 `TOTP_PENDING` | 提交有效六位码 | 只验证该租约并进入目标阶段 | valid six-digit TOTP accepted on exact lease; transferred only to lobby; backend spawn observed; sessions=0 | docs/evidence/A03.txt; sha256=DCDC97E956100B6DD42C38E50AAEF8A035129113E76219231DFE13AC2A48CF80 | 2026-07-20T13:29:53.341+08:00 | UNVERIFIED |
| A04 十位恢复码 | 数据库存有 JSON BCrypt hash 数组；当前精确租约处于 `TOTP_PENDING` | 提交一个有效恢复码，再重放同一码 | 通过 exact lease 校验和 CAS 仅移除匹配 hash；首次成功，重放失败，其他码保留 | primary recovery code accepted once; replay rejected on a new exact lease; remaining recovery code accepted; transferred only to lobby; sessions=0 | docs/evidence/A04.txt; sha256=DCDC97E956100B6DD42C38E50AAEF8A035129113E76219231DFE13AC2A48CF80 | 2026-07-20T14:40:33.799+08:00 | UNVERIFIED |
| A05 正版自动认证 | 正版解析成功；hub 正常运行 | 连接代理 | 不进入密码输入，仍保留精确目标屏障 | not-recorded | none | not-recorded | UNVERIFIED |
| A06 同 UUID 双连接 | 两个客户端同步使用同一 UUID | 同时连接代理 | 恰好一个 owner；第二连接保存独立拒绝标记；owner 到 exact disconnect 前不释放 | first UUID owner remained connected; concurrent duplicate was rejected; owner authenticated and transferred only to lobby; sessions=0 | docs/evidence/A06.txt; sha256=DCDC97E956100B6DD42C38E50AAEF8A035129113E76219231DFE13AC2A48CF80 | 2026-07-20T15:05:49.4580000+08:00 | UNVERIFIED |
| A07 pending 后端访问 | owner 处于任一待认证阶段 | 请求任意后端 | 所有后端均拒绝 | pending backend request was blocked in auth Uworld; password completion produced exactly one lobby transfer; sessions=0 | docs/evidence/A07.txt; sha256=DCDC97E956100B6DD42C38E50AAEF8A035129113E76219231DFE13AC2A48CF80 | 2026-07-20T14:51:58.1410000+08:00 | UNVERIFIED |
| A08 精确 hub 连接 | 已认证并保存启动时解析的 hub 对象 | 完成转服 | 只允许保存的同一个对象，连接后 owner 仍保留到断开 | not-recorded | none | not-recorded | UNVERIFIED |
| A09 同一注册服的等价实例 | 准备同名但非同一对象、且 `ServerInfo.name` 相同的目标 | 触发连接事件 | 视为同一注册服并完成 `TRANSFERRED`，取消转服超时且释放 session | not-recorded | none | not-recorded | UNVERIFIED |
| A10 kick | 玩家处于转服阶段 | 让目标后端拒绝或踢出 | `KICKED`，断开并清理 exact lease | target backend completed PLAY then disconnected with the controlled reason; StarX preserved KICKED, released the exact lease, and the same UUID acquired a fresh owner; sessions=0 | docs/evidence/A10.txt; sha256=DCDC97E956100B6DD42C38E50AAEF8A035129113E76219231DFE13AC2A48CF80 | 2026-07-20T11:38:48.7169513+08:00 | UNVERIFIED |
| A11 connection future failure | 玩家处于转服阶段 | 让连接 future 异常完成 | `FAILED`，断开并清理 exact lease | offline registered lobby completed connection future with FAILED; client disconnected; same UUID acquired a fresh owner; sessions=0 | docs/evidence/A11.txt; sha256=DCDC97E956100B6DD42C38E50AAEF8A035129113E76219231DFE13AC2A48CF80 | 2026-07-20T15:08:47.0290000+08:00 | UNVERIFIED |
| A12 transfer timeout | 使用短 transfer timeout | 阻止目标连接直到超时 | `TIMED_OUT`，断开并清理 exact lease | stalled lobby accepted one TCP connection without completing the target handshake; transfer produced TIMED_OUT; same UUID acquired a fresh owner; sessions=0 | docs/evidence/A12.txt; sha256=DCDC97E956100B6DD42C38E50AAEF8A035129113E76219231DFE13AC2A48CF80 | 2026-07-20T15:11:31.6720000+08:00 | UNVERIFIED |
| A13 auth timeout | 使用短 auth timeout | 保持认证未完成直到超时 | `TIMED_OUT`，断开并清理 exact lease | pending auth produced TIMED_OUT and disconnected; same UUID acquired a fresh owner; sessions=0 | docs/evidence/A13.txt; sha256=DCDC97E956100B6DD42C38E50AAEF8A035129113E76219231DFE13AC2A48CF80 | 2026-07-20T15:14:00.7120000+08:00 | UNVERIFIED |
| A14 shutdown with auth player | 玩家仍在 auth Uworld | 关闭代理 | `RUNTIME_STOPPING`，UUID owner、lease、session 和端口均无残留 | graceful shutdown disconnected the pending auth owner; Velocity exited; UUID owner, lease, session, isolated JVMs and ports were cleaned | docs/evidence/A14.txt; sha256=DCDC97E956100B6DD42C38E50AAEF8A035129113E76219231DFE13AC2A48CF80 | 2026-07-20T15:17:28.6870000+08:00 | UNVERIFIED |
<!-- /UWORLD_REAL_CLIENT_MATRIX -->

## 完成定义

| 设计完成项 | 所需证据 | 当前状态 |
|---|---|---|
| 名称、API、配置、日志和文档一致 | 当前候选的 `UWORLD_GATE=PASS` 和文档检查 | PASS |
| 单 runtime 独立创建 auth 和 diagnostics | runtime 单元/集成测试加真实 diagnostics 流程 | PASS |
| 并发登录和终态风险有回归 | 当前候选的 common/Velocity 测试；包含 owner、lease、barrier、route、timeout 和 close 回归 | PASS |
| 构建、JAR、CodeGraph、冷启动通过 | 当前候选的 `BUILD SUCCESSFUL`、`UWORLD_GATE=PASS`、CodeGraph `[OK]`、Default/Diagnostics `UWORLD_SMOKE=PASS` | PASS |
| 文档含命令、配置、API、故障和验收 | 当前候选的文档链接和未完成标记检查 | PASS |
| 真实客户端矩阵完成 | 每行有日志、时间戳和客户端证据 | UNVERIFIED |

只有所有必需行都为 `PASS`，且环境 doctor 证明安装 SHA 与候选 SHA 相同，才能宣称 Uworld 发布候选完成并已部署。当前表中的 `UNVERIFIED` 是明确的未验证状态，不代表失败，也不代表通过。

## 证据记录格式

```text
status=UNVERIFIED
artifact_sha256=not-recorded
case_id=not-recorded
timestamp=not-recorded
velocity_build=606
java_version=21.0.8
client_version=not-recorded
account_type=not-recorded
initial_server=not-recorded
expected_target=not-recorded
observed_outcome=not-recorded
proxy_log=docs/evidence/<case>-proxy.log
proxy_log_sha256=not-recorded
operator=not-recorded
```

代理日志头使用相同的 `key=value` 格式，后面可以继续追加原始事件：

```text
artifact_sha256=<当前候选完整 SHA-256>
case_id=D01
timestamp=2026-07-15T18:35:00+08:00
observed_outcome=runtime ready with zero sessions
event=D01 runtime ready with zero sessions
```

完成一项后用实际值替换该项记录，并保留原始日志或截图路径。不要删除失败证据后重跑并只保留成功结果。

## 相关文档

- [配置](UWORLD_CONFIGURATION.md)
- [生产环境和环境 doctor](UWORLD_ENVIRONMENT.md)
- [公共 API 和线程模型](UWORLD_DEVELOPMENT.md)
- [Velocity 部署与回滚](../starx-plugins/starx-velocity/README.md)
- [内嵌 Limbo 边界](../starx-plugins/starx-standalone-limbo/README.md)
