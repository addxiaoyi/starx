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
import java.util.concurrent.Semaphore;

/**
 * GitHub Releases 版本查询客户端。
 * 获取指定仓库的最新 Release 信息。
 */
public final class GitHubReleasesClient extends RepositoryClient {
  private final String owner;
  private final String repo;
  private final Semaphore concurrentRequests;
  private final SimpleCircuitBreaker circuitBreaker;

  public GitHubReleasesClient(String requestId, String owner, String repo) {
    this(requestId, owner, repo, 8, Duration.ofSeconds(5));
  }

    public GitHubReleasesClient(
      String requestId,
      String owner,
      String repo,
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
    this.owner = Objects.requireNonNull(owner, "owner");
    this.repo = Objects.requireNonNull(repo, "repo");
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
        "https://api.github.com/repos/" + this.owner + "/" + this.repo + "/releases/latest");
    try (InputStream stream = doFetch(uri)) {
      String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      return parseResponse(json);
    } catch (IOException error) {
      return Optional.empty();
    }
  }

  static Optional<VersionInfo> parseResponse(String json) {
    try {
      JsonObject root = JsonParser.parseString(json).getAsJsonObject();
      String tagName = root.get("tag_name").getAsString();
      String name = root.has("name") ? root.get("name").getAsString() : tagName;
      Version version = Version.parse(tagName);
      if (version == null) {
        return Optional.empty();
      }
      String body = root.has("body") ? root.get("body").getAsString() : "";

      // 从 assets 中找到 jar 下载链接
      String downloadUrl = null;
      JsonArray assets = root.getAsJsonArray("assets");
      for (JsonElement asset : assets) {
        JsonObject item = asset.getAsJsonObject();
        String assetName = item.get("name").getAsString();
        if (assetName.endsWith(".jar")) {
          downloadUrl = item.get("browser_download_url").getAsString();
          break;
        }
      }

      return Optional.of(new VersionInfo(version, name, body,
          downloadUrl != null ? URI.create(downloadUrl) : null));
    } catch (Exception error) {
      return Optional.empty();
    }
  }
}