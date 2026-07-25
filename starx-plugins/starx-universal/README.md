# StarX Universal Plugin

`starx-universal.jar` 是同一套 StarX 代码的三端通用部署产物。

它同时包含：

- `velocity-plugin.json`：Velocity 只读取该描述符并启动 `StarxVelocityPlugin`。
- `plugin.yml`：Paper/Folia 只读取该描述符并启动 `StarxServerPlugin`。
- `folia-supported: true`：Paper 插件加载器在 Folia 环境中允许加载同一后端入口。

这里的“智能识别”由平台插件加载器完成，不依赖容易误判的运行时平台探测。未被对应描述符引用的另一端入口类不会被主动加载。

## 构建

```powershell
npm run plugin:universal
```

产物：

```text
starx-plugins/starx-universal/build/libs/starx-universal.jar
```

## 部署

把完全相同的文件分别复制到需要运行 StarX 的实例：

```text
Velocity/plugins/starx-universal.jar
Paper/plugins/starx-universal.jar
Folia/plugins/starx-universal.jar
```

一个 JAR 文件不能只放在某个中心目录后自动跨进程生效；Velocity 和后端服务器是独立 JVM，每个实例仍需各自放置一份相同文件。

不要在同一个实例中同时放置 `starx-universal.jar` 与对应的旧分端 JAR，否则会形成重复插件 ID：

- Velocity 不要同时放 `starx-velocity.jar`。
- Paper/Folia 不要同时放 `starx-server.jar`。

Geyser、Floodgate、PlaceholderAPI、SkinsRestorer 等第三方插件仍需按其自身平台要求单独安装。

## 验证边界

构建任务会拒绝：

- 缺少任意平台描述符或入口类；
- 共享 API 出现不受控重复副本；
- 把 Velocity、Bukkit 或 Paper API 类打入 JAR；
- 嵌套 JAR、签名文件或 `module-info.class` 泄漏；
- 后端描述符未声明 Folia 支持；
- ZIP 内存在重复条目。
