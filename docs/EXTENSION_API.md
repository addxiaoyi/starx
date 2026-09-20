# StarX 第三方扩展 API

当前公共 API 版本为 **1.0.0**，要求 Java 21。运行时由同一份 `starx-universal.jar` 在 Velocity、Paper 或 Folia 中提供。

## 依赖规则

```kotlin
repositories {
    mavenLocal()
}

dependencies {
    compileOnly("io.github.addxiaoyi.starx:starx-api:1.0.0")
}
```

扩展不得将 `starx-api` 打包、shadow 或 relocate 到自己的 JAR。否则服务接口会因类加载器身份不同而无法匹配。

## Velocity 服务发现

扩展的 `velocity-plugin.json` 必须声明对 `starx` 的非可选依赖。初始化后从 StarX 插件实例取得稳定 Provider：

```java
PluginContainer container = proxy.getPluginManager()
    .getPlugin("starx")
    .orElseThrow(() -> new IllegalStateException("StarX is not installed"));

StarxService service = container.getInstance()
    .filter(StarxServiceProvider.class::isInstance)
    .map(StarxServiceProvider.class::cast)
    .map(StarxServiceProvider::starxService)
    .orElseThrow(() -> new IllegalStateException("StarX service is not ready"));
```

描述符依赖示例：

```json
{
  "dependencies": [
    { "id": "starx", "optional": false }
  ]
}
```

## Paper/Folia 服务发现

扩展的 `plugin.yml` 应使用：

```yaml
depend: [StarXServer]
```

然后通过 Bukkit 标准服务管理器查询：

```java
RegisteredServiceProvider<StarxService> registration =
    getServer().getServicesManager().getRegistration(StarxService.class);
if (registration == null) {
  throw new IllegalStateException("StarX service is not registered");
}
StarxService service = registration.getProvider();
```

Paper 与 Folia 使用同一发现代码。通过 `service.platform()` 可区分 `PAPER` 和 `FOLIA`。

## 注册扩展

扩展 ID 必须为小写、稳定且带命名空间，例如 `com.example.audit`。注册句柄必须由第三方插件保存，并在自身关闭时调用 `close()`：

```java
StarxExtensionDescriptor descriptor = new StarxExtensionDescriptor(
    "com.example.audit",
    "Example Audit",
    "1.2.0",
    StarxApi.VERSION,
    Set.of(StarxCapabilities.EVENTS));

StarxExtensionRegistration registration = service.registerExtension(
    descriptor,
    new StarxExtension() {
      @Override
      public void onEnable(StarxExtensionContext context) {
        context.subscribe(
            StarxServiceEventTypes.PLAYER_LOGIN_SUCCESS,
            event -> context.logger().log(
                System.Logger.Level.INFO,
                "Login event: " + event.payload()));
        context.publish("ready", Map.of("mode", "audit"));
      }

      @Override
      public void onDisable(StarxExtensionContext context) {
        // 关闭扩展自身拥有的任务、连接和文件资源。
      }
    });
```

StarX 会自动清理通过 `context.subscribe(...)` 创建的订阅。扩展自行创建的调度任务、线程、网络连接和数据库连接仍由扩展自己关闭。

## 版本协商

注册前 StarX 强制检查：

- `requiredApi.major` 必须与运行时 API major 相同；
- 运行时版本必须大于或等于扩展要求的版本；
- `requiredCapabilities` 必须全部存在；
- 扩展 ID 不得重复。

不满足条件时注册直接失败，`onEnable` 不会执行。`onEnable` 抛出异常时，注册会回滚、订阅会清理，并调用一次 `onDisable` 做补偿清理。

## 平台能力

所有平台都提供：

```text
starx.service.extensions
starx.service.events
starx.platform.velocity | starx.platform.paper | starx.platform.folia
```

Velocity 还声明：

```text
starx.velocity.auth
starx.velocity.uworld
starx.velocity.http-api
starx.velocity.backend-routing
```

Paper/Folia 还声明：

```text
starx.backend.bridge
starx.backend.status
starx.backend.heartbeat
starx.backend.skin
```

检测到对应插件时还可能提供：

```text
starx.backend.placeholderapi
starx.backend.skinsrestorer
```

后端还会保留 `scheduler.main`、`scheduler.global`、`scheduler.region`、`world.paper`、`world.regionized` 等平台能力。扩展必须查询能力，不得仅根据实现版本猜测功能。

## 公共事件

稳定事件名称由 `StarxServiceEventTypes` 提供，包括登录、注册、TOTP、安全告警、皮肤、绑定、管理操作、配置同步、后端 ready/stopping 以及扩展 enabled/disabled。

第三方扩展发布的事件自动命名为：

```text
extension.<extension-id>.<eventName>
```

可订阅精确事件名，也可使用 `StarxApi.ALL_EVENTS` 订阅全部事件。

## 线程模型

事件回调运行在**事件发布者所在的线程**，不保证是 Velocity 事件线程、Bukkit 主线程或 Folia region thread。回调必须快速返回，并遵守以下规则：

- 不在回调中阻塞网络或数据库；
- 访问 Velocity API 时投递到扩展自己的 Velocity 调度任务；
- 访问 Paper API 时投递到 Bukkit/GlobalRegionScheduler；
- 访问 Folia 实体或区块时使用对应 EntityScheduler/RegionScheduler；
- 不把 StarX 的内部线程或 executor 当作扩展资源。

## 生命周期

- StarX 初始化完成后服务才可获取；过早调用 `starxService()` 会明确失败。
- 第三方插件应在自己的 enable/init 阶段注册，在 disable/shutdown 阶段关闭句柄。
- StarX 停止时会按注册逆序关闭仍存活的扩展。
- `close()` 幂等，可安全重复调用。
- 不支持 `/reload`、插件管理器热卸载或运行时替换 JAR；必须完整重启对应 JVM。

## 错误隔离

一个事件监听器抛出的运行时异常会被记录，不会阻止其他监听器。扩展关闭异常会被汇总，但 StarX 仍继续关闭其余扩展。扩展不得依赖异常被吞掉，也不得在失败后继续持有失效的 context。
