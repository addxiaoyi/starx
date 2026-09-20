package io.github.addxiaoyi.starx.website;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 网站同步的运行时指标收集器。
 * 记录请求数、失败数、熔断次数、缓存命中率等，用于运维观测。
 * 全部为无锁原子计数，开销极低。
 */
public final class SyncMetrics {
  private final AtomicLong heartbeatsSent = new AtomicLong();
  private final AtomicLong heartbeatsFailed = new AtomicLong();
  private final AtomicLong manifestsSent = new AtomicLong();
  private final AtomicLong manifestsFailed = new AtomicLong();
  private final AtomicLong texturesUploaded = new AtomicLong();
  private final AtomicLong texturesUploadFailed = new AtomicLong();
  private final AtomicLong requestsRejectedByLimit = new AtomicLong();
  private final AtomicLong requestsRejectedByCircuit = new AtomicLong();
  private final AtomicLong cacheHits = new AtomicLong();
  private final AtomicLong cacheMisses = new AtomicLong();
  private final AtomicInteger circuitOpenCount = new AtomicInteger();
  private volatile long lastHeartbeatLatencyMillis = -1;
  private volatile long lastManifestLatencyMillis = -1;
  private volatile long startedAtMillis = System.currentTimeMillis();

  public void recordHeartbeatSuccess(long latencyMillis) {
    this.heartbeatsSent.incrementAndGet();
    this.lastHeartbeatLatencyMillis = latencyMillis;
  }

  public void recordHeartbeatFailure() {
    this.heartbeatsFailed.incrementAndGet();
  }

  public void recordManifestSuccess(long latencyMillis) {
    this.manifestsSent.incrementAndGet();
    this.lastManifestLatencyMillis = latencyMillis;
  }

  public void recordManifestFailure() {
    this.manifestsFailed.incrementAndGet();
  }

  public void recordTextureUploadSuccess() {
    this.texturesUploaded.incrementAndGet();
  }

  public void recordTextureUploadFailure() {
    this.texturesUploadFailed.incrementAndGet();
  }

  public void recordRequestRejectedByLimit() {
    this.requestsRejectedByLimit.incrementAndGet();
  }

  public void recordRequestRejectedByCircuit() {
    this.requestsRejectedByCircuit.incrementAndGet();
  }

  public void recordCacheHit() {
    this.cacheHits.incrementAndGet();
  }

  public void recordCacheMiss() {
    this.cacheMisses.incrementAndGet();
  }

  public void recordCircuitOpened() {
    this.circuitOpenCount.incrementAndGet();
  }

  public Snapshot snapshot() {
    long hits = this.cacheHits.get();
    long misses = this.cacheMisses.get();
    long total = hits + misses;
    return new Snapshot(
        this.heartbeatsSent.get(),
        this.heartbeatsFailed.get(),
        this.manifestsSent.get(),
        this.manifestsFailed.get(),
        this.texturesUploaded.get(),
        this.texturesUploadFailed.get(),
        this.requestsRejectedByLimit.get(),
        this.requestsRejectedByCircuit.get(),
        total == 0 ? 0.0 : (double) hits / total,
        this.circuitOpenCount.get(),
        this.lastHeartbeatLatencyMillis,
        this.lastManifestLatencyMillis,
        System.currentTimeMillis() - this.startedAtMillis);
  }

  /** 指标的只读快照，供状态接口输出。 */
  public record Snapshot(
      long heartbeatsSent,
      long heartbeatsFailed,
      long manifestsSent,
      long manifestsFailed,
      long texturesUploaded,
      long texturesUploadFailed,
      long requestsRejectedByLimit,
      long requestsRejectedByCircuit,
      double cacheHitRate,
      int circuitOpenCount,
      long lastHeartbeatLatencyMillis,
      long lastManifestLatencyMillis,
      long uptimeMillis
  ) {
    @Override
    public String toString() {
      return "hb=" + heartbeatsSent + "/" + heartbeatsFailed
          + " mf=" + manifestsSent + "/" + manifestsFailed
          + " tx=" + texturesUploaded + "/" + texturesUploadFailed
          + " cache=" + String.format("%.1f%%", cacheHitRate * 100)
          + " circuit=" + circuitOpenCount
          + " rejected(limit/circuit)=" + requestsRejectedByLimit + "/" + requestsRejectedByCircuit
          + " uptime=" + (uptimeMillis / 1000) + "s";
    }
  }
}
