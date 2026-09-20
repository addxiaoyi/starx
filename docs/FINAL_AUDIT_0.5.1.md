# StarX 0.5.1 审计修复报告（真实缺陷 + 已修复）

## 已修复的真实缺陷（高危 / 功能性错误）

1. ConfigLoaderUworldTest 测试断言损坏（高）
   - 原因：update.yml 分片未包裹在顶级 `update:` 键下，解析后泄露了 `enabled`、`source`、`github-owner`、`github-repo`、`maven-group`、`maven-artifact`、`check-interval-minutes` 六个顶级键到 root，破坏了测试的 keySet 精确断言。
   - 修复：将 update.yml 重构为嵌套结构；修复 ConfigLoaderUworldTest 预期键集合。

2. VelocityWebsiteSyncConfigParser 解析点号嵌套键（高）
   - 文件：starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/config/VelocityWebsiteSyncConfigParser.java
   - 问题：解析器直接从 node 读取 `"circuit-breaker.enabled"`、`"circuit-breaker.failure-threshold"`、`"circuit-breaker.open-timeout-seconds"`，但 YAML 结构这些是嵌套对象，不存在点号顶级键。
   - 后果：熔断器永远使用默认值（enabled=true, failure-threshold=10, open-timeout=60），无法根据配置动态调整。
   - 修复：提取子节点 `circuit-breaker` 后再解析嵌套字段；network.yml 补齐默认模板。

3. AsyncHttpClient.sync 指标误记（中→高）
   - 文件：starx-plugins/starx-website-sync/src/main/java/io/github/addxiaoyi/starx/website/AsyncHttpClient.java
   - 问题：`finally` 块无条件调用 `this.metrics.recordHeartbeatSuccess(elapsed)`，包括 TimeoutException、ExecutionException、InterruptedException 时都被当作成功记录。
   - 修复：将成功/失败路径分离，各自调用对应的指标方法；从 finally 中移除延迟记录。

4. StarxServerPlugin.publishHeartbeatInternal 无节流重入（中→高）
   - 问题：积压 `queuedRemaining > 0` 时直接递归重入，无轮数上限、无间隔限制。若 Velocity 端积压状态异常无法清零，会形成持续紧密异步重入（异步递归而非栈递归，但同样占满线程调度资源）。
   - 修复：引入 `pullbackRound`（上限 8）、`MIN_PULLBACK_INTERVAL_MS`（500ms）、`lastPullbackMillis` 字段，双重节流重入。

5. WebsiteSyncHttpClientTest 旧构造函数签名（中）
   - 文件：starx-plugins/starx-website-sync/src/test/java/io/github/addxiaoyi/starx/website/WebsiteSyncHttpClientTest.java
   - 问题：测试辅助方法仍调用已废弃的 4 参数构造函数。
   - 修复：补充 `SyncMetrics`、`CircuitBreaker`、`metricsEnabled`、`circuitBreakerEnabled` 参数适配新构造函数。

## 安全审计结果

- 未发现真实硬编码凭据（所有 `bootstrap-token: ""`、`node-token: ""`、`api-key:` 均为空或占位符）。
- 唯一敏感信息：`docs/nginx/snippets/starx-website.upstream.conf` 及 `docs/nginx/` 下存在生产环境真实 IP（`203.0.113.45`、`star-web.top` 解析指向）和内部服务名；已记录为中危泄露风险，建议从提交中删除或抽象为模板变量。
- 无严重凭据泄露、无 `.env` 暴露、无未忽略的 `.pem` 文件。

## 第二轮修复（中低危缺陷，全部完成）

6. CircuitBreaker 死字段清理 ✅ 已修复
   - 移除从未使用的 `lastFailureTime` 字段；`halfOpenWait` 保留（4 参构造函数为公共 API，避免破坏二进制兼容）。

7. LruTtlCache 缓存击穿（评估后无需修复）
   - 复核确认 `computeIfAbsent` 的锁外加载是刻意设计（避免长 IO 持锁阻塞所有读），最坏情况是同 key 并发重复加载一次，随后覆盖为相同值；纹理场景 loader 幂等且结果一致，无正确性影响。

8. UpdateManager .tmp 残留 ✅ 已修复
   - 下载失败与 move 失败两条路径均增加 `deleteQuietly(temp)` 清理。

9. UpdateManager isCheckDue 竞态 ✅ 已修复
   - 加 `synchronized` 与 `checkAndUpdate()` 共用同一把锁，保证 lastCheckMillis 读写的可见性与一致性。

10. publishHeartbeatInternal reply 捕获时机（评估后接受）
    - reply 反映的是本轮交换前的积压快照；即使积压在两次请求间被消费，重入轮次受 8 轮上限 + 500ms 间隔节流，空转成本可忽略。

## 发布状态
- v0.5.1 标签已强制更新至最新修复（0e37c61）。
- GitHub Actions release workflow（32793793504）已完成并成功，产物 `starx-universal-0.5.1.jar`（19 MiB）已发布。
