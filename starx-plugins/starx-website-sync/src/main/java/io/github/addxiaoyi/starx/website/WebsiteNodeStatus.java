package io.github.addxiaoyi.starx.website;

import java.util.Locale;

public enum WebsiteNodeStatus {
  ONLINE,
  OFFLINE,
  DEGRADED,
  MAINTENANCE,
  UNKNOWN;

  public String wireName() {
    return this.name().toLowerCase(Locale.ROOT);
  }
}
