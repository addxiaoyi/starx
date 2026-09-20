# Uworld 开发接口

Uworld 公共契约位于 `starx-limbo-api` 的 `io.github.addxiaoyi.starx.uworld` 包，Velocity 实现位于 `starx-velocity`。业务模块应接收 `UworldRuntime`，不得直接持有 `StarxUworldFactory`、`LimboFactory`、`VirtualWorld`、`Limbo` 或 `LimboPlayer`。

Uworld 属于 `starx-velocity.jar`，不是独立插件。运行环境要求 Java 21 和 Velocity `3.5.0-SNAPSHOT`，不支持热重载。

## API 可见性与装配

StarX 业务模块通过构造器接收 `UworldRuntime`。`StarxUworldFactory` 是内部生命周期细节，只能由 `UworldModule` 创建、初始化和关闭；它不是向外部 Velocity 插件注册的 service API。`StarxVelocityPlugin.uworld()` 只用于同一插件内部的模块装配，不能被当作跨插件稳定接口。

`io.github.addxiaoyi.starx.limbo`、原始 `LimboFactory`、`VirtualWorld`、`Limbo`、`LimboPlayer` 和所有迁移兼容别名都属于内部实现或迁移边界。消费者不得注入、返回或缓存这些类型，也不得自行初始化第二个 core owner。

仓库内的新消费者应放在 `starx-velocity` 的模块装配中，并复用该模块已经声明的 `starx-limbo-api` 工程依赖。当前发行物没有 Velocity `ServicesManager` 注册，也不承诺外部插件可获取 runtime；需要跨插件 API 时必须先新增独立 service 契约、版本策略和真实集成测试，不能直接依赖内嵌实现包。

## 公共类型

| 类型 | 用途 |
|---|---|
| `UworldRuntime` | 创建世界、查询 ready 状态、查询玩家当前 session。 |
| `UworldSpec` | 不可变世界规格；在发布前验证名称、坐标、旋转、距离和超时。 |
| `UworldWorldGenerator` | 仅在初始化阶段构建世界。 |
| `UworldWorldEditor` | 设置方块、biome、光照或加载世界文件；发布后 sealed。 |
| `UworldHandle` | owner 持有的幂等世界句柄；进入玩家并关闭世界。 |
| `UworldFlowOptions` | active timeout 和 transfer timeout。 |
| `UworldFlowSession` | 当前玩家流程、精确转服、失败、取消和唯一终态。 |
| `UworldFlowHandler` | ready、聊天、移动、旋转、落地、传送、通用 packet 和 outcome 回调。 |
| `UworldEnterResult` | 明确区分接受和拒绝，不使用 null。 |
| `UworldOutcome` | 终态类型、玩家可读原因和可选目标服。 |

## 完整示例

下面的消费者创建审核与 diagnostics 两个独立 handle，分别生成石质和金质平台。两个玩家可以同时进入不同世界；输入 `continue` 后只允许前往构造时绑定的同一个 `RegisteredServer` 对象。耗时判断在虚拟线程执行，结果通过 `session.execute` 回到玩家连接 event loop。关闭时严格按照创建顺序的逆序先关 diagnostics，再关 review。

