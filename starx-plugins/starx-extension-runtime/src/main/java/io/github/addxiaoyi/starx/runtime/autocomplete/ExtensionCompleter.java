package io.github.addxiaoyi.starx.runtime.autocomplete;

import io.github.addxiaoyi.starx.api.extension.StarxAutoCompleter;
import io.github.addxiaoyi.starx.api.extension.StarxExtensionSnapshot;
import io.github.addxiaoyi.starx.api.extension.StarxService;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Provides auto-completion for StarX extension identifiers.
 */
public final class ExtensionCompleter implements StarxAutoCompleter {
  private final StarxService service;

  public ExtensionCompleter(StarxService service) {
    this.service = service;
  }

  @Override
  public String id() {
    return "starx.extension";
  }

  @Override
  public String displayName() {
    return "StarX Extensions";
  }

  @Override
  public List<String> contexts() {
    return List.of("extension", "starx.extension");
  }

  @Override
  public List<String> complete(String input) {
    List<StarxExtensionSnapshot> extensions = service.extensions();
    if (input == null || input.isBlank()) {
      return extensions.stream()
          .map(s -> s.descriptor().id())
          .collect(Collectors.toList());
    }
    String lower = input.toLowerCase();
    return extensions.stream()
        .filter(s -> s.descriptor().id().toLowerCase().contains(lower))
        .map(s -> s.descriptor().id())
        .collect(Collectors.toList());
  }

  @Override
  public String documentation(String suggestion) {
    return service.extension(suggestion)
        .map(s -> s.descriptor().name() + " v" + s.descriptor().version())
        .orElse(null);
  }
}
