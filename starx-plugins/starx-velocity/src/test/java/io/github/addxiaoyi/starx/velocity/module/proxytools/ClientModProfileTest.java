package io.github.addxiaoyi.starx.velocity.module.proxytools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.util.ModInfo;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ClientModProfileTest {

  @Test
  void detectsForgeNeoForgeAndFabricAcrossMetadataStyles() {
    ClientModProfile forge = ClientModProfile.detect(
        "forge", Optional.of(new ModInfo("FML2", List.of(new ModInfo.Mod("forge", "47")))));
    ClientModProfile neoForge = ClientModProfile.detect(
        "neoforge", Optional.of(new ModInfo("FML2", List.of(new ModInfo.Mod("neoforge", "21")))));
    ClientModProfile fabric = ClientModProfile.detect(
        "fabric", Optional.of(new ModInfo("fabric", List.of(new ModInfo.Mod("fabricloader", "0.16")))));

    assertEquals(ClientModProfile.Loader.FORGE, forge.loader());
    assertEquals(ClientModProfile.Loader.NEOFORGE, neoForge.loader());
    assertEquals(ClientModProfile.Loader.FABRIC, fabric.loader());
    assertTrue(forge.modded());
    assertTrue(neoForge.modded());
    assertTrue(fabric.modded());
  }

  @Test
  void keepsVanillaClientsVanillaAndMarksUnknownBrands() {
    ClientModProfile vanilla = ClientModProfile.detect("vanilla", Optional.empty());
    ClientModProfile custom = ClientModProfile.detect("custom-client", Optional.empty());

    assertEquals(ClientModProfile.Loader.VANILLA, vanilla.loader());
    assertFalse(vanilla.modded());
    assertEquals(ClientModProfile.Loader.UNKNOWN_MODDED, custom.loader());
  }
}
