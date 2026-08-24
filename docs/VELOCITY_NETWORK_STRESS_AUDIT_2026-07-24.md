# StarX Velocity 网络压力审计 — 2026-07-24

## 结论

当前 `starx-velocity.jar` 候选已在**本机独立、仅环回监听**的 Velocity 3.5.0-SNAPSHOT build 606 环境中完成有界协议对抗矩阵。

```text
ROBOT_CONNECTION_FLOOD=PASS
MALFORMED_VARINT_AND_PACKET_FLOOD=PASS
OVERSIZED_PACKET_DECLARATION=PASS
SLOW_PARTIAL_HANDSHAKE=PASS
POST_ATTACK_RESOURCE_RECOVERY=PASS
STARX_VELOCITY_NETWORK_STRESS=PASS
```

候选绑定：

```text
starx-velocity.jar
SHA-256 a4ce5bdff9868bc96af682c9b88c60dd917b4f2d5e64de1b83f91977d961a822
```

本结论证明候选在本轮有界、本机环回负载下能够拒绝或释放异常连接，并在每个阶段后继续完成正常 Minecraft 状态握手。它**不等于公网 DDoS 容量认证**，也不替代边缘防火墙、连接速率限制、反向代理或运营商清洗能力。

## 隔离边界

| 项目 | 值 |
|---|---|
| 游戏监听 | `127.0.0.1:25694` |
| StarX HTTP | `127.0.0.1:8794` |
| 不可达后端夹具 | `127.0.0.1:25695` |
| Minecraft 协议 | `774` / 1.21.11 |
| Velocity | `3.5.0-SNAPSHOT` build 606 |
| 运行目录 | `tmp/starx-velocity-network-20260724-112922-095` |

压测没有访问生产域名、生产 IP 或公网目标。所有进程、端口和数据目录均由本轮工装创建并在结束时清理。

## 对抗矩阵

| 场景 | 总量 | 并发 | 建连/发送成功 | 工装错误 | 阶段后状态握手 |
|---|---:|---:|---:|---:|---:|
| 连接洪泛 | 1,200 | 120 | 1,200 | 0 | PASS |
| 畸形 VarInt 与随机包 | 800 | 80 | 800 | 0 | PASS |
| 超长数据包长度声明 | 300 | 50 | 300 | 0 | PASS |
| 慢速半握手 | 96 | 48 | 96 | 0 | PASS |

正常状态握手在基线、每个场景结束后以及最终阶段均成功。基线和最终响应一致：

```text
protocol=774
version=Velocity 1.7.2-26.2
```

## 资源回落

攻击结束后冷却 12 秒，再读取同一 Velocity PID 的资源指标：

| 指标 | 攻击前 | 冷却后 | 结果 |
|---|---:|---:|---:|
| 线程 | 65 | 126 | 既定上限内 |
| 句柄 | 1,600 | 2,038 | 既定上限内 |
| 工作集 | 224,022,528 B | 258,404,352 B | 增量约 32.8 MiB |
| 私有字节 | 488,701,952 B | 506,884,096 B | 增量约 17.3 MiB |
| TCP 连接 | 2 | 2 | 完全回落到基线 |

日志扫描结果：

```text
OutOfMemoryError=0
StackOverflowError=0
unable to create native thread=0
too many open files=0
fatal error=0
shutting down the proxy=0
```

结束后复核：

```text
127.0.0.1:25694 released
127.0.0.1:8794 released
127.0.0.1:25695 released
```

## 工装

最终工装使用 Node.js 标准库 `net`，不依赖第三方攻击工具：

```text
tmp/velocity-network-adversary.mjs
tmp/run-current-velocity-network-stress.ps1
```

对抗器直接构造 Minecraft 握手和状态请求，并为每类异常流量设置有界总量、并发数、连接超时和 180 秒总守护时间。阶段结果同步写入运行目录日志，包装器负责候选哈希校验、独立进程、冷却指标、致命日志扫描与端口清理。

## 仍未覆盖

以下内容不能由本轮本机环回测试推导为 PASS：

```text
PUBLIC_INTERNET_DDOS_CAPACITY=UNVERIFIED
MULTI_HOST_DISTRIBUTED_LOAD=UNVERIFIED
REAL_EDGE_FIREWALL_RATE_LIMIT=UNVERIFIED
REAL_BEDROCK_PROTOCOL_FLOOD=UNVERIFIED
REAL_MODDED_CLIENT_NETWORK_MATRIX=UNVERIFIED
```

完整原始证据见：

```text
docs/evidence/2026-07-24-velocity-network-stress.log
tmp/starx-velocity-network-20260724-112922-095/network-adversary.log
tmp/starx-velocity-network-20260724-112922-095/metrics.json
```
