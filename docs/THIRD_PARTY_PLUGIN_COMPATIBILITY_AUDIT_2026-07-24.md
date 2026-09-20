# StarX 第三方插件兼容审计 — 2026-07-24

## 结论

当前 StarX 候选已通过本地现有第三方插件精确版本矩阵：

```text
STARX_LUCKPERMS_5_5_60=PASS
STARX_TAB_6_0_2=PASS
STARX_FLOODGATE_2_2_5_B138_API=PASS
STARX_PLACEHOLDERAPI_2_12_2=PASS
STARX_SKINSRESTORER_15_12_4_API=PASS
CURRENT_EXACT_VERSION_MATRIX=PASS
```

该结论只覆盖下表中的确切版本和哈希，不代表全部历史版本、未来版本或真实玩家客户端链路均已通过。

## 验收矩阵

| 平台 | 第三方插件 | 精确版本 | StarX 接线 | 结果 |
|---|---|---:|---|---:|
| Velocity | LuckPerms | 5.5.60 | `qq-bound`、`discord-bound` 上下文计算器 | PASS |
| Velocity | TAB | 6.0.2 | 注册 22 个 StarX 变量 | PASS |
| Velocity | Floodgate | 2.2.5-b138 | 可信基岩身份提供器 | PASS |
| Paper 1.21.11 | PlaceholderAPI | 2.12.2 | 注册 `starx` 内部扩展 | PASS |
| Paper 1.21.11 | SkinsRestorer | 15.12.4 | 子服皮肤存储与查询桥接 | PASS |

## Velocity 验收

运行目录：

```text
tmp/starx-velocity-integrations-20260724-185542-139
```

运行时和候选：

```text
Velocity 3.5.0-SNAPSHOT build 606
SHA-256 f763b42b951892c62ecdee2e532a7788c9929a4468068227daea71d84f2b39f2

starx-velocity.jar
SHA-256 a4ce5bdff9868bc96af682c9b88c60dd917b4f2d5e64de1b83f91977d961a822
```

第三方 JAR：

```text
LuckPerms-Velocity 5.5.60
SHA-256 25d05c9d08e5d4e0d47fcb70ad33801b72bf8e6c5da72706095e4e8f3aaf5f82

TAB 6.0.2
SHA-256 7f4a0f5bd7408d894361a1c4921a8dc437e7cd18d5058a76ca833bfdfe567b68

Floodgate Velocity 2.2.5-b138
SHA-256 524744c5c3de67df4b84adc80babbf5d9f00412c1a593f74a8ae6e6dde92c06f
```

StarX 运行时实际输出了三个成功标记：

```text
已解锁 Floodgate：可信基岩玩家自动认证
已解锁 TAB：注册 22 个 StarX 变量
已解锁 LuckPerms：qq-bound / discord-bound 权限上下文
```

`25696`、`8796`、`25697` 在验收结束后全部释放。

### TAB 限制

TAB 6.0.2 在 Velocity 上提示未安装可选的 VelocityScoreboardAPI，因此以下 TAB 自身功能不可用：

- scoreboard teams
- below-name objective
- player-list objective
- scoreboard

该提示不影响本轮已验证的 StarX 变量注册，但不能将 TAB 的全部计分板功能标记为通过。

### Floodgate 限制

本轮验证了 Floodgate 插件加载、API 发现和 StarX 可信身份提供器接线。未使用真实 Bedrock 客户端登录，因此：

```text
REAL_BEDROCK_LOGIN=UNVERIFIED
```

## Paper 验收

运行目录：

```text
tmp/starx-paper-integrations-20260724-190349-262
```

运行时和候选：

```text
Paper 1.21.11
SHA-256 5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba

starx-server.jar
SHA-256 fc76752fb4dad781013e0415b3843b335f2c3ae875a9db732257da3ee2031342
```

第三方 JAR：

```text
PlaceholderAPI 2.12.2
SHA-256 ff76af20c7acf327ff2a28fb2dbd6694e3f946503e72635a5f7b6cb2e64fc014

SkinsRestorer 15.12.4
SHA-256 56fed7d9fa5862356851307cdb20707adb5d43f0dd6451a0225ebbd03e8d04a0
```

实际运行证据：

```text
PlaceholderAPI: Successfully registered internal expansion: starx [0.1.4-SNAPSHOT]
已解锁 PlaceholderAPI：starx_* 子服变量
已解锁 SkinsRestorer：子服皮肤数据桥接可用
StarX backend ready: node=paper-integrations platform=PAPER bridge=true
```

针对下列失败类型的扫描结果为零：

```text
PlaceholderAPI rejected the StarX expansion
SkinsRestorer API was found but could not be initialized
Error occurred while enabling StarXServer
NoClassDefFoundError
UnsupportedClassVersionError
OutOfMemoryError
```

验收结束后 `25698` 已释放。

### SkinsRestorer 限制

本轮证明了 SkinsRestorer API、玩家存储和皮肤存储可由 StarXServer 发现并接入，但未连接真实玩家客户端确认最终纹理渲染：

```text
REAL_CLIENT_SKIN_RENDER=UNVERIFIED
```

SkinsRestorer 在启动时检测到 15.12.5 并尝试执行更新检查。验收结束后实际测试 JAR 仍为 15.12.4，哈希未改变。本次结果仍绑定 15.12.4，不能据此认定 15.12.5 已兼容。

## 结论边界

本轮是隔离本地冷启动和 API 接线验收，没有修改生产服务器或真实玩家数据。以下项目仍未验证：

```text
REAL_BEDROCK_LOGIN=UNVERIFIED
REAL_CLIENT_SKIN_RENDER=UNVERIFIED
MULTI_VERSION_COMPATIBILITY=UNVERIFIED
```

当前可以据实声明：上述五个精确第三方插件版本与当前 StarX 候选完成冷启动和运行时 API 接线，结果为 PASS。
