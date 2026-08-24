# StarX 当前候选第三方插件与玩家矩阵 — 2026-07-24

## 结论

在构建产物稳定后，当前磁盘候选完成了 Velocity 第三方 API 接线以及 Paper/Folia 双平台真实协议玩家交互：

```text
CURRENT_CANDIDATE_MATRIX=PASS
STARX_SERVER_DUAL_PLATFORM_INTEGRATIONS=PASS
```

当前候选哈希：

```text
starx-velocity.jar
SHA-256 d87c32b6166ad5a3b2e8cec518e47af551c5d9174379d38c157e9d7b2c5987be

starx-server.jar
SHA-256 7f43791274a1f6edb3eda56c6cce5eef0905cd30fbd86d635eedd11a8ae4e550
```

## Velocity 当前候选

运行目录：

```text
tmp/starx-velocity-integrations-20260724-194248-523
```

精确矩阵：

| 运行时/插件 | 版本 | 结果 |
|---|---:|---:|
| Velocity | 3.5.0-SNAPSHOT build 606 | PASS |
| LuckPerms | 5.5.60 | PASS |
| TAB | 6.0.2 | PASS |
| Floodgate | 2.2.5-b138 | API PASS |

结果：

```text
STARX_LUCKPERMS_5_5_60=PASS
STARX_TAB_6_0_2=PASS
STARX_FLOODGATE_2_2_5_B138_API=PASS
```

测试结束后 `25696`、`8796`、`25697` 全部释放。

真实 Bedrock 登录仍未执行：

```text
REAL_BEDROCK_LOGIN=UNVERIFIED
```

## Paper/Folia 当前候选

运行目录：

```text
tmp/starx-server-integrations-20260724-113546
```

精确矩阵：

| 平台 | PlaceholderAPI | SkinsRestorer | 协议玩家 | 结果 |
|---|---:|---:|---:|---:|
| Paper 1.21.11 | 2.12.2 | 15.12.4 | Mineflayer 1.21.11 | PASS |
| Folia 1.21.11-14 | 2.12.2 | 15.12.4 | Mineflayer 1.21.11 | PASS |

两平台均完成：

1. StarXServer 冷启动和平台识别。
2. PlaceholderAPI `starx` 扩展注册。
3. SkinsRestorer 存储 API 接线。
4. RCON 状态、能力和执行模型查询。
5. 将隔离测试玩家授权为 operator。
6. 玩家通过 Minecraft 协议实际登录。
7. 玩家执行 `/starxserver status`。
8. 玩家执行 `%starx_platform%` 解析。
9. 玩家执行 StarX 皮肤诊断命令。
10. 正常禁用 StarXServer 并释放游戏/RCON 端口。

### Paper 玩家证据

```text
Platform: PAPER
Execution: main-thread
PLACEHOLDER_RESULT paper
SKIN_RESULT found=false ...
STARX_SERVER_INTEGRATION_PLAYER PASS
```

### Folia 玩家证据

```text
Platform: FOLIA
Execution: regionized
PLACEHOLDER_RESULT folia
SKIN_RESULT found=false ...
STARX_SERVER_INTEGRATION_PLAYER PASS
```

Paper 清单和 Folia 清单均为：

```text
acceptance=PASS
```

测试结束后以下端口全部释放：

```text
25702
25703
25704
25705
```

## 工装修复

双平台编排器在验收过程中修复了两个仅影响测试判定的关闭竞态：

- RCON `stop` 后的 `ECONNRESET/EPIPE` 被视为正常服务器关闭。
- 在发送 `stop` 前预注册 Java `exit` Promise，防止错过快速退出事件。

这些缺陷没有造成 StarXServer、PlaceholderAPI 或 SkinsRestorer 的运行失败；修复后同一候选完成全矩阵 PASS。

## 结论边界

本轮已证明真实 Minecraft 协议玩家能够在 Paper 和 Folia 上使用 StarX 状态命令、PlaceholderAPI 变量和结构化皮肤诊断。诊断结果为 `found=false`，因此没有实际纹理可供客户端渲染，不能将真实皮肤纹理显示标记为通过：

```text
REAL_CLIENT_SKIN_TEXTURE_RENDER=UNVERIFIED
MULTI_VERSION_COMPATIBILITY=UNVERIFIED
```

当前可以据实声明：当前 StarX 候选与上述精确第三方插件版本在 Velocity、Paper 和 Folia 上完成 API 接线；Paper/Folia 还完成了真实协议玩家交互，结果为 PASS。
