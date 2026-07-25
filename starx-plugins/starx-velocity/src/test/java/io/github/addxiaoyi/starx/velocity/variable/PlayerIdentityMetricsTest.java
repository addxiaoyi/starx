package io.github.addxiaoyi.starx.velocity.variable;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.addxiaoyi.starx.common.model.PlayerBinding;
import io.github.addxiaoyi.starx.common.model.StarxUser;
import io.github.addxiaoyi.starx.common.session.DisconnectReason;
import io.github.addxiaoyi.starx.common.session.PlayerSessionSummary;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class PlayerIdentityMetricsTest {
  @Test
  void aggregatesOnlyPersistedAccountAndSessionFacts() {
    UUID id = UUID.randomUUID();
    StarxUser user = new StarxUser(
        id, "Alex", null, "hash", "totp", true,
        Instant.parse("2025-01-01T00:00:00Z"), null, null, List.of(), "",
        "local", "completed", null, null, null, null, 3_600L, null, true);
    PlayerBinding binding = new PlayerBinding(id, "10001", null, 1L);
    PlayerSessionSummary summary = new PlayerSessionSummary(
        72_000_000L, 12, "survival-2", DisconnectReason.NORMAL);

    PlayerIdentityMetrics metrics = PlayerIdentityMetrics.from(
        user,
        binding,
        summary,
        Map.of("lobby", 5_000L, "survival-2", 71_995_000L, "empty", 0L),
        Instant.parse("2026-07-23T00:00:00Z"));

    assertEquals(72_000L, metrics.playtimeSeconds());
    assertEquals(2, metrics.serverFootprint());
    assertEquals(72, metrics.reputation());
    assertEquals("可信", metrics.trustLevel());
  }

  @Test
  void unregisteredPlayersAreExplicitlyUnrated() {
    PlayerIdentityMetrics metrics = PlayerIdentityMetrics.from(
        null, null, null, Map.of(), Instant.parse("2026-07-23T00:00:00Z"));

    assertEquals(0, metrics.reputation());
    assertEquals("未评级", metrics.trustLevel());
  }
}
