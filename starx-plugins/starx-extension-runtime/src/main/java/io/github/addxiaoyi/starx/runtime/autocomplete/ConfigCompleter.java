package io.github.addxiaoyi.starx.runtime.autocomplete;

import io.github.addxiaoyi.starx.api.extension.StarxAutoCompleter;
import java.util.List;

/**
 * Provides auto-completion for StarX configuration keys.
 */
public final class ConfigCompleter implements StarxAutoCompleter {
  @Override
  public String id() {
    return "starx.config";
  }

  @Override
  public String displayName() {
    return "StarX Configuration";
  }

  @Override
  public List<String> contexts() {
    return List.of("config", "starx.config");
  }

  @Override
  public List<String> complete(String input) {
    if (input == null || input.isBlank()) {
      return List.of(
          "database.url",
          "database.username",
          "database.password",
          "server.name",
          "server.port",
          "auth.enabled",
          "auth.timeout",
          "skin.enabled",
          "skin.provider",
          "extension.enabled",
          "extension.auto-reload"
      );
    }
    String lower = input.toLowerCase();
    return List.of(
        "database." + lower,
        "server." + lower,
        "auth." + lower,
        "skin." + lower,
        "extension." + lower
    );
  }
}
