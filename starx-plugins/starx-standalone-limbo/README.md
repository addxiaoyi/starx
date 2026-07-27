# StarX Standalone Limbo

该模块把固定版本的 Elytrium LimboAPI 源码嵌入 StarX 构建，并提供 `StarxUworldFactory` 给 `starx-velocity` 内部的 Uworld runtime。名称中的 standalone 表示源码和运行时不依赖外置 LimboAPI 插件，不表示它是一个需要单独部署的 Velocity 插件。

## 部署边界

- 生产环境只部署 `starx-universal.jar`；Uworld 类虽然包含在通用包中，但只会由 Velocity 入口加载。
- `starx-standalone-limbo`、`starx-limbo-api` 和 Uworld 都不是第二个插件。
- `starx-standalone-limbo` 是内嵌构建库，不能把它的 JAR 复制到 Velocity `plugins/`。
- 禁止同时安装外置 LimboAPI。
- Uworld runtime 是 factory、世界、玩家 session 和协议资源的唯一生命周期所有者。
- 每个 JVM 只能有一个进程级 core owner；该所有权只能通过完整停止 JVM 释放，不支持热重载。
- 协议号 776 是 Velocity `ProtocolVersion.MAXIMUM_VERSION` 所报告的最大协议能力下限，不是玩家客户端最低协议下限。它用于拒绝底层能力不足的 Velocity runtime，不能据此宣称某个 Minecraft 客户端版本已完成支持或验收。

底层预生成范围仍覆盖 `1_7_2..LATEST`，但“可生成数据”不等于“已支持”。当前没有任何客户端版本可以在真实客户端矩阵完成前标为已验证支持；最低、最高和至少一个中间版本都必须使用同一候选 JAR 留证。

公共消费者只使用 `io.github.addxiaoyi.starx.uworld` 契约。`io.github.addxiaoyi.starx.limbo`、原始 `LimboFactory`、`VirtualWorld`、`Limbo` 和 `LimboPlayer` 属于底层实现边界，不应从业务模块返回或缓存。

底层 LimboAPI 包名、上游项目名、同步命令和 `starx-standalone-limbo` Gradle 模块名保持不变。`StarxLimboFactory` 只作为迁移别名保留一个完整 Uworld 主版本；新业务代码必须使用 `StarxUworldFactory` 或公共 `io.github.addxiaoyi.starx.uworld` API。

## 生命周期

1. `UworldModule` 创建一个 `StarxUworldFactory` 并初始化 core。
2. 运行时在发布世界前完成世界生成和 packet 准备。
3. 每个玩家由一个受管 session 持有，终态通过 CAS 和精确对象删除收敛。
4. 关闭时先拒绝新 session，再终止玩家并关闭每个 `UworldHandle`，等待 handle 的异步关闭完成后才关闭 factory。

handle-before-factory 的逆序关闭是强制生命周期边界。直接先关 `StarxUworldFactory` 会让仍存活的世界或 session 持有已释放的进程级协议资源。

如果 world generator、mapping、配置或 Velocity 协议不兼容，初始化必须失败并由上层模块阻止玩家进入，不得退回不受认证屏障保护的任意后端。

## 构建与测试

从仓库根目录执行：

```powershell
& .\scripts\invoke-gradle-ascii.ps1 `
  :starx-plugins:starx-limbo-api:test `
  :starx-plugins:starx-standalone-limbo:test `
  --no-daemon --console=plain
```

同步测试和完整 Uworld 门禁见 [Uworld 验收](../../docs/UWORLD_ACCEPTANCE.md)。

## 上游与许可证

上游固定为 Elytrium LimboAPI commit `839773cfd406458cf247fbfd64ed492926f921b7`。协议实现源码遵循上游 AGPL-3.0 边界，API 源码保留 MIT 边界；StarX 新增的 Uworld 产品代码使用 AGPL-3.0。

- [上游同步说明](UPSTREAM.md)
- [第三方声明](../../NOTICE)
- [AGPL-3.0](../../LICENSES/AGPL-3.0.txt)
- [MIT](../../LICENSES/MIT.txt)

更新上游前必须先重放同步脚本、审查 StarX override、运行全部测试，并重新执行冷启动和真实客户端矩阵。只更新 commit 或 vendor JAR 而不完成这些步骤是不受支持的升级。
