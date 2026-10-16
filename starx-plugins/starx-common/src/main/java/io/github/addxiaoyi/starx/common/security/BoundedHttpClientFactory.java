package io.github.addxiaoyi.starx.common.security;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class BoundedHttpClientFactory {
  private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
      4, 4, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), threadFactory(),
      new ThreadPoolExecutor.AbortPolicy());

  static {
    EXECUTOR.allowCoreThreadTimeOut(true);
  }

  private BoundedHttpClientFactory() {
  }

  public static HttpClient.Builder builder(Duration connectTimeout) {
    return HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .version(HttpClient.Version.HTTP_1_1)
        .executor(EXECUTOR);
  }

  private static ThreadFactory threadFactory() {
    AtomicInteger sequence = new AtomicInteger();
    return task -> {
      Thread thread = new Thread(task, "starx-common-http-" + sequence.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }
}
