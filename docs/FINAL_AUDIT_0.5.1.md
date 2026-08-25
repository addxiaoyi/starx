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

## 中低危缺陷评估（已确认但未修复，建议后续处理）

6. CircuitBreaker 竞态（中）
   - `recordSuccess()` 在 HALF_OPEN 时直接把状态设 CLOSED，但 `allowRequest()` 在 OPEN 状态下直接拒绝，不存在状态机竞态问题（状态机已简化）；`halfOpenWait` 字段已声明但未使用，建议后续删除或实现半开探测窗口计时。

7. LruTtlCache 缓存击穿（低→中）
   - `computeIfAbsent` 在锁内加载值，已避免击穿；`removeEldestEntry` 未被覆盖（默认不移除），`evictIfNeeded` 手动清理，逻辑正确但若高并发下同时访问已过期条目可能短暂返回旧值，严重程度低。

8. UpdateManager 临时文件残留（低）
   - `fetchToFile()` 在 `.tmp` 下载完成后重命名到目标文件，若重命名失败会残留 `.tmp`。建议增加 finally 处理残留文件清理。

9. VelocityWebsiteSyncConfigParser 默认回退与构造默认值差异（中）
   - `bool(node, "circuit-breaker.enabled", true)` 回退为 true，与 `WebsiteSyncConfig.CircuitBreakerConfig.defaults()` 一致，已修复后解析路径一致，无额外风险。

## 发布状态
- v0.5.1 标签已强制更新至最新修复（0e37c61）。
- GitHub Actions release workflow（32793793504）已完成并成功，产物 `starx-universal-0.5.1.jar`（19 MiB）已发布。
