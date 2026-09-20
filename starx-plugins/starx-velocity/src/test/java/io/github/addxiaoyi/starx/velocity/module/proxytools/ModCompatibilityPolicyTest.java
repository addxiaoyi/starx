package io.github.addxiaoyi.starx.velocity.module.proxytools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ModCompatibilityPolicyTest {

  @Test
  void allowsKnownClientOnlyHelpersOnConfiguredVanillaServer() {
    ModCompatibilityPolicy policy = new ModCompatibilityPolicy(
        Set.of("lobby"),
        ModCompatibilityPolicy.defaultAllowedClientOnlyMods(),
        Set.of(),
        ModCompatibilityPolicy.Action.DENY);
    ClientModProfile profile = new ClientModProfile(
        ClientModProfile.Loader.FABRIC,
        "fabric",
        Map.of("fabricloader", "0.16", "sodium", "1", "xaerominimap", "1"));

    assertEquals(ModCompatibilityPolicy.Action.ALLOW, policy.evaluate("lobby", profile).action());
  }

  @Test
  void deniesExplicitlyBlockedModAndWarnsForUnknownByDefault() {
    ModCompatibilityPolicy policy = new ModCompatibilityPolicy(
        Set.of("lobby"),
        Set.of("sodium"),
        Set.of("packet-crasher"),
        ModCompatibilityPolicy.Action.WARN);

    ModCompatibilityPolicy.Decision denied = policy.evaluate(
        "lobby",
        new ClientModProfile(ClientModProfile.Loader.FABRIC, "fabric",
            Map.of("fabricloader", "1", "packet-crasher", "1")));
    ModCompatibilityPolicy.Decision warned = policy.evaluate(
        "lobby",
        new ClientModProfile(ClientModProfile.Loader.FABRIC, "fabric",
            Map.of("fabricloader", "1", "unknown-helper", "1")));

    assertEquals(ModCompatibilityPolicy.Action.DENY, denied.action());
    assertTrue(denied.flaggedMods().contains("packet-crasher"));
    assertEquals(ModCompatibilityPolicy.Action.WARN, warned.action());
    assertTrue(warned.flaggedMods().contains("unknown-helper"));
  }

  @Test
  void neverAppliesVanillaPolicyToUnlistedServer() {
    ModCompatibilityPolicy policy = new ModCompatibilityPolicy(
        Set.of("lobby"), Set.of(), Set.of("blocked"), ModCompatibilityPolicy.Action.DENY);
    ClientModProfile profile = new ClientModProfile(
        ClientModProfile.Loader.FORGE, "forge", Map.of("blocked", "1"));

    assertEquals(ModCompatibilityPolicy.Action.ALLOW, policy.evaluate("modded", profile).action());
  }
}
