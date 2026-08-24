package io.github.addxiaoyi.starx.velocity.config;

import java.time.Duration;

/**
 * 插件自动更新配置。
 */
public record UpdateConfig(
    boolean enabled,
    String source,
    String githubOwner,
    String githubRepo,
    String mavenGroup,
    String mavenArtifact,
    int checkIntervalMinutes
) {
  public static final String SOURCE_GITHUB = "github";
  public static final String SOURCE_MAVEN = "maven";
  private static final int MIN_INTERVAL_MINUTES = 30;
  private static final int MAX_INTERVAL_MINUTES = 24 * 60;

  public UpdateConfig {
    source = source == null || source.isBlank() ? SOURCE_GITHUB : source.trim().toLowerCase();
    if (!SOURCE_GITHUB.equals(source) && !SOURCE_MAVEN.equals(source)) {
      throw new IllegalArgumentException("update.source must be github or maven");
    }
    if (checkIntervalMinutes < MIN_INTERVAL_MINUTES) {
      checkIntervalMinutes = MIN_INTERVAL_MINUTES;
    }
    if (checkIntervalMinutes > MAX_INTERVAL_MINUTES) {
      checkIntervalMinutes = MAX_INTERVAL_MINUTES;
    }
  }

  public static UpdateConfig disabled() {
    return new UpdateConfig(false, SOURCE_GITHUB, "", "", "", "", MIN_INTERVAL_MINUTES);
  }

  public Duration checkInterval() {
    return Duration.ofMinutes(this.checkIntervalMinutes);
  }

  public boolean isGitHubSource() {
    return SOURCE_GITHUB.equals(this.source);
  }
}
