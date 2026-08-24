package io.github.addxiaoyi.starx.common.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.Semaphore;

/**
 * Maven Central 版本查询客户端。
 * 使用 Solr 搜索 API 获取最新版本。
 */
public final class MavenCentralClient extends RepositoryClient {
  private final String groupId;
  private final String artifactId;
  private final Semaphore concurrentRequests;
  private final SimpleCircuitBreaker circuitBreaker;

  public MavenCentralClient(String requestId, String groupId, String artifactId) {
    this(requestId, groupId, artifactId, 8, Duration.ofSeconds(5));
  }

  public MavenCentralClient(
      String requestId,
      String groupId,
      String artifactId,
      int maxConcurrent,
      Duration timeout
  ) {
    super(requestId, uri -> {
      try {
        return HttpFetchers.fetchWithTimeout(uri, timeout);
      } catch (IOException error) {
        throw new RuntimeException(error);
      }
    }, (int) timeout.toMillis());
    this.groupId = Objects.requireNonNull(groupId, "groupId");
    this.artifactId = Objects.requireNonNull(artifactId, "artifactId");
    this.concurrentRequests = new Semaphore(maxConcurrent);
    this.circuitBreaker = new SimpleCircuitBreaker(10, Duration.ofSeconds(30));
  }

  @Override
  public Optional<VersionInfo> fetchLatestVersion() {
    if (!this.circuitBreaker.allowRequest()) {
      return Optional.empty();
    }
    if (!this.concurrentRequests.tryAcquire()) {
      return Optional.empty();
    }
    try {
      Optional<VersionInfo> result = this.doFetchLatestVersion();
      this.circuitBreaker.recordSuccess();
      return result;
    } catch (Exception error) {
      this.circuitBreaker.recordFailure();
      return Optional.empty();
    } finally {
      this.concurrentRequests.release();
    }
  }

  private Optional<VersionInfo> doFetchLatestVersion() {
    URI uri = URI.create(
        "https://search.maven.org/solrsearch/select?q=g:%22" + urlEncode(this.groupId)
            + "%22+AND+a:%22" + urlEncode(this.artifactId) + "%22&rows=20&wt=json&core=gav");
    try (InputStream stream = doFetch(uri)) {
      String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      return parseResponse(json);
    } catch (IOException error) {
      return Optional.empty();
    }
  }

  private static String urlEncode(String value) {
    StringBuilder result = new StringBuilder();
    for (char c : value.toCharArray()) {
      if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.') {
        result.append(c);
      }
    }
    return result.toString();
  }

  static Optional<VersionInfo> parseResponse(String json) {
    try {
      JsonObject root = JsonParser.parseString(json).getAsJsonObject();
      JsonElement response = root.get("response");
      if (response == null || !response.isJsonObject()) {
        return Optional.empty();
      }
      JsonArray docs = response.getAsJsonObject().get("docs").getAsJsonArray();
      if (docs.isEmpty()) {
        return Optional.empty();
      }
      // 过滤掉 -SNAPSHOT 版本，取最新的正式版
      SortedSet<Version> versions = new TreeSet<>();
      JsonObject latestDoc = null;
      String latestVersionStr = null;
      for (JsonElement doc : docs) {
        JsonObject item = doc.getAsJsonObject();
        String versionStr = item.get("v").getAsString();
        if (versionStr.endsWith("-SNAPSHOT")) {
          continue;
        }
        Version parsed = Version.parse(versionStr);
        if (parsed == null) {
          continue;
        }
        if (versions.isEmpty() || parsed.compareTo(versions.last()) > 0) {
          versions.add(parsed);
          latestDoc = item;
          latestVersionStr = versionStr;
        }
      }
      if (latestDoc == null || latestVersionStr == null) {
        return Optional.empty();
      }
      return buildVersionInfo(versions.last(), latestVersionStr, latestDoc);
    } catch (RuntimeException error) {
      return Optional.empty();
    }
  }

  private static Optional<VersionInfo> buildVersionInfo(
      Version version, String versionStr, JsonObject item) {
    try {
      String g = item.get("g").getAsString();
      String a = item.get("a").getAsString();
      // 标准 Maven Central 下载 URL
      String url = "https://repo1.maven.org/maven2/"
          + g.replace('.', '/') + "/" + a + "/" + versionStr
          + "/" + a + '-' + versionStr + ".jar";
      return Optional.of(new VersionInfo(version, a + '-' + versionStr,
          item.toString(), URI.create(url)));
    } catch (RuntimeException error) {
      return Optional.empty();
    }
  }
}
