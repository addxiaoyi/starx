package io.github.addxiaoyi.starx.velocity.identity;

import java.util.Objects;

public final class OfflineIdentityPolicy {

  private final String prefix;

  public OfflineIdentityPolicy(String prefix) {
    String normalized = Objects.requireNonNull(prefix, "prefix").trim();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException("离线身份前缀不能为空");
    }
    this.prefix = normalized;
  }

  public String prefix() {
    return this.prefix;
  }

  public boolean isPrefixed(String username) {
    return username != null
        && username.length() > this.prefix.length()
        && username.startsWith(this.prefix);
  }
}
