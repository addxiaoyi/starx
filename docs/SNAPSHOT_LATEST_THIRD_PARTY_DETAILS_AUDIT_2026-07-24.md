# StarX 快照最新版第三方兼容细节审计 — 2026-07-24

## 结论

本轮将会被并行构建覆盖的 `build/libs` 产物固化为不可变候选快照，并在该快照上完成最新版后端插件、Velocity 三项集成以及软依赖缺失场景的运行时验收。

总体结论：**PASS**。

本结论仅适用于下列精确 SHA-256：

- `starx-server.jar`: `f383fab89b24a8e5ceaec759978bb629a8b046ad10f95c5bd624fe141a1f0310`
- `starx-velocity.jar`: `fe02d53d20470d3dfe712f0f4bfa98c37b6bca6447aa721a8539cd4ebeaa8408`

快照目录：

```text
/tmp/candidate-snapshots/20260724-202324-f383-fe02
```

## 最新第三方插件来源与完整性

### PlaceholderAPI 2.12.3

- Modrinth version ID: `pIvQcXW8`
- SHA-512: `f048d55b633fd816c08e2e4472bd54a75fc4d13534682e6e7745408253d2f393706efdc389d12ca2cf28d4dc035a9afdda3eed9ecde51c7e332831391d9b6479`
- SHA-256: `fde03259f5af6938f3c33eeb4d814000a1adabf1d2304ce14970be81f609a437`

### SkinsRestorer 15.12.5

- Modrinth version ID: `wXS6bHiC`
- SHA-512: `7819f6b1e8f8ddb2e86d3d3e54352dd040f381e9a094f8a9c80c7d3273ffd7b1cef6eca7369dcee4b0f5290e7837ef51cee1baeca906b3784f30d7ba2f58b7b4`
- SHA-256: `bf13ffee9bb488141b7ec99603ebc8abac689933d72db15e664feb0b4deefc60`

两个文件均使用 Modrinth API 返回的 SHA-512 完成下载后校验，旧版 `2.12.2/15.12.4` 基线文件未被覆盖。

## Paper/Folia 最新版双平台矩阵

运行目录：

```text
tmp/starx-server-latest-integrations-20260724-124830
```

| 平台 | PlaceholderAPI 2.12.3 | SkinsRestorer 15.12.5 API | 协议玩家 | 平台变量 | 结果 |
|---|---:|---:|---:|---|---:|
| Paper 1.21.11 | PASS | PASS | PASS | `paper` | PASS |
| Folia 1.21.11 | PASS | PASS | PASS | `folia` | PASS |

协议玩家实际执行：

1. Minecraft 1.21.11 协议登录。
2. `/starxserver status`。
3. 平台和执行模型读取。
4. `/papi parse me %starx_platform%`。
5. `/starxserver skin <uuid> <name>`。
6. 玩家退出、RCON 停服、StarXServer 禁用生命周期和端口释放。

Folia 明确返回：

```text
Platform: FOLIA
Execution: regionized
PLACEHOLDER_RESULT folia
```

## Velocity 快照矩阵

运行目录：

```text
tmp/starx-velocity-snapshot-integrations-20260724-205743-380
```

| 集成 | 精确版本 | 结果 |
|---|---|---:|
| LuckPerms | 5.5.60 | PASS |
| TAB | 6.0.2 | PASS |
| Floodgate | 2.2.5-b138 | PASS |

具体接线包括：

- LuckPerms `qq-bound` / `discord-bound` 上下文计算器。
- TAB 22 个 StarX 变量。
- Floodgate 可信基岩身份提供器。

Velocity 运行时为 `3.5.0-SNAPSHOT-b606`，SHA-256 为 `f763b42b951892c62ecdee2e532a7788c9929a4468068227daea71d84f2b39f2`。

## 软依赖降级矩阵

`plugin.yml` 将 PlaceholderAPI 与 SkinsRestorer 声明为 `softdepend`。本轮不再只依赖静态声明，而是执行真实 Paper 冷启动和玩家交互。

运行目录：

```text
tmp/starx-server-optional-dependencies-20260724-130625
```

| 场景 | StarX 启动 | 玩家登录/状态 | 占位符 | 皮肤诊断 | 正常关闭 | 结果 |
|---|---:|---:|---|---:|---:|---:|
| 两者都缺失 | PASS | PASS | 跳过 | PASS，返回 `found=false` | PASS | PASS |
| 仅 PlaceholderAPI 2.12.3 | PASS | PASS | `paper` | PASS，降级返回 `found=false` | PASS | PASS |
| 仅 SkinsRestorer 15.12.5 | PASS | PASS | 不可用且不误注册 | PASS | PASS | PASS |

这证明两个插件确实是可独立缺失的软依赖；StarXServer 不会因类缺失、API 缺失或空解析器而中止启动。

## 工装可靠性修复

本轮修复了三类测试工装竞态：

1. RCON `stop` 后允许服务器主动返回 `ECONNRESET/EPIPE`。
2. Socket 关闭等待在 `close` 或 `error` 任一事件上收敛；非预期错误仍单独抛出。
3. Java 停服前预注册 `exit` Promise，避免快速退出事件丢失造成假超时。

这些是测试编排器缺陷，不是 StarX 或第三方插件缺陷。

## 清理与隔离

最终复核结果：

```text
25696, 8796, 25697 = RELEASED
25702–25705 = RELEASED
25706–25707 = RELEASED
stale_node_java = 0
```

## 限制与未验证项

下列项目仍不能标记为 PASS：

- Mojang 官方 Java GUI 客户端：`UNVERIFIED`
- 真实 Bedrock 客户端登录：`UNVERIFIED`
- 客户端实际皮肤纹理渲染：`UNVERIFIED`
- 全历史版本矩阵：`UNVERIFIED`

Mineflayer 是协议级玩家探针，不等同于 Mojang 官方客户端验收。SkinsRestorer 测试使用不存在皮肤的零 UUID，确认的是 StarX → SkinsRestorer API 调用和结构化降级结果，不代表纹理已经在客户端呈现。

## 最终状态

```text
SNAPSHOT_EXACT_CANDIDATE=PASS
LATEST_PAPI_SKINS_DUAL_PLATFORM=PASS
VELOCITY_THREE_INTEGRATIONS=PASS
OPTIONAL_DEPENDENCY_DEGRADATION=PASS
OFFICIAL_CLIENT_ACCEPTANCE=UNVERIFIED
REAL_BEDROCK_LOGIN=UNVERIFIED
REAL_SKIN_RENDER=UNVERIFIED
```
