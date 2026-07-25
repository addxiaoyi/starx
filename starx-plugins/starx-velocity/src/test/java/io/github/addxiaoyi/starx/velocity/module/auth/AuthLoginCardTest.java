package io.github.addxiaoyi.starx.velocity.module.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.common.model.StarxUser;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

final class AuthLoginCardTest {

  @Test
  void showsLoginDetailsAndClickableBindingLink() {
    UUID uuid = UUID.fromString("4f06bce0-32d7-4d4d-bb17-9f7e92ae8701");
    StarxUser user = new StarxUser(
        uuid, "Alex", null, "hash", null, false,
        Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-07-16T12:00:00Z"),
        null, List.of(), "", "starx", "completed", null,
        "203.0.113.8", "", "", 3661L, null, false);

    Component card = AuthLoginCard.render(
        user, "198.51.100.7", "factions",
        "https://star-web.top/profile?bind=token-1");
    String text = PlainTextComponentSerializer.plainText().serialize(card);

    assertTrue(text.contains("198.51.100.7"));
    assertTrue(text.contains(uuid.toString()));
    assertTrue(text.contains("1 小时 1 分钟"));
    TextComponent link = (TextComponent) card.children().get(card.children().size() - 1);
    assertEquals(ClickEvent.Action.OPEN_URL, link.clickEvent().action());
  }
}
