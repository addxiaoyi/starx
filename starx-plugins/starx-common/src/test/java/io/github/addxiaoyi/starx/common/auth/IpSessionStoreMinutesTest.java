package io.github.addxiaoyi.starx.common.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.common.model.IpSession;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IpSessionStoreMinutesTest {
  private static final UUID PLAYER_ID = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");
  private static final String ADDRESS = "203.0.113.42";

  @Test
  void enforcesTheConfiguredWindowAtMinutePrecision() {
    InMemoryIpSessionStore recent = new InMemoryIpSessionStore();
    recent.save(session(Duration.ofMinutes(29), "device-a"));
    assertTrue(recent.hasRecentSessionMinutes(PLAYER_ID, ADDRESS, "device-a", 30));
    assertFalse(recent.hasRecentSessionMinutes(PLAYER_ID, ADDRESS, "device-b", 30));
    assertFalse(recent.hasRecentSessionMinutes(PLAYER_ID, ADDRESS, null, 30));

    InMemoryIpSessionStore expired = new InMemoryIpSessionStore();
    expired.save(session(Duration.ofMinutes(31), "device-a"));
    assertFalse(expired.hasRecentSessionMinutes(PLAYER_ID, ADDRESS, "device-a", 30));
    assertFalse(recent.hasRecentSessionMinutes(PLAYER_ID, ADDRESS, "device-a", 0));
  }

  @Test
  void trustedIdentitySessionsCannotSeedPasswordBypass() {
    InMemoryIpSessionStore store = new InMemoryIpSessionStore();
    store.save(new IpSession(
        PLAYER_ID, ADDRESS, null, null, Instant.now().toEpochMilli(), "premium", "device-a"));

    assertFalse(store.hasRecentSessionMinutes(PLAYER_ID, ADDRESS, "device-a", 30));
  }

  private static IpSession session(Duration age, String deviceFingerprint) {
    return new IpSession(
        PLAYER_ID,
        ADDRESS,
        null,
        null,
        Instant.now().minus(age).toEpochMilli(),
        "local",
        deviceFingerprint);
  }
}