```java
package example;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import io.github.addxiaoyi.starx.chunk.BuiltInBiome;
import io.github.addxiaoyi.starx.chunk.VirtualBlock;
import io.github.addxiaoyi.starx.uworld.UworldEnterResult;
import io.github.addxiaoyi.starx.uworld.UworldFlowHandler;
import io.github.addxiaoyi.starx.uworld.UworldFlowOptions;
import io.github.addxiaoyi.starx.uworld.UworldFlowSession;
import io.github.addxiaoyi.starx.uworld.UworldHandle;
import io.github.addxiaoyi.starx.uworld.UworldOutcome;
import io.github.addxiaoyi.starx.uworld.UworldRuntime;
import io.github.addxiaoyi.starx.uworld.UworldSpec;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

public final class ReviewFlow implements AutoCloseable {
  private static final int PLATFORM_RADIUS = 5;
  private static final int PLATFORM_Y = 99;

  private final UworldRuntime runtime;
  private final RegisteredServer target;
  private final Logger logger;
  private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

  private UworldHandle reviewWorld;
  private UworldHandle diagnosticsWorld;

  public ReviewFlow(UworldRuntime runtime, RegisteredServer target, Logger logger) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.target = Objects.requireNonNull(target, "target");
    this.logger = Objects.requireNonNull(logger, "logger");
  }

  public void enable() {
    if (!this.runtime.isReady()) {
      throw new IllegalStateException("Uworld runtime is not ready");
    }

    UworldHandle review = this.runtime.createWorld(
        "example.review",
        UworldSpec.defaults("review"),
        editor -> {
          VirtualBlock stone = editor.createBlock("minecraft:stone");
          for (int x = -PLATFORM_RADIUS; x <= PLATFORM_RADIUS; x++) {
            for (int z = -PLATFORM_RADIUS; z <= PLATFORM_RADIUS; z++) {
              editor.setBlock(x, PLATFORM_Y, z, stone);
              editor.setBiome(x, PLATFORM_Y + 1, z, BuiltInBiome.PLAINS);
            }
          }
          editor.fillSkyLight(15);
          editor.fillBlockLight(0);
        });

    try {
      this.diagnosticsWorld = this.runtime.createWorld(
          "example.diagnostics",
          UworldSpec.defaults("diagnostics"),
          editor -> {
            VirtualBlock marker = editor.createBlock("minecraft:gold_block");
            for (int x = -PLATFORM_RADIUS; x <= PLATFORM_RADIUS; x++) {
              for (int z = -PLATFORM_RADIUS; z <= PLATFORM_RADIUS; z++) {
                editor.setBlock(x, PLATFORM_Y, z, marker);
                editor.setBiome(x, PLATFORM_Y + 1, z, BuiltInBiome.PLAINS);
              }
            }
            editor.fillSkyLight(15);
            editor.fillBlockLight(0);
          });
      this.reviewWorld = review;
    } catch (RuntimeException error) {
      review.closeAsync(Component.text("Diagnostics world creation failed"))
          .toCompletableFuture()
          .join();
      throw error;
    }
  }

  public UworldEnterResult enterReview(Player player) {
    UworldHandle current = Objects.requireNonNull(
        this.reviewWorld, "review world is not enabled");
    UworldFlowOptions options = new UworldFlowOptions(
        Duration.ofMinutes(2),
        Duration.ofSeconds(15));
    return current.enter(player, options, new ReviewHandler("review"));
  }

  public UworldEnterResult enterDiagnostics(Player player) {
    UworldHandle current = Objects.requireNonNull(
        this.diagnosticsWorld, "diagnostics world is not enabled");
    UworldFlowOptions options = new UworldFlowOptions(
        Duration.ofMinutes(2),
        Duration.ofSeconds(15));
    return current.enter(player, options, new ReviewHandler("diagnostics"));
  }

  @Override
  public void close() {
    UworldHandle diagnostics = this.diagnosticsWorld;
    UworldHandle review = this.reviewWorld;
    this.diagnosticsWorld = null;
    this.reviewWorld = null;
    try {
      closeWorld(diagnostics, "Diagnostics flow is stopping");
    } finally {
      try {
        closeWorld(review, "Review flow is stopping");
      } finally {
        this.workers.shutdown();
      }
    }
  }

  private final class ReviewHandler implements UworldFlowHandler {
    private final String flowName;

    private ReviewHandler(String flowName) {
      this.flowName = flowName;
    }

    @Override
    public void onReady(UworldFlowSession session) {
      session.player().sendMessage(Component.text("Type continue to leave the review world."));
    }

    @Override
    public void onChat(UworldFlowSession session, String message) {
      ReviewFlow.this.workers.execute(() -> {
        boolean accepted = "continue".equalsIgnoreCase(message.trim());
        session.execute(() -> {
          if (!accepted) {
            session.player().sendMessage(Component.text("Input was not accepted."));
            return;
          }
          if (!session.complete(ReviewFlow.this.target)) {
            session.player().sendMessage(Component.text("The flow is already closing."));
          }
        });
      });
    }

    @Override
    public void onMove(UworldFlowSession session, double x, double y, double z) {
      if (y < 80.0) {
        session.cancel(Component.text("Player left the " + this.flowName + " platform"));
      }
    }

    @Override
    public void onOutcome(UworldFlowSession session, UworldOutcome outcome) {
      ReviewFlow.this.logger.info(
          "Uworld outcome flow={} player={} type={}",
          this.flowName,
          session.player().getUsername(),
          outcome.type());
    }
  }

  private static void closeWorld(UworldHandle world, String reason) {
    if (world == null) {
      return;
    }
    world.closeAsync(Component.text(reason)).toCompletableFuture().join();
  }
}
```

