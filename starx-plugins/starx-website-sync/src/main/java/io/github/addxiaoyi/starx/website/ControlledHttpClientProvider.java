package io.github.addxiaoyi.starx.website;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * 受控 HTTP 客户端提供者。
 * 解决 JDK HttpClient Worker 线程无限增长的问题：
 * 1. 共享单例 HttpClient（连接池受控）
 * 2. 信号量限制最大并发请求数
 * 3. 统一超时设置（缩短连接+读取超时）
 * 4. 禁用自动重试（让调用方决策）
 * 
 * 第三方插件（SkinsRestorer 等）应当通过此类获取请求委托。
 */
public final class ControlledHttpClientProvider {
  private static final ControlledHttpClientProvider INSTANCE = new ControlledHttpClientProvider();
  
  private final HttpClient sharedClient;
  private final Semaphore concurrentRequests;
  private final int maxConcurrent;
  private final Duration connectTimeout;
  private final Duration requestTimeout;
  private final ThreadPoolExecutor executor;
  
  public static ControlledHttpClientProvider getInstance() {
    return INSTANCE;
  }
  
  private ControlledHttpClientProvider() {
    this(4, Duration.ofSeconds(3), Duration.ofSeconds(5));
  }
  
  public ControlledHttpClientProvider(int maxConcurrent, Duration connectTimeout, Duration requestTimeout) {
    if (maxConcurrent < 1) {
      throw new IllegalArgumentException("maxConcurrent must be positive");
    }
    this.maxConcurrent = maxConcurrent;
    this.concurrentRequests = new Semaphore(maxConcurrent);
    this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
    this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
    int workerLimit = Math.max(4, maxConcurrent);
    this.executor = new ThreadPoolExecutor(
        workerLimit,
        workerLimit,
        30,
        TimeUnit.SECONDS,
        new SynchronousQueue<>(),
        daemonThreadFactory(),
        new ThreadPoolExecutor.AbortPolicy());
    this.executor.allowCoreThreadTimeOut(true);
    this.sharedClient = HttpClient.newBuilder()
        .connectTimeout(this.connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .version(HttpClient.Version.HTTP_1_1)
        .executor(this.executor)
        .build();
  }

  private static ThreadFactory daemonThreadFactory() {
    AtomicInteger sequence = new AtomicInteger();
    return task -> {
      Thread thread = new Thread(task, "starx-website-http-" + sequence.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }

  public <T> HttpResponse<T> send(
      HttpRequest request,
      HttpResponse.BodyHandler<T> bodyHandler
  ) throws IOException, InterruptedException {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(bodyHandler, "bodyHandler");
    if (!this.concurrentRequests.tryAcquire()) {
      throw new IOException("HTTP concurrency limit reached: " + request.uri());
    }
    try {
      return this.sharedClient.send(request, bodyHandler);
    } finally {
      this.concurrentRequests.release();
    }
  }

  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request,
      HttpResponse.BodyHandler<T> bodyHandler
  ) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(bodyHandler, "bodyHandler");
    if (!this.concurrentRequests.tryAcquire()) {
      return CompletableFuture.failedFuture(
          new IOException("HTTP concurrency limit reached: " + request.uri()));
    }
    try {
      return this.sharedClient.sendAsync(request, bodyHandler)
          .whenComplete((ignored, error) -> this.concurrentRequests.release());
    } catch (RuntimeException error) {
      this.concurrentRequests.release();
      return CompletableFuture.failedFuture(error);
    }
  }
  
  /**
   * 执行 GET 请求到 texture URL，受限流+超时控制。
   */
  public byte[] fetchTexture(URI uri, Duration timeout) throws IOException, InterruptedException {
    if (!this.concurrentRequests.tryAcquire()) {
      throw new IOException("Texture fetch concurrent limit reached: " + uri);
    }
    try {
      HttpRequest request = HttpRequest.newBuilder(uri)
          .GET()
          .timeout(timeout != null ? timeout : this.requestTimeout)
          .header("Accept", "image/png")
          .build();
      
      HttpResponse<InputStream> response = this.sharedClient.send(
          request, HttpResponse.BodyHandlers.ofInputStream());
      
      if (response.statusCode() != 200) {
        response.body().close();
        throw new IOException("Texture endpoint returned HTTP " + response.statusCode());
      }
      
      try (InputStream body = response.body()) {
        return body.readNBytes(TextureBlob.MAX_BYTES + 1);
      }
    } finally {
      this.concurrentRequests.release();
    }
  }
  
  /**
   * 执行 GET 请求，受限流+超时控制。
   * @param successHandler 成功时返回的处理函数
   */
  public <T> T fetch(URI uri, Function<InputStream, T> contentHandler) throws IOException, InterruptedException {
    return fetch(uri, contentHandler, null);
  }
  
  public <T> T fetch(URI uri, Function<InputStream, T> contentHandler, Duration timeout) throws IOException, InterruptedException {
    if (!this.concurrentRequests.tryAcquire()) {
      throw new IOException("Request concurrent limit reached: " + uri);
    }
    try {
      HttpRequest request = HttpRequest.newBuilder(uri)
          .GET()
          .timeout(timeout != null ? timeout : this.requestTimeout)
          .build();
      
      HttpResponse<InputStream> response = this.sharedClient.send(
          request, HttpResponse.BodyHandlers.ofInputStream());
      
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        response.body().close();
        throw new IOException("HTTP " + response.statusCode() + " for " + uri);
      }
      
      try (InputStream body = response.body()) {
        return contentHandler.apply(body);
      }
    } finally {
      this.concurrentRequests.release();
    }
  }
  
  /**
   * 获取当前可用并发槽位数。
   */
  public int availablePermits() {
    return this.concurrentRequests.availablePermits();
  }

  public int maxConcurrent() {
    return this.maxConcurrent;
  }

  public int activeRequests() {
    return this.maxConcurrent - this.concurrentRequests.availablePermits();
  }

  public int workerThreads() {
    return this.executor.getPoolSize();
  }

  public WebSocket.Builder newWebSocketBuilder() {
    return this.sharedClient.newWebSocketBuilder();
  }
  
  /**
   * 重置信号量（在熔断器需要时调用）。
   */
  public void resetConcurrency() {
    this.concurrentRequests.drainPermits();
    for (int i = 0; i < this.maxConcurrent; i++) {
      this.concurrentRequests.release();
    }
  }
}
