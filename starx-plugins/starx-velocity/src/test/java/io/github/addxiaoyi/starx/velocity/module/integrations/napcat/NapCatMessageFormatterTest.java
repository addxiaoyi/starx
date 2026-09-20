package io.github.addxiaoyi.starx.velocity.module.integrations.napcat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

class NapCatMessageFormatterTest {
  @Test
  void formatsQqMessageWithoutLegacyComponentTypes() {
    Component expected = Component.text()
        .append(Component.text("[QQ] ", NamedTextColor.AQUA))
        .append(Component.text("Alice", NamedTextColor.YELLOW))
        .append(Component.text(": ", NamedTextColor.WHITE))
        .append(Component.text("hello", NamedTextColor.WHITE))
        .build();

    assertEquals(expected, NapCatModule.formatQqMessage("Alice", "hello"));
  }
}