调用方可以让两个独立玩家同时进入两个 handle，并且必须分别处理拒绝结果：

```java
UworldEnterResult reviewResult = reviewFlow.enterReview(reviewPlayer);
if (reviewResult instanceof UworldEnterResult.Rejected rejected) {
  reviewPlayer.sendMessage(rejected.reason());
}

UworldEnterResult diagnosticsResult = reviewFlow.enterDiagnostics(diagnosticsPlayer);
if (diagnosticsResult instanceof UworldEnterResult.Rejected rejected) {
  diagnosticsPlayer.sendMessage(rejected.reason());
}
```

## 内置玩家流程

| 流程 | 触发与玩家所见 | 玩家输入 | 成功出口 | 失败动作与证据 |
|---|---|---|---|---|
| 离线新用户注册 | 未注册离线账号连接后进入 auth 世界并收到注册提示 | 在聊天输入新密码 | 注册提交成功后只连接启动时保存的精确 hub 对象 | 校验、数据库或转服失败时断开并记录唯一 outcome |
| 已注册用户登录 | 已注册离线账号进入 auth 世界并收到密码提示 | 在聊天输入密码 | 密码通过且无需 TOTP 时进入精确 hub | 密码错误保留当前精确 lease；超时或内部错误断开并清理 |
| TOTP / 恢复码 | 密码通过且账号启用 TOTP 后显示二次验证提示 | 输入六位 TOTP 或十位恢复码 | 只完成当前连接的精确 lease，再进入目标阶段 | 无效码不消费其他恢复码；重放、超时和 CAS 失败均留 outcome |
| 正版自动认证 | 正版解析成功时不显示密码输入 | 无 | 保留同一连接 owner 和精确目标屏障后转服 | 解析或目标失败时不绕过屏障 |
| Diagnostics | 有权限玩家执行 `/uworld test` 后看到独立平台和 ready 消息 | 普通聊天触发 callback；移动触发 move callback；在世界内输入 `/uworld leave` 会由聊天回调识别并执行 | 返回进入前保存的服务器；无前序服时返回 diagnostics 初始化时保存的 hub 对象 | 缺失目标、离线后端、wrong target、timeout 或 shutdown 都产生唯一 outcome 并清理 session |

Diagnostics handler 本身不读 Auth 用户表，但这不表示整个 `starx-velocity.jar` 可以脱离数据库启动。插件会在模块初始化前创建共享数据库；当前受支持发行物仍要求 SQLite 初始化成功。所谓“独立流程”是世界、session 和业务回调不依赖 Auth 数据，不是无数据库运行模式。

## 世界所有权与隔离

