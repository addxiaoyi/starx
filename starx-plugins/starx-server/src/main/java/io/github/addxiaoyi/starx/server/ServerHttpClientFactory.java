package io.github.addxiaoyi.starx.server;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class ServerHttpClientFactory {
  private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
      4,
      4,
      30,
      TimeUnit.SECONDS,
      new ArrayBlockingQueue<>(32),
      threadFactory(),
      new ThreadPoolExecutor.AbortPolicy());

  static {
    EXECUTOR.allowCoreThreadTimeOut(true);
  }

  private ServerHttpClientFactory() {
  }

  static HttpClient.Builder builder(Duration connectTimeout) {
    return HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .version(HttpClient.Version.HTTP_1_1)
        .executor(EXECUTOR);
  }

  private static ThreadFactory threadFactory() {
    AtomicInteger sequence = new AtomicInteger();
    return task -> {
      Thread thread = new Thread(task, "starx-server-http-" + sequence.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }
}