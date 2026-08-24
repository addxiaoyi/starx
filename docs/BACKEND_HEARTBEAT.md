# 后端心跳协议

## 概述

-StarX 后端（Lobby 等子服务器）通过心跳协议与前置的 Velocity 代理建立连接，实现皮肤数据、配置同步等功能。

## 关键问题

### 问题：为什么无玩家子服的皮肤同步需要等几分钟？

根源在于：**心跳协议单向拉取设计**。

1. **Velocity** 通过 `BackendCommandMailbox` 存储 `proxy.skin.update` 命令
2. **后端** 只有在发送 **heartbeat** 时才能从 Velocity 拉取这些命令
3. 默认心跳间隔为 **15 秒**，若 heartbeat 期间有积压，需等下轮 heartbeat 才行

### 为什么会有积压？

- 网站皮肤更新触发 `SkinBridgeModule.broadcastSkinUpdate()`
- 目标后端无玩家在线 → 消息进入 mailbox
- 后端收不到消息 → 皮肤无法同步

---

## 机制优化

从 0.5.1 起，StarX 优化了心跳拉取逻辑：

### 1. 实时拉取（积压即推送）

当 Velocity 心跳响应中包含积压数量 `httpCommandsQueued > 0` 时，后端会**立即触发下轮心跳**，而不是等固定间隔。

```
后端心跳 → Velocity 返回 queued=5 → 立即心跳 → 取走 1 条 → 还有 4 条 → 再心跳...
```

这种机制确保：

- 积压命令在 1-5 秒内完成处理
- 大量离线玩家皮肤同步不再卡顿
- 线程不再堆积（每次心跳不超过 8 条）

### 2. 最大交换次数

`BackendHeartbeatExchange` 限制最大交换次数为 **32 次**，防止：

- 网络异常导致无限循环
- 单个心跳处理过多命令卡死

---

## 配置参考

```yaml
bridge:
  heartbeat:
    enabled: true
    velocity-url: "http://127.0.0.1:8788"
    api-key: "your-api-key"
    interval-seconds: 15      # 心跳间隔
    timeout-ms: 4000          # 超时时间
```

### 推荐配置（离线玩家多的服务器）

```yaml
bridge:
  heartbeat:
    interval-seconds: 5       # 缩短间隔，减少等待时间
    timeout-ms: 2000          # 缩短超时，快速失败
```

---

## 相关事件

- `skin:updated` - 皮肤更新事件（由 skin:update 命令触发）
- `skin:applied` - 皮肤应用到玩家上（由后端 `skinResolver.store()` 触发）
- `skin:refresh:request` - 皮肤刷新请求（由 `SkinService.refreshSkin()` 触发）

## 调试技巧

查看 mailbox 积压情况：

```log
# 心跳响应中包含：
X-StarX-Server: lobby
# 响应 body (Base64) 解码后 attributes.httpCommandsQueued
```

后端日志：

```
[StarX] 空服心跳准备就绪
[StarX] 心跳恢复
[StarX] 心跳失败；回退至 player-carried bridge
```