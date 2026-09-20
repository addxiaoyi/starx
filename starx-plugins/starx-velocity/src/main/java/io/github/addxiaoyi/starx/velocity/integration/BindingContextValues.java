package io.github.addxiaoyi.starx.velocity.integration;

import io.github.addxiaoyi.starx.common.model.PlayerBinding;
import java.util.Map;

public record BindingContextValues(boolean qqBound, boolean discordBound) {

  public static BindingContextValues from(PlayerBinding binding) {
    if (binding == null) {
      return empty();
    }
    return new BindingContextValues(hasText(binding.qqId()), hasText(binding.discordId()));
  }

  public static BindingContextValues empty() {
    return new BindingContextValues(false, false);
  }

  public Map<String, String> asMap() {
    return Map.of(
        "qq-bound", Boolean.toString(this.qqBound),
        "discord-bound", Boolean.toString(this.discordBound));
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
