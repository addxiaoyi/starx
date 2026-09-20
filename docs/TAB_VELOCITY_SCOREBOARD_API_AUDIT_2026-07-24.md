# TAB / VelocityScoreboardAPI 兼容审计 — 2026-07-24

## 结论

此前 TAB 6.0.2 在 Velocity 上虽然完成了 22 个 StarX 变量注册，但日志明确提示缺少 VelocityScoreboardAPI，导致以下功能不可用：

- scoreboard teams / nametags 与 sorting；
- belowname objective；
- playerlist objective；
- scoreboard。

本轮加入官方 VelocityScoreboardAPI `2.1.0` 后，Scoreboard API 注入成功，TAB 的缺失依赖警告完全消失，精确版本冷启动清单为 `PASS`。

结论分类：

```text
VELOCITY_SCOREBOARD_API_INJECTION=PASS
TAB_SCOREBOARD_FEATURE_GATE=PASS
OFFICIAL_CLIENT_VISUAL_RENDER=UNVERIFIED
```

## 官方源码身份

源码目录：

```text
tmp/velocity-scoreboard-api-source
```

身份核对：

- Remote: `https://github.com/NEZNAMY/VelocityScoreboardAPI.git`
- Tag: `2.1.0`
- Local commit: `1bb579b93b168c1cb80f3cd936e2349ce397256d`
- Remote tag commit: `1bb579b93b168c1cb80f3cd936e2349ce397256d`
- Working tree: clean

远端 tag 与本地 HEAD 完全一致。

## 干净构建

执行：

```text
gradlew.bat clean build
```

结果：

```text
BUILD SUCCESSFUL in 4m 30s
27 actionable tasks: 27 executed
```

可部署 shaded JAR：

```text
tmp/velocity-scoreboard-api-source/target/VelocityScoreboardAPI-2.1.0.jar
size=642606
SHA-256=639d7701a6745a983f52d72f4a31d1169a1333bedd1aa14d39f909ee2a66a8b4
```

`plugin/build/libs` 下约 20 KB 的文件只包含插件自身类，没有内嵌运行依赖，不作为部署产物。

插件描述符：

```text
id=velocity-scoreboard-api
version=2.1.0
main=com.velocitypowered.scoreboardapi.VelocityScoreboardAPI
```

## 运行矩阵

运行目录：

```text
tmp/starx-velocity-full-tab-integrations-20260724-213811-771
```

精确输入：

| 组件 | 版本 / SHA-256 |
|---|---|
| StarX Velocity | `fe02d53d20470d3dfe712f0f4bfa98c37b6bca6447aa721a8539cd4ebeaa8408` |
| Velocity | `3.5.0-SNAPSHOT-b606` / `f763b42b951892c62ecdee2e532a7788c9929a4468068227daea71d84f2b39f2` |
| LuckPerms | `5.5.60` |
| TAB | `6.0.2` |
| Floodgate | `2.2.5-b138` |
| VelocityScoreboardAPI | `2.1.0` / `639d7701a6745a983f52d72f4a31d1169a1333bedd1aa14d39f909ee2a66a8b4` |

关键成功标记：

```text
Loaded plugin velocity-scoreboard-api 2.1.0
Successfully injected Scoreboard API.
StarX TAB：注册 22 个 StarX 变量
acceptance=PASS
```

关键反向断言：

```text
Velocity does not have any sort of scoreboard API. = 0
Until then, the following features will not work: = 0
unsupported Velocity version warning = 0
scoreboard packet registration failure = 0
breaking-change registration error = 0
fatal lines = 0
```

## 启动时间边界

第一次组合冷启动在 `119.47s` 完成，而原工装上限为 `120s`，轮询边界误报超时。日志已显示代理正常 `Done`、Scoreboard API 注入成功且无 fatal。

完整依赖工装的冷启动上限调整为 `180s` 后重跑，最终清单为 `PASS`。这是验收工装边界问题，不是兼容失败。

## 清理

```text
25696=RELEASED
8796=RELEASED
25697=RELEASED
stale_java=0
```

## 维护债务

VelocityScoreboardAPI 2.1.0 的干净构建存在非致命提示：

- `VSACommand` 使用或覆盖 Velocity 已弃用 API；
- proxy 模块 Javadoc 的 `#getEntry()` 引用失效；
- 构建脚本使用 Gradle 9 将不兼容的弃用特性；
- 各模块当前没有单元测试源。

这些项目标记为 `PARTIAL`，不影响本轮运行时兼容结论。

## 验收边界

本轮证明：

- VelocityScoreboardAPI 能在 build 606 上完成 packet registration 和 API 注入；
- TAB 不再进入“scoreboard 功能不可用”的降级路径；
- StarX TAB 变量注册仍正常。

本轮没有使用 Mojang 官方 GUI 客户端观察视觉结果，因此以下仍为 `UNVERIFIED`：

- nametag / sorting 的客户端显示；
- belowname objective；
- playerlist objective；
- scoreboard 画面；
- Bedrock 客户端 scoreboard 映射。
