# StarX 扩展 API 兼容政策

## 版本范围

StarX 插件实现版本与扩展 API 版本独立管理：

- 插件实现当前为 `0.5.1`；
- 公共扩展 API 从 `1.0.0` 开始采用语义化版本。

扩展应声明自己所需的最低 `ApiVersion`，不得根据 StarX 插件文件名推断 API 能力。

## 稳定兼容面

以下包属于 API 1.x 的公共兼容面：

```text
io.github.addxiaoyi.starx.api.bridge
io.github.addxiaoyi.starx.api.extension
```

以下内容不在兼容承诺内：

```text
io.github.addxiaoyi.starx.runtime.extension
io.github.addxiaoyi.starx.common
io.github.addxiaoyi.starx.velocity
io.github.addxiaoyi.starx.server
io.github.addxiaoyi.starx.limbo
```

第三方扩展导入内部包、反射私有成员或直接转换为 StarX 实现类，风险由扩展作者承担。

## SemVer 规则

### Patch：`1.0.0 → 1.0.1`

允许：

- 修复实现缺陷；
- 改进日志和错误信息；
- 收紧实现中未承诺的内部行为；
- 修复文档，不改变公共签名和稳定语义。

禁止删除或修改已有公共类型、方法、构造器、事件名、能力名和必需负载字段。

### Minor：`1.0.x → 1.1.0`

允许向后兼容的新增：

- 新公共类型；
- 接口 `default` 方法；
- 新事件和新能力；
- 事件负载中的可选字段；
- 新枚举值。扩展必须为未知枚举值保留回退分支。

禁止向已有接口增加无默认实现的抽象方法，也不得改变已有方法参数、返回类型或异常语义。

### Major：`1.x → 2.0.0`

仅 major 版本允许不兼容变更，包括删除、重命名、修改签名、改变线程保证、改变必需事件字段或重新定义能力语义。

运行时只接受相同 major，且运行时版本不低于扩展声明版本：

```text
runtime.major == required.major
runtime >= required
```

## 弃用政策

公共 API 在删除前必须：

1. 先以 `@Deprecated` 和文档标记；
2. 提供迁移替代方案；
3. 至少保留两个 minor 发布，且通常不少于 180 天；
4. 仅在下一个 major 删除。

严重安全问题可以缩短周期，但必须在发布说明中明确风险、替代接口和受影响版本。

## 事件兼容

- `StarxServiceEventTypes` 中的名称在同一 major 内稳定；
- minor 可增加可选 payload 字段；
- 删除字段、改变字段类型或把可选字段改为必需字段属于 major 变更；
- payload 值必须可视为不可变快照；扩展不得修改或依赖具体 Map 实现；
- 事件顺序只在同一发布线程内保持调用顺序，不承诺跨线程全局顺序；
- 不承诺事件一定运行在游戏线程。

## 能力兼容

- `StarxCapabilities` 中的字符串在同一 major 内不可改义；
- 新能力可在 minor 中增加；
- 能力存在表示对应契约可用，不表示某个外部服务当前健康；
- 可选集成能力可能随服务器已安装插件变化，因此扩展必须在每次启动时查询；
- 扩展不得把未知能力视为错误。

## 生命周期兼容

API 1.x 保证：

- 扩展 ID 唯一；
- 注册失败不留下活动注册；
- `onEnable` 失败会执行补偿性 `onDisable`；
- 注册句柄 `close()` 幂等；
- context 创建的订阅会在注销时自动清理；
- StarX 关闭时按注册逆序关闭扩展；
- 事件监听器异常不会中断其他监听器。

不保证插件热重载。受支持的升级方式是停止对应 JVM、替换 JAR、再启动。

## 发布门禁

每个 API 发布必须通过：

- `starx-api` 单元测试；
- `PublicApiCompatibilityTest` 的 1.0 基线；
- Velocity 服务发现契约测试；
- Paper/Folia Bukkit Service 契约测试；
- `starx-universal` 单一 API 副本、双描述符和平台 API 泄漏验证；
- Maven POM、主 JAR、sources JAR 和 Javadoc JAR 生成；
- 至少一次与当前支持的 Velocity、Paper 和 Folia 运行候选的启动验收。

修改公共 API 时，评审必须明确标注为 patch、minor 或 major，不允许以插件实现版本掩盖 API 破坏性变更。
