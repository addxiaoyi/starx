package io.github.addxiaoyi.starx.common.auth.uniauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UniAuthConfigTest {
  @Test
  void profileSyncDefaultsAreSafe() {
    UniAuthConfig config = UniAuthConfig.defaults();
    assertFalse(config.profileSync().enabled());
    assertTrue(config.profileSync().onLogin());
    assertFalse(config.profileSync().overwriteLocalValues());
    assertEquals("uniauth", config.profileSync().sourceSystem());
  }

  @Test
  void normalizesUrlSourceAndRedactsKey() {
    UniAuthConfig config = new UniAuthConfig(
        true,
        "https://auth.example.test/api",
        "super-secret",
        3000,
        true,
        new UniAuthConfig.ProfileSyncConfig(
            true, true, true, true, false, "AuthX Legacy"));
    assertEquals("https://auth.example.test/api/", config.apiUrl());
    assertEquals("uniauth", config.profileSync().sourceSystem());
    assertFalse(config.toString().contains("super-secret"));
  }
}
