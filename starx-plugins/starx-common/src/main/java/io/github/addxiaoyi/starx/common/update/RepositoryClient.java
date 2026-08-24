package io.github.addxiaoyi.starx.common.update;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * 版本仓库客户端抽象。
 * 由具体实现（Maven Central / GitHub Releases）提供最新版本信息。
 */
public abstract class RepositoryClient {
  /**
   * 最新版本信息。
   */
  public record VersionInfo(
      Version version,
      String name,
      String rawJson,
      URI downloadUrl
  ) {
    public boolean isNewerThan(String currentVersion) {
      Version current = Version.parse(currentVersion);
      return this.version.isNewerThan(current);
    }
  }

  protected final String requestId;
  protected final FunctionForFetch fetcher;
  protected final int timeoutMs;

  protected interface FunctionForFetch {
    InputStream apply(URI uri) throws IOException;
  }

  protected RepositoryClient(String requestId, FunctionForFetch fetcher, int timeoutMs) {
    this.requestId = Objects.requireNonNull(requestId, "requestId");
    this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
    this.timeoutMs = timeoutMs;
  }

  /**
   * 获取该仓库的最新版本信息。
   * @return 最新版本；仓库不可达时返回 Optional.empty()
   */
  public abstract Optional<VersionInfo> fetchLatestVersion();

  protected final InputStream doFetch(URI uri) throws IOException {
    return this.fetcher.apply(uri);
  }
}