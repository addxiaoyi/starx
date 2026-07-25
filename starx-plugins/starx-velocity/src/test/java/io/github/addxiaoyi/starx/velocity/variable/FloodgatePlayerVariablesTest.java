package io.github.addxiaoyi.starx.velocity.variable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.velocity.identity.OfflineIdentityPolicy;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class FloodgatePlayerVariablesTest {

  @Test
  void exposesBedrockPlatformAndFloodgateLoginSource() {
    UUID playerId = UUID.randomUUID();
    StarxPlayerContextFactory factory = new StarxPlayerContextFactory(
        new OfflineIdentityPolicy("."),
        "前缀离线账号",
        candidate -> candidate.equals(playerId));
    StarxVariableService.PlayerContext context = factory.create(
        playerId,
        "BedrockAlex",
        false,
        false,
        null,
        null,
        "lobby",
        1,
        20,
        1,
        20,
        PlayerIdentityMetrics.from(null, null, null, Map.of(), Instant.now()));
    StarxVariableService variables = new StarxVariableService(ZoneId.of("Asia/Shanghai"));

    assertTrue(context.bedrock());
    assertEquals("基岩版", context.clientPlatform());
    assertEquals("基岩版 Floodgate", context.loginSource());
    assertEquals("基岩版", variables.resolve("starx_client_platform", context));
    assertEquals("是", variables.resolve("starx_bedrock", context));
  }

  @Test
  void keepsJavaPlayersOnJavaPlatform() {
    UUID playerId = UUID.randomUUID();
    StarxPlayerContextFactory factory = new StarxPlayerContextFactory(
        new OfflineIdentityPolicy("."), "前缀离线账号", candidate -> false);
    StarxVariableService.PlayerContext context = factory.create(
        playerId, "Alex", true, false, null, null, "lobby", 1, 20, 1, 20,
        PlayerIdentityMetrics.from(null, null, null, Map.of(), Instant.now()));

    assertFalse(context.bedrock());
    assertEquals("Java版", context.clientPlatform());
    assertEquals("Java 正版账号", context.loginSource());
  }
}