- `createWorld(owner, spec, generator)` 中的 owner 和 world name 都是诊断上下文的一部分。
- 同名世界不能由第二个 owner 静默替换；冲突抛出 `UworldCreationException`。
- 一个 runtime 可以同时持有认证世界、诊断世界和其他业务世界。
- 同一个 `Player` 对象同一时刻只能属于一个 Uworld session；第二次进入返回 `PLAYER_BUSY`。
- generator 成功返回后 editor 被 sealed，世界发布后视为不可变。
- 关闭一个 handle 只终止属于该世界的 session，不得清理其他世界。

## Session 状态

```text
ENTERING -> ACTIVE -> TRANSFERRING -> CLOSED
    |          |             |
    +----------+-------------+--> CLOSED
```

- `onSpawn` 将 `ENTERING` 变为 `ACTIVE`。
- `complete(target)` 只允许从 `ACTIVE` 进入 `TRANSFERRING`。
- session 保留到与保存对象完全相同的目标触发 `ServerConnectedEvent`。
- wrong target、kick、future error、active timeout、transfer timeout、disconnect、world close 和 runtime shutdown 都进入唯一终态。
- 终态操作幂等；并发 close 只能完成一次 `completion()` 和一次 `onOutcome`。

不要按服务器名称重新解析目标，也不要在转服失败时选择第一个可用后端。

## 线程规则

`UworldFlowHandler` 回调默认运行在玩家的 Netty event loop：

- 可以做状态判断、发送消息和触发 session 终态。
- 不得同步访问数据库、文件、HTTP、DNS 或其他可能阻塞的服务。
- 耗时工作应提交到受控 executor。
- executor 完成后调用 `session.execute(action)` 回到连接线程，再调用 `complete`、`fail`、`cancel` 或玩家 API。
- handler 共享的可变状态必须自行保证线程安全。

World generator 在发布前执行，可以读取 loader 文件；不要保留 editor 并在发布后继续写世界。

## 进入失败

| `UworldEnterStatus` | 含义 | 调用方处理 |
|---|---|---|
| `PLAYER_BUSY` | 玩家已属于另一个 session。 | 保留现有流程并显示冲突原因。 |
| `WORLD_CLOSED` | handle 已关闭。 | 不重试同一 handle；重新获取业务世界。 |
| `RUNTIME_STOPPING` | 代理正在关闭。 | 拒绝进入并要求玩家稍后重连。 |
| `SPAWN_REJECTED` | 底层 spawn 同步失败。 | 记录 owner/world/player 上下文并 fail closed。 |

## 终态

| `UworldOutcomeType` | 含义 |
|---|---|
| `TRANSFERRED` | 已连接到精确目标。 |
| `FAILED` | 业务或内部错误。 |
| `CANCELLED` | 业务主动取消。 |
| `DISCONNECTED` | 玩家连接中断。 |
| `TIMED_OUT` | active 或 transfer timeout。 |
| `KICKED` | 转服期间被后端拒绝或踢出。 |
| `WRONG_TARGET` | 实际连接目标不是保存的对象。 |
| `RUNTIME_STOPPING` | runtime 关闭。 |
| `WORLD_CLOSED` | 所属世界关闭。 |
| `SPAWN_REJECTED` | spawn 未成功建立 session。 |

内部日志必须包含 owner、world、player 和 target 上下文；玩家消息使用普通语言，不暴露堆栈。

## 生命周期

建议模块顺序：

1. 初始化 `StarxUworldFactory` 和 runtime。
2. 创建业务世界并发布 handle。
3. 注册 Auth 和 diagnostics 等消费者。
4. 停止时先停止消费者接纳新流程。
5. 反向关闭消费者、session 和 handle。
6. 等待世界关闭后再释放 factory。
7. 最后关闭数据库和代理级资源。

Uworld core 是进程级所有者。开发环境也必须完整停止并重新启动 Velocity，不能使用插件热重载器。

## 相关文档

- [配置](UWORLD_CONFIGURATION.md)
- [验收](UWORLD_ACCEPTANCE.md)
- [Velocity 部署](../starx-plugins/starx-velocity/README.md)
- [内嵌运行时与上游边界](../starx-plugins/starx-standalone-limbo/README.md)
