# v1.1.0+ 自动部署到六个服务器

Release 工作流成功后，`.github/workflows/deploy-six-servers.yml` 会按顺序更新：

- Velocity：`/data/minecraft/vc/plugins/starx-universal.jar`
- lobby：`/data/minecraft/lobby/plugins/starx-universal.jar`
- LE：`/data/minecraft/le/plugins/starx-universal.jar`
- PVP：`/data/minecraft/pvp/pvp/plugins/starx-universal.jar`
- ThePit：`/data/minecraft/thepit/plugins/starx-universal.jar`
- ChaserGamer：`/data/minecraft/ChaserGamer#4/plugins/starx-universal.jar`

## 必需 Secrets

在 GitHub 仓库 Settings → Secrets and variables → Actions 配置：

- `STARX_DEPLOY_HOST`：MCSManager/SSH 主机地址
- `STARX_DEPLOY_USER`：SSH 用户
- `STARX_DEPLOY_KEY`：专用部署私钥
- `STARX_DEPLOY_KNOWN_HOSTS`：由管理员核验后生成的目标主机 known_hosts 行
- `STARX_DEPLOY_SUDO_PASSWORD`：仅用于远端 `sudo install` 原子替换，不写入日志或仓库

部署行为：每个实例串行执行；先备份现有 JAR，再上传到 `/tmp`，校验文件非空和 SHA-256，使用 `.new` 后原子 `mv` 替换。任一实例失败会停止后续实例，不会覆盖未验证文件。

此流程只替换 JAR，不自动强杀运行中的 Java 进程。Minecraft 服务需要在维护窗口重启，或者后续为每个实例补充经过核验的 MCSManager 重启命令。
