# StarX 配置与运行时资源目录

本文是 StarX Velocity 0.4.2 的配置和运行时资源目录说明。生产环境只读取 Velocity 工作目录下的 `plugins/starx/`；仓库里的 `src/main/resources/` 只是打包时使用的默认模板。

## 先记住这三点

1. `plugins/starx/config.yml` 是唯一入口和分片索引，不再承载全部业务配置。
2. 真正的配置放在 `plugins/starx/config/` 下按职责拆分的 YAML 文件中。
3. 投影、结构等世界文件放在 `plugins/starx/assets/uworld/`，配置只通过相对路径引用它们。

## 生产目录

```text
plugins/starx/
  config.yml                 # 配置入口，只维护分片目录和顺序
  config/
    core.yml                 # 基础、HTTP、数据库、兼容性和自动配置
    auth.yml                 # Auth、UniAuth、TOTP
    network.yml              # 网站同步、网络自动化、NapCat
    modules.yml              # 模块开关、玩家列表
    uworld.yml               # Uworld 和认证世界
  assets/
    uworld/
      *.schem                # WorldEdit Schematic
      *.schematic            # MCEdit Schematic
      *.nbt                  # Minecraft Structure
      *.litematic             # Litematica
  uworld/
    core.yml                 # Uworld core 运行时配置，不是投影文件
  data.db                    # SQLite 数据库
  data.db-wal                # SQLite 运行时可能存在
  data.db-shm                # SQLite 运行时可能存在
  cache/                      # 运行时缓存，可由插件创建
```

首次启动时，插件会创建 `config.yml` 和缺失的默认分片；已有文件不会被默认模板覆盖。完整启动会读取配置，插件不支持热重载，修改后必须完整停止并重新启动 Velocity。

## 中文注释如何生效

JAR 内置的五个默认分片已经加入中文保姆式注释。插件只会复制不存在的分片，因此：

- 新安装或新建的 `plugins/starx/config/*.yml` 会直接带中文注释。
- 已经存在的分片不会被覆盖，升级时也不会自动把注释插入旧文件；这样可以保护管理员现有配置。
- 旧安装如果想补齐注释，可以先备份 `plugins/starx/config/`，再把 JAR 中对应的默认分片与现有文件逐项对照，只复制注释，不要覆盖现有值。
- 注释不会参与 YAML 配置解析；改完后仍要完整重启 Velocity 才会生效。

## 配置文件归属

| 文件 | 顶层配置 | 用途 |
|---|---|---|
| `config/core.yml` | `auto-config`、`compatibility`、`api-key`、`http`、`webhook`、`database` | 代理基础配置、管理 HTTP、数据库和自动探测 |
| `config/auth.yml` | `auth`、`uniauth`、`totp` | 登录、UniAuth、二次验证 |
| `config/network.yml` | `website-sync`、`network-automation`、`napcat` | 网站同步和网络相关自动化 |
| `config/modules.yml` | `modules`、`player-list` | 模块启用状态和玩家列表 |
| `config/uworld.yml` | `uworld` | 认证虚拟世界、诊断世界和世界文件 |

入口文件默认内容如下。`files` 的顺序就是合并顺序；同一个键在后加载的分片中覆盖前面的值，不建议让多个分片重复声明同一顶层配置。

```yaml
schema-version: 5
config-files:
  directory: config
  files:
    - core.yml
    - auth.yml
    - network.yml
    - modules.yml
    - uworld.yml
```

分片文件名必须是同目录下的 `.yml` 或 `.yaml` 文件，不能使用绝对路径、子目录、`..` 逃逸或重复文件名。保留 `config.yml` 的入口位置，外部脚本和备份工具也以它为识别点。

## 投影文件放置和加载

投影文件由 `config/uworld.yml` 的以下配置选择：

```yaml
uworld:
  auth:
    world:
      loader-type: AUTO
      file-name: assets/uworld/auth_world.schem
```

`file-name` 是相对于 `plugins/starx/` 的路径。因此上面的配置实际加载：

```text
plugins/starx/assets/uworld/auth_world.schem
```

推荐操作步骤：

1. 将投影文件复制到 `plugins/starx/assets/uworld/`。
2. 在 `config/uworld.yml` 中填写 `uworld.auth.world.file-name`，只写相对路径。
3. 使用 `AUTO` 让插件按扩展名识别，或填写明确的 loader 类型。
4. 停止并重新启动 Velocity，检查日志中的 `Loaded Uworld authentication world ...`。

支持的格式如下：

| 扩展名 | 明确 loader 类型 | 说明 |
|---|---|---|
| `.schem` | `WORLDEDIT_SCHEM` | WorldEdit schem |
| `.schematic` | `SCHEMATIC` | MCEdit schematic |
| `.nbt` | `STRUCTURE` | Minecraft structure NBT |
| `.litematic` | `LITEMATIC` | Litematica |

`loader-type: VOID` 不读取投影文件，而是在认证世界中生成默认平台。`AUTO` 只接受上表四种扩展名。文件必须是 `plugins/starx/` 下的真实普通文件；绝对路径、路径穿越和指向目录外的符号链接都会被拒绝，不会静默回退到其他世界。

`plugins/starx/uworld/core.yml` 是 Uworld core 的独立运行时配置，和 `assets/uworld/` 中的投影文件没有关系。旧的 `plugins/starx/limbo/core.yml` 和根目录投影路径仍可作为兼容输入，但新安装应使用上面的目录。

## 迁移和回滚

没有 `config-files` 的旧单文件 `plugins/starx/config.yml` 会在下一次完整启动时按顶层配置归属拆分：

1. 先保留原文件为 `config.yml.split-backup`；如果同名备份已存在，会追加数字后缀。
2. 将值写入五个分片。
3. 将 `config.yml` 原子替换为小型入口索引。
4. 后续启动直接读取分片，不会再次把它们合并写回单文件。

迁移前仍建议停服并额外备份整个 `plugins/starx/`。回滚时停止 Velocity，恢复同一批次的 JAR、`config.yml`、`config/`、`assets/uworld/`、`uworld/` 和 SQLite 文件，再完整启动。不要在运行中的代理里移动投影文件或使用插件重载器。

## 源码默认模板

以下文件会被打包进 JAR，用于首次启动时生成运行时文件：

```text
starx-plugins/starx-velocity/src/main/resources/default-config.yml
starx-plugins/starx-velocity/src/main/resources/config/core.yml
starx-plugins/starx-velocity/src/main/resources/config/auth.yml
starx-plugins/starx-velocity/src/main/resources/config/network.yml
starx-plugins/starx-velocity/src/main/resources/config/modules.yml
starx-plugins/starx-velocity/src/main/resources/config/uworld.yml
```

不要把生产投影放进 `src/main/resources/`，也不要把生产密钥提交到仓库。生产投影和配置都放在 Velocity 实例的 `plugins/starx/` 目录下。

## 相关文档

- [Uworld 配置](UWORLD_CONFIGURATION.md)
- [Uworld 环境与部署](UWORLD_ENVIRONMENT.md)
- [Velocity 插件模块说明](../starx-plugins/starx-velocity/README.md)
