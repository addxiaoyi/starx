# StarX Extension Example

这是一个真正的第三方扩展示例。主源码只依赖 `starx-api` 与对应平台 API，不引用 `starx-common`、`starx-velocity` 或 `starx-server` 实现类。

构建：

```powershell
npm run plugin:extension-example
```

产物：

```text
starx-plugins/starx-extension-example/build/libs/starx-extension-example.jar
```

同一 JAR 可分别放入 Velocity、Paper 和 Folia：

```text
Velocity/plugins/starx-extension-example.jar
Paper/plugins/starx-extension-example.jar
Folia/plugins/starx-extension-example.jar
```

它演示：

- Velocity 通过 `StarxServiceProvider` 发现服务；
- Paper/Folia 通过 Bukkit `ServicesManager` 发现服务；
- API 和能力协商；
- 扩展注册、事件订阅、自定义事件发布；
- 插件关闭时幂等注销；
- 单一 JAR 双描述符；
- 不把 StarX API 或平台 API 打入扩展 JAR。
