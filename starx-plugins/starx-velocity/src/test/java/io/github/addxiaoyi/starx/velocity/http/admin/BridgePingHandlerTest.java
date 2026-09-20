package io.github.addxiaoyi.starx.velocity.http.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class BridgePingHandlerTest {
  private static final Clock CLOCK = Clock.fixed(
      Instant.parse("2026-07-30T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void acknowledgesAValidSideEffectFreeWebsiteMessage() {
    String nonce = UUID.randomUUID().toString();

    BridgePingHandler.PingAck ack = BridgePingHandler.acknowledge(
        nonce,
        "star-web.top bridge test",
        "2026-07-29T23:59:59Z",
        CLOCK);

    assertEquals(nonce, ack.nonce());
    assertEquals("star-web.top bridge test", ack.message());
    assertEquals("2026-07-29T23:59:59Z", ack.sentAt());
    assertEquals("2026-07-30T00:00:00Z", ack.receivedAt());
  }

  @Test
  void rejectsMalformedNonceAndTimestamp() {
    IllegalArgumentException nonce = assertThrows(
        IllegalArgumentException.class,
        () -> BridgePingHandler.acknowledge("not-a-uuid", "test", "", CLOCK));
    assertEquals("invalid_nonce", nonce.getMessage());

    IllegalArgumentException sentAt = assertThrows(
        IllegalArgumentException.class,
        () -> BridgePingHandler.acknowledge(
            UUID.randomUUID().toString(), "test", "yesterday", CLOCK));
    assertEquals("invalid_sent_at", sentAt.getMessage());
  }

  @Test
  void rejectsOversizedAndControlCharacterMessages() {
    String nonce = UUID.randomUUID().toString();
    IllegalArgumentException oversized = assertThrows(
        IllegalArgumentException.class,
        () -> BridgePingHandler.acknowledge(nonce, "x".repeat(257), "", CLOCK));
    assertEquals("invalid_message", oversized.getMessage());

    IllegalArgumentException control = assertThrows(
        IllegalArgumentException.class,
        () -> BridgePingHandler.acknowledge(nonce, "line\nbreak", "", CLOCK));
    assertEquals("invalid_message", control.getMessage());
  }

  @Test
  void allowsAnEmptyBoundedMessage() {
    BridgePingHandler.PingAck ack = BridgePingHandler.acknowledge(
        UUID.randomUUID().toString(), null, null, CLOCK);
    assertTrue(ack.message().isEmpty());
    assertTrue(ack.sentAt().isEmpty());
  }
}
