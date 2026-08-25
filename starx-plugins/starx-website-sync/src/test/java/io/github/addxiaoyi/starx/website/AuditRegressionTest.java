package io.github.addxiaoyi.starx.website;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 0.5.1 审计修复的回归测试：
 * - CircuitBreaker 状态机（OPEN 超时后进入 HALF_OPEN，成功恢复 CLOSED）
 * - LruTtlCache 的 TTL 过期、LRU 淘汰与容量边界
 */
class AuditRegressionTest {

  @Test
  void circuitBreakerStaysClosedBelowThreshold() {
    CircuitBreaker breaker = new CircuitBreaker(3, Duration.ofSeconds(60));
    for (int i = 0; i < 2; i++) {
      breaker.recordFailure();
    }
    assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
    assertTrue(breaker.allowRequest());
  }

  @Test
  void circuitBreakerOpensAtThresholdAndRejects() {
    CircuitBreaker breaker = new CircuitBreaker(3, Duration.ofSeconds(60));
    for (int i = 0; i < 3; i++) {
      breaker.recordFailure();
    }
    assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
    // 熔断超时未到，请求必须被拒绝
    assertFalse(breaker.allowRequest());
  }

  @Test
  void circuitBreakerSuccessResetsFailureCount() {
    CircuitBreaker breaker = new CircuitBreaker(3, Duration.ofSeconds(60));
    breaker.recordFailure();
    breaker.recordFailure();
    breaker.recordSuccess();
    assertEquals(0, breaker.getFailureCount());
    // 两次新失败不应触发熔断（计数已重置）
    breaker.recordFailure();
    breaker.recordFailure();
    assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
  }

  @Test
  void circuitBreakerHalfOpenAfterTimeoutThenRecovers() {
    // timeout=0ms：OPEN 后立即可半开
    CircuitBreaker breaker = new CircuitBreaker(1, Duration.ZERO);
    breaker.recordFailure();
    assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
    assertTrue(breaker.allowRequest());
    assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.getState());
    // 半开状态下成功 → 恢复 CLOSED
    breaker.recordSuccess();
    assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
    assertTrue(breaker.allowRequest());
  }

  @Test
  void circuitBreakerHalfOpenFailureReopens() {
    CircuitBreaker breaker = new CircuitBreaker(1, Duration.ZERO);
    breaker.recordFailure();
    assertTrue(breaker.allowRequest()); // 进入 HALF_OPEN
    breaker.recordFailure();            // 半开探测失败
    assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
  }

  @Test
  void cacheStoresAndRetrievesValues() {
    LruTtlCache<String, String> cache = new LruTtlCache<>(4, 60_000);
    long now = 1_000L;
    cache.put("a", "alpha", now);
    assertEquals("alpha", cache.get("a", now + 1));
    assertEquals(1, cache.size());
  }

  @Test
  void cacheExpiresEntriesAfterTtl() {
    LruTtlCache<String, String> cache = new LruTtlCache<>(4, 100);
    long now = 1_000L;
    cache.put("k", "v", now);
    assertEquals("v", cache.get("k", now + 99));
    assertNull(cache.get("k", now + 101));
    assertEquals(0, cache.size());
  }

  @Test
  void cacheEvictsLeastRecentlyUsedBeyondCapacity() {
    LruTtlCache<String, String> cache = new LruTtlCache<>(2, 60_000);
    long now = 1_000L;
    cache.put("a", "1", now);
    cache.put("b", "2", now);
    // 访问 a，使 b 成为最久未使用
    assertEquals("1", cache.get("a", now));
    cache.put("c", "3", now);
    assertEquals(2, cache.size());
    assertNull(cache.get("b", now));
    assertEquals("1", cache.get("a", now));
    assertEquals("3", cache.get("c", now));
  }

  @Test
  void cacheComputeIfAbsentLoadsOnceAndCaches() {
    LruTtlCache<String, String> cache = new LruTtlCache<>(4, 60_000);
    long now = 1_000L;
    int[] loads = {0};
    String first = cache.computeIfAbsent("x", now, key -> {
      loads[0]++;
      return "loaded";
    });
    String second = cache.computeIfAbsent("x", now + 10, key -> {
      loads[0]++;
      return "loaded-again";
    });
    assertEquals("loaded", first);
    assertEquals("loaded", second);
    assertEquals(1, loads[0]);
  }

  @Test
  void cacheComputeIfAbsentEvictsExpiredBeforeLoad() {
    LruTtlCache<String, String> cache = new LruTtlCache<>(4, 50);
    long now = 1_000L;
    cache.put("x", "old", now);
    int[] loads = {0};
    // 过期条目被移除，loader 被调用
    String result = cache.computeIfAbsent("x", now + 100, key -> {
      loads[0]++;
      return "fresh";
    });
    assertEquals("fresh", result);
    assertEquals(1, loads[0]);
    assertEquals("fresh", cache.get("x", now + 100));
  }
