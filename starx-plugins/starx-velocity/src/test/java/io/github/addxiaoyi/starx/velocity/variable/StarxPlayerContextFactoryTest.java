package io.github.addxiaoyi.starx.velocity.variable;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.common.model.PlayerBinding;
import io.github.addxiaoyi.starx.common.model.StarxUser;
import io.github.addxiaoyi.starx.velocity.identity.OfflineIdentityPolicy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class StarxPlayerContextFactoryTest {

  @Test
  void mapsLiveAccountBindingAndProxyStateIntoVariables() {
    UUID playerId = UUID.randomUUID();
    StarxUser user = new StarxUser(
        playerId,
        ".Alex",
        null,
        "hash",
        "totp-secret",
        false,
        Instant.parse("2026-07-01T00:00:00Z"),
        Instant.parse("2026-07-17T00:00:00Z"),
        null,
        List.of(),
        "",
        "local",
        "completed",
        null,
        "127.0.0.1",
        "",
        "本机",
        7200L,
        null,
        true);
    PlayerBinding binding = new PlayerBinding(playerId, "10001", null, 1L);
    StarxPlayerContextFactory factory = new StarxPlayerContextFactory(
        new OfflineIdentityPolicy("."), "前缀离线账号");

    StarxVariableService.PlayerContext context = factory.create(
        ".Alex", false, false, user, binding, "factions", 7);

    assertAll(
        () -> assertEquals(StarxVariableService.AuthState.AUTHENTICATED, context.authState()),
        () -> assertTrue(context.registered()),
        () -> assertTrue(context.totpEnabled()),
        () -> assertEquals("前缀离线账号", context.loginSource()),
        () -> assertTrue(context.qqBound()),
        () -> assertFalse(context.discordBound()),
        () -> assertEquals(7200L, context.playtimeSeconds()),
        () -> assertEquals("factions", context.serverName()),
        () -> assertEquals(7, context.onlinePlayers()));
  }

  @Test
  void unregisteredOfflinePlayerStaysInLoginState() {
    StarxPlayerContextFactory factory = new StarxPlayerContextFactory(
        new OfflineIdentityPolicy("."), "前缀离线账号");

    StarxVariableService.PlayerContext context = factory.create(
        "Alex", false, true, null, null, null, 1);

    assertEquals(StarxVariableService.AuthState.AWAITING_PASSWORD, context.authState());
    assertEquals("Java 离线账号", context.loginSource());
    assertFalse(context.registered());
  }
}
