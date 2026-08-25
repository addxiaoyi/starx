package io.github.addxiaoyi.starx.runtime.autocomplete;

import io.github.addxiaoyi.starx.api.extension.StarxAutoCompleter;
import io.github.addxiaoyi.starx.api.extension.StarxService;
import java.util.List;

/**
 * Provides auto-completion for StarX commands.
 */
public final class CommandCompleter implements StarxAutoCompleter {
  private final StarxService service;

  public CommandCompleter(StarxService service) {
    this.service = service;
  }

  @Override
  public String id() {
    return "starx.command";
  }

  @Override
  public String displayName() {
    return "StarX Commands";
  }

  @Override
  public List<String> contexts() {
    return List.of("command", "starx.command", "console");
  }

  @Override
  public List<String> complete(String input) {
    if (input == null || input.isBlank()) {
      return List.of(
          "/starx",
          "/starx reload",
          "/starx status",
          "/starx info",
          "/starx extensions",
          "/starx enable",
          "/starx disable",
          "/starx config"
      );
    }
    String lower = input.toLowerCase();
    if (lower.startsWith("/starx")) {
      return List.of(
          "/starx reload",
          "/starx status",
          "/starx info",
          "/starx extensions",
          "/starx enable ",
          "/starx disable ",
          "/starx config "
      );
    }
    return List.of();
  }

  @Override
  public String documentation(String suggestion) {
    return switch (suggestion) {
      case "/starx reload" -> "Reload all StarX extensions";
      case "/starx status" -> "Show StarX system status";
      case "/starx info" -> "Show StarX version information";
      case "/starx extensions" -> "List all loaded extensions";
      case "/starx enable" -> "Enable a specific extension";
      case "/starx disable" -> "Disable a specific extension";
      case "/starx config" -> "Show or modify StarX configuration";
      default -> null;
    };
  }
}
