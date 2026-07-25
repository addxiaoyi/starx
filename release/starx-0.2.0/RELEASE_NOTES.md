# StarX 0.2.0

StarX 0.2.0 是首个正式提供三端通用插件与第三方扩展 Service API 的发布版本。

## 主要更新

- 新增 `starx-universal.jar`：同一 JAR 可分别部署到 Velocity、Paper 和 Folia，由平台描述符自动选择入口。
- 新增 StarX Extension API 1.0.0：支持版本与能力协商、扩展生命周期、事件订阅、失败回滚和逆序关闭。
- Velocity 通过 `StarxServiceProvider` 暴露服务；Paper/Folia 通过 Bukkit `ServicesManager` 暴露服务。
- 新增独立示例扩展，已在 Velocity、Paper、Folia 真实加载验证。
- 完善 Uworld 认证世界、账号/TOTP、后端桥接、空服 heartbeat、网络状态和安全门禁。
- 固定插件描述符资源过滤为 UTF-8，并增加版本和中文命令文本构建断言。
- 公共 API JAR 不包含内部运行时实现；Javadoc 使用 `-Werror` 严格发布门禁。

## 推荐安装

GitHub Release 只提供 `starx-universal-0.2.0.jar`。将同一个文件分别放入 Velocity、Paper 和 Folia 实例的 `plugins/` 目录。

## 扩展开发

Maven 坐标：`io.github.addxiaoyi.starx:starx-api:1.0.0`。
扩展应使用 `compileOnly`，不得将 API shade 或 relocate 到扩展 JAR。

## 环境基线

- Java 21
- Velocity 3.5.0-SNAPSHOT build 606
- Paper/Folia 1.21.11 API 基线

## 验证

发布候选已通过 API 公共边界测试、三端模块测试、示例扩展测试、通用 JAR 边界检查、UTF-8 描述符检查和完整 `npm run plugin:verify`。

不支持 `/reload` 或插件管理器热替换；升级应完整停止 JVM 后替换 JAR。
