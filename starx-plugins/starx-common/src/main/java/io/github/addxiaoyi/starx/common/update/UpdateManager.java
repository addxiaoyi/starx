package io.github.addxiaoyi.starx.common.update;

import io.github.addxiaoyi.starx.common.security.HttpConstants;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 插件自动更新管理器。
 * 定期检查版本仓库，发现新版本时下载到更新目录，等待下次重启生效。
 * 
 * 安全设计：
 * - 信号量限制并发请求
 * - 熔断器防止仓库不可用时反复重试
 * - 检查间隔有最小值保护（避免频繁轮询）
 */
public final class UpdateManager {
  private static final Duration MIN_CHECK_INTERVAL = Duration.ofMinutes(30);
  private static final long MAX_JAR_SIZE_BYTES = 64L * 1024 * 1024; // 64 MiB
  private final String currentVersion;
  private final RepositoryClient repository;
  private final Path updateDirectory;
  private final Consumer<String> logger;
  private final long maxJarSizeBytes;
  private volatile long lastCheckMillis = 0;
  private volatile String latestKnownVersion = "";

  public UpdateManager(
      String currentVersion,
      RepositoryClient repository,
      Path updateDirectory,
      Consumer<String> logger
  ) {
    this(currentVersion, repository, updateDirectory, logger, MAX_JAR_SIZE_BYTES);
  }

  UpdateManager(
      String currentVersion,
      RepositoryClient repository,
      Path updateDirectory,
      Consumer<String> logger,
      long maxJarSizeBytes
  ) {
    this.currentVersion = Objects.requireNonNull(currentVersion, "currentVersion");
    this.repository = Objects.requireNonNull(repository, "repository");
    this.updateDirectory = Objects.requireNonNull(updateDirectory, "updateDirectory");
    this.logger = logger == null ? ignored -> { } : logger;
    this.maxJarSizeBytes = maxJarSizeBytes;
  }

  public enum CheckResult {
    UP_TO_DATE,
    UPDATE_AVAILABLE,
    UPDATE_DOWNLOADED,
    CHECK_FAILED,
    DOWNLOAD_FAILED
  }

  /**
   * 检查并尝试下载更新。
   * @return 更新检查结果
   */
  public synchronized CheckResult checkAndUpdate() {
    Optional<RepositoryClient.VersionInfo> latest = this.repository.fetchLatestVersion();
    if (latest.isEmpty()) {
      return CheckResult.CHECK_FAILED;
    }
    RepositoryClient.VersionInfo info = latest.get();
    this.lastCheckMillis = System.currentTimeMillis();
    this.latestKnownVersion = info.version().raw();

    if (!info.isNewerThan(this.currentVersion)) {
      return CheckResult.UP_TO_DATE;
    }
    if (info.downloadUrl() == null) {
      this.logger.accept("StarX update available but no download URL: "
          + info.version().raw());
      return CheckResult.UPDATE_AVAILABLE;
    }
    return this.download(info);
  }

  private CheckResult download(RepositoryClient.VersionInfo info) {
    try {
      Files.createDirectories(this.updateDirectory);
      String fileName = sanitizeFileName(info.name());
      if (!fileName.endsWith(".jar")) {
        fileName = fileName + ".jar";
      }
      Path target = this.updateDirectory.resolve(fileName);
      Path temp = this.updateDirectory.resolve(fileName + ".tmp");

      DownloadResult result = fetchToFile(info.downloadUrl(), temp);
      if (!result.success()) {
        deleteQuietly(temp);
        this.logger.accept("StarX update download failed for "
            + info.version().raw() + ": " + result.errorMessage());
        return CheckResult.DOWNLOAD_FAILED;
      }
      if (info.hasSha256() && !info.sha256().equalsIgnoreCase(result.sha256())) {
        deleteQuietly(temp);
        this.logger.accept("StarX update checksum mismatch for " + info.version().raw());
        return CheckResult.DOWNLOAD_FAILED;
      }
      // Prefer an atomic replacement, but keep updates working on filesystems without ATOMIC_MOVE.
      try {
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
        try {
          Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException moveError) {
          deleteQuietly(temp);
          throw moveError;
        }
      } catch (IOException moveError) {
        deleteQuietly(temp);
        throw moveError;
      }
      this.logger.accept("StarX update " + info.version().raw()
          + " downloaded to " + target.getFileName()
          + "; restart to apply (" + (result.bytes() / 1024) + " KiB)");
      return CheckResult.UPDATE_DOWNLOADED;
    } catch (IOException | RuntimeException error) {
      this.logger.accept("StarX update I/O failed: " + error.getClass().getSimpleName());
      return CheckResult.DOWNLOAD_FAILED;
    }
  }

  private static void deleteQuietly(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException ignored) {
      // 清理失败不影响主流程；残留文件会在下次下载时被覆盖
    }
  }

  private record DownloadResult(boolean success, long bytes, String errorMessage, String sha256) {
    static DownloadResult ok(long bytes, String sha256) {
      return new DownloadResult(true, bytes, "", sha256);
    }

    static DownloadResult fail(String message) {
      return new DownloadResult(false, 0, message, "");
    }
  }


  private DownloadResult fetchToFile(URI uri, Path target) throws IOException {
    if (uri == null || uri.getHost() == null
        || !"https".equalsIgnoreCase(uri.getScheme())
        || uri.getUserInfo() != null || uri.getFragment() != null) {
      return DownloadResult.fail("download URL must be HTTPS without credentials or fragment");
    }
    try {
      MessageDigest digest;
      try {
        digest = MessageDigest.getInstance("SHA-256");
      } catch (java.security.NoSuchAlgorithmException error) {
        throw new IOException("SHA-256 unavailable", error);
      }
      long total = 0;
      try (InputStream body = HttpFetchers.fetchWithTimeout(
               uri,
               HttpConstants.UPDATE_REQUEST_TIMEOUT,
               "application/java-archive, application/octet-stream;q=0.9",
               "StarX-Updater");
           var out = Files.newOutputStream(target)) {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = body.read(buffer)) >= 0) {
          total += read;
          digest.update(buffer, 0, read);
          if (total > this.maxJarSizeBytes) {
            return DownloadResult.fail("download exceeds size limit");
          }
          out.write(buffer, 0, read);
        }
      }
      if (total == 0) {
        return DownloadResult.fail("empty body");
      }
      return DownloadResult.ok(total, HexFormat.of().formatHex(digest.digest()));
    } catch (IllegalArgumentException error) {
      return DownloadResult.fail(error.getMessage());
    }
  }

  /**
   * 距上次检查是否已超过指定间隔。
   */
  /**
   * 距上次检查是否已超过指定间隔。
   * 与 {@link #checkAndUpdate()} 共用同一把锁，保证读取 lastCheckMillis 的可见性与一致性。
   */
  public synchronized boolean isCheckDue(Duration interval) {
    Duration effective = interval.compareTo(MIN_CHECK_INTERVAL) < 0 ? MIN_CHECK_INTERVAL : interval;
    if (this.lastCheckMillis == 0) {
      return true;
    }
    long elapsed = System.currentTimeMillis() - this.lastCheckMillis;
    return elapsed >= effective.toMillis();
  }

  public String latestKnownVersion() {
    return this.latestKnownVersion;
  }

  private static String sanitizeFileName(String name) {
    String cleaned = Objects.requireNonNullElse(name, "").trim();
    StringBuilder result = new StringBuilder();
    for (char c : cleaned.toCharArray()) {
      if (Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_') {
        result.append(c);
      }
    }
    return result.isEmpty() ? "starx-update" : result.toString();
  }
}
