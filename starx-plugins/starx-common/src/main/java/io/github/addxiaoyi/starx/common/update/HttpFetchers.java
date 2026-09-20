package io.github.addxiaoyi.starx.common.update;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;

/**
 * 仓库客户端共享的 HTTP 抓取工具。
 * 统一超时设置，避免线程长时间挂起。
 */
final class HttpFetchers {
  private static final java.net.http.HttpClient SHARED_CLIENT = java.net.http.HttpClient.newBuilder()
      .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
      .build();

  private HttpFetchers() {
  }

  static InputStream fetchWithTimeout(URI uri, Duration timeout) throws IOException {
    java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
        .uri(uri)
        .timeout(timeout)
        .header("Accept", "application/json")
        .header("User-Agent", "StarX-UpdateChecker")
        .GET()
        .build();
    try {
      java.net.http.HttpResponse<InputStream> response = SHARED_CLIENT.send(
          request, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        response.body().close();
        throw new IOException("HTTP " + response.statusCode() + " for " + uri);
      }
      return response.body();
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IOException("Request interrupted", error);
    }
  }
}
