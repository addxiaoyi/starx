package io.github.addxiaoyi.starx.velocity.variable;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class StarxVariableServiceTest {

  private final StarxVariableService variables =
      new StarxVariableService(ZoneId.of("Asia/Shanghai"));

  @Test
  void resolvesLiveStarxValuesWithChinesePlayerFacingText() {
    StarxVariableService.PlayerContext player = new StarxVariableService.PlayerContext(
        "小星",
        StarxVariableService.AuthState.AUTHENTICATED,
        true,
        true,
        Instant.parse("2026-07-17T04:34:00Z"),
        "离线账号",
        true,
        false,
        3661,
        Instant.parse("2026-07-16T00:00:00Z"),
        "factions",
        42,
        100,
        3,
        50,
        4,
        72,
        "可信");

    assertAll(
        () -> assertEquals("小星", variables.resolve("starx_player", player)),
        () -> assertEquals("已登录", variables.resolve("starx_auth_status", player)),
        () -> assertEquals("是", variables.resolve("starx_registered", player)),
        () -> assertEquals("已开启", variables.resolve("starx_2fa_enabled", player)),
        () -> assertEquals("2026-07-17 12:34", variables.resolve("starx_last_login", player)),
        () -> assertEquals("离线账号", variables.resolve("starx_login_source", player)),
        () -> assertEquals("已绑定", variables.resolve("starx_bind_qq", player)),
        () -> assertEquals("未绑定", variables.resolve("starx_bind_discord", player)),
        () -> assertEquals("1 小时 1 分钟", variables.resolve("starx_playtime", player)),
        () -> assertEquals("2026-07-16 08:00", variables.resolve("starx_first_join", player)),
        () -> assertEquals("factions", variables.resolve("starx_server", player)),
        () -> assertEquals("42", variables.resolve("starx_online", player)),
        () -> assertEquals("42", variables.resolve("starx_network_online", player)),
        () -> assertEquals("3", variables.resolve("starx_server_online", player)),
        () -> assertEquals("1 小时 1 分钟", variables.resolve("starx_playtime_total", player)),
        () -> assertEquals("4", variables.resolve("starx_server_footprint", player)),
        () -> assertEquals("72", variables.resolve("starx_reputation", player)),
        () -> assertEquals("可信", variables.resolve("starx_trust_level", player)));
  }

  @Test
  void rendersBraceAndPercentSyntaxWithoutHidingUnknownVariables() {
    StarxVariableService.PlayerContext player = StarxVariableService.PlayerContext.guest("Alex", 3);

    String rendered = variables.render(
        "玩家 {starx_player} · %starx_auth_status% · {starx_online} · {unknown}", player);

    assertEquals("玩家 Alex · 待登录 · 3 · {unknown}", rendered);
  }

  @Test
  void missingAccountValuesHaveClearChineseFallbacks() {
    StarxVariableService.PlayerContext player = StarxVariableService.PlayerContext.guest("Alex", 1);

    assertAll(
        () -> assertEquals("从未", variables.resolve("starx_last_login", player)),
        () -> assertEquals("从未", variables.resolve("starx_first_join", player)),
        () -> assertEquals("未连接", variables.resolve("starx_server", player)),
        () -> assertEquals("0 分钟", variables.resolve("starx_playtime", player)),
        () -> assertEquals("0", variables.resolve("starx_server_footprint", player)),
        () -> assertEquals("0", variables.resolve("starx_reputation", player)),
        () -> assertEquals("未评级", variables.resolve("starx_trust_level", player)),
        () -> assertEquals("{not_registered}", variables.resolve("not_registered", player)));
  }

  @Test
  void publishesTheStableVariableCatalogForOptionalIntegrations() {
    assertEquals(
        Set.of(
            "starx_player", "starx_auth_status", "starx_registered",
            "starx_2fa_enabled", "starx_last_login", "starx_login_source",
            "starx_client_platform", "starx_bedrock",
            "starx_bind_qq", "starx_bind_discord", "starx_playtime",
            "starx_first_join", "starx_server", "starx_online",
            "starx_network_online", "starx_network_max", "starx_server_online",
            "starx_server_max", "starx_playtime_total", "starx_server_footprint",
            "starx_reputation", "starx_trust_level"),
        this.variables.keys());
  }
}
