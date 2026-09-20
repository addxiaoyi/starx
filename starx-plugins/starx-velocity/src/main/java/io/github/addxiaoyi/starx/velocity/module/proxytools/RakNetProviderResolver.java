package io.github.addxiaoyi.starx.velocity.module.proxytools;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

final class RakNetProviderResolver {

  private RakNetProviderResolver() {
  }

  static Provider resolve(Set<String> pluginIds) {
    Set<String> normalized = new TreeSet<>();
    if (pluginIds != null) {
      for (String pluginId : pluginIds) {
        if (pluginId != null && !pluginId.isBlank()) {
          normalized.add(pluginId.trim().toLowerCase(Locale.ROOT));
        }
      }
    }
    if (normalized.contains("geyser") || normalized.contains("geyser-velocity")) {
      return Provider.GEYSER;
    }
    if (normalized.contains("raknetify")) {
      return Provider.RAKNETIFY;
    }
    return Provider.NONE;
  }

  enum Provider {
    GEYSER,
    RAKNETIFY,
    NONE
  }
}
