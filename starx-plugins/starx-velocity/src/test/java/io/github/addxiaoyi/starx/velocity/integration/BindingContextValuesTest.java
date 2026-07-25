package io.github.addxiaoyi.starx.velocity.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.addxiaoyi.starx.common.model.PlayerBinding;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class BindingContextValuesTest {

  @Test
  void exposesStableLuckPermsContextValuesForPresentAndMissingBindings() {
    UUID playerId = UUID.randomUUID();

    assertEquals(
        Map.of("qq-bound", "true", "discord-bound", "false"),
        BindingContextValues.from(new PlayerBinding(playerId, "10001", null, 1L)).asMap());
    assertEquals(
        Map.of("qq-bound", "false", "discord-bound", "false"),
        BindingContextValues.empty().asMap());
  }
}
