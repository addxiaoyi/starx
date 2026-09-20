# StarX Extension API

`starx-api` 是 StarX 面向第三方插件的独立 Java 21 合约，不是可直接安装的 Minecraft 插件。

## Maven 坐标

```text
io.github.addxiaoyi.starx:starx-api:1.0.0
```

本地发布：

```powershell
.\scripts\invoke-gradle-ascii.ps1 `
  :starx-plugins:starx-api:publishToMavenLocal `
  --console=plain
```

第三方 Gradle 工程：

```kotlin
repositories {
    mavenLocal()
}

dependencies {
    compileOnly("io.github.addxiaoyi.starx:starx-api:1.0.0")
}
```

必须使用 `compileOnly`。不要把 `starx-api` shadow、relocate 或复制进扩展 JAR；运行时应使用 StarX 主插件提供的同一 API 类。

## 公共入口

- `StarxService`：能力查询、扩展注册、状态查询和事件订阅。
- `StarxServiceProvider`：Velocity 主插件实例的稳定发现接口。
- `StarxExtension` / `StarxExtensionContext`：扩展生命周期。
- `StarxExtensionDescriptor`：扩展 ID、版本、所需 API 和能力。
- `StarxCapabilities`：稳定能力名称。
- `StarxServiceEventTypes`：稳定事件类型。
- `BridgeProtocol`：Velocity 与 Paper/Folia 后端桥协议。

`io.github.addxiaoyi.starx.runtime.extension` 仅供 StarX 自身实现使用，不属于兼容承诺。

完整接入说明见 `docs/EXTENSION_API.md`，兼容政策见 `docs/EXTENSION_COMPATIBILITY_POLICY.md`。
