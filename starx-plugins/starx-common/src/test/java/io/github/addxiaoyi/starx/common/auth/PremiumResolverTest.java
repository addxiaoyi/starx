package io.github.addxiaoyi.starx.common.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PremiumResolverTest {
  private static final UUID ONLINE_UUID = UUID.fromString("123e4567-e89b-42d3-a456-426614174000");
  private static final UUID OFFLINE_UUID = UUID.nameUUIDFromBytes("OfflinePlayer:add".getBytes());

  @Test
  void acceptsOnlyOnlineModeVersionFourIdentity() {
    PremiumResolver resolver = new PremiumResolver();

    assertTrue(resolver.isPremium(ONLINE_UUID, true));
    assertFalse(resolver.isPremium(ONLINE_UUID, false));
    assertFalse(resolver.isPremium(OFFLINE_UUID, true));
  }

  @Test
  void offlineConnectionNeverTurnsIntoPremiumBypass() {
    PremiumResolver resolver = new PremiumResolver();

    assertFalse(resolver.isPremium(ONLINE_UUID, false));
    assertFalse(resolver.isPremium(OFFLINE_UUID, false));
  }
}
