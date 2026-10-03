package io.github.addxiaoyi.starx.common.update;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 仓库客户端共享的 HTTP 抓取工具。
 * 统一超时设置，避免线程长时间挂起。
 */
final class HttpFetchers {
  private static final java.net.http.HttpClient SHARED_CLIENT = java.net.http.HttpClient.newBuilder()
      .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
      .build();
  private static final int MAX_REDIRECTS = 3;

  private HttpFetchers() {
  }

  static InputStream fetchWithTimeout(URI uri, Duration timeout) throws IOException {
    return fetchWithTimeout(uri, timeout, "application/json", "StarX-UpdateChecker");
  }

  static InputStream fetchWithTimeout(
      URI uri, Duration timeout, String accept, String userAgent) throws IOException {
    URI current = requireHttps(uri);
    for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
      java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
          .uri(current)
          .timeout(timeout)
          .header("Accept", accept)
          .header("User-Agent", userAgent)
          .GET()
          .build();
      try {
        HttpResponse<InputStream> response = SHARED_CLIENT.send(
            request, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        if (isRedirect(response.statusCode())) {
          String location = response.headers().firstValue("Location").orElse(null);
          response.body().close();
          if (location == null || redirect == MAX_REDIRECTS) {
            throw new IOException("Unsafe or excessive update redirect for " + current);
          }
          current = resolveRedirect(current, location);
          continue;
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
          response.body().close();
          throw new IOException("HTTP " + response.statusCode() + " for " + current);
        }
        return response.body();
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new IOException("Request interrupted", error);
      }
    }
    throw new IOException("Unsafe or excessive update redirect");
  }

  static URI resolveRedirect(URI current, String location) throws IOException {
    try {
      return requireHttps(current.resolve(location));
    } catch (IllegalArgumentException error) {
      throw new IOException("Invalid update redirect", error);
    }
  }

  private static URI requireHttps(URI uri) {
    if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())
        || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
      throw new IllegalArgumentException("Update URL must be HTTPS without credentials or fragment");
    }
    return uri;
  }

  private static boolean isRedirect(int status) {
    return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
  }
}
