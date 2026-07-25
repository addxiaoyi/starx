package io.github.addxiaoyi.starx.limbo.injection.packet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.util.GameProfile;
import com.velocitypowered.proxy.protocol.packet.LegacyPlayerListItemPacket;
import com.velocitypowered.proxy.protocol.packet.UpsertPlayerInfoPacket;
import com.velocitypowered.proxy.protocol.packet.chat.ComponentHolder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

class PlayerInfoHookTest {

  @Test
  void backendPacketsRemainUntouchedWithoutAnInitialIdentity() {
    UUID playerId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    UUID otherId = UUID.fromString("22222222-2222-2222-2222-222222222222");

    UpsertPlayerInfoPacket.Entry upsert = new UpsertPlayerInfoPacket.Entry(playerId);
    GameProfile profile = new GameProfile(playerId, "UworldProbe", List.of());
    upsert.setProfile(profile);
    List<UpsertPlayerInfoPacket.Entry> upserts = new ArrayList<>(List.of(upsert));

    LegacyPlayerListItemPacket.Item legacy = new LegacyPlayerListItemPacket.Item(playerId)
        .setName("UworldProbe");
    List<LegacyPlayerListItemPacket.Item> legacyItems = new ArrayList<>(List.of(legacy));
    List<UUID> removals = new ArrayList<>(List.of(playerId, otherId));

    UpsertPlayerInfoHook.rewriteEntries(upserts, playerId, null, "UworldProbe");
    LegacyPlayerListItemHook.rewriteItems(legacyItems, playerId, null);
    RemovePlayerInfoHook.rewriteProfiles(removals, playerId, null);

    assertSame(upsert, upserts.getFirst());
    assertSame(profile, upserts.getFirst().getProfile());
    assertSame(legacy, legacyItems.getFirst());
    assertEquals(List.of(playerId, otherId), removals);
  }

  @Test
  void mappedPlayerIdentityIsRestoredAcrossAllPlayerInfoPackets() {
    UUID playerId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    UUID initialId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    UUID otherId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    ComponentHolder displayName = new ComponentHolder(
        ProtocolVersion.MAXIMUM_VERSION,
        Component.text("Probe display"));

    UpsertPlayerInfoPacket.Entry upsert = new UpsertPlayerInfoPacket.Entry(playerId);
    upsert.setProfile(new GameProfile(playerId, "UworldProbe", List.of()));
    upsert.setDisplayName(displayName);
    upsert.setGameMode(2);
    upsert.setLatency(42);
    upsert.setListed(true);
    upsert.setListOrder(7);
    upsert.setShowHat(true);
    UpsertPlayerInfoPacket.Entry otherUpsert = new UpsertPlayerInfoPacket.Entry(otherId);
    List<UpsertPlayerInfoPacket.Entry> upserts =
        new ArrayList<>(List.of(upsert, otherUpsert));

    LegacyPlayerListItemPacket.Item legacy = new LegacyPlayerListItemPacket.Item(playerId)
        .setName("UworldProbe")
        .setDisplayName(Component.text("Legacy display"))
        .setGameMode(2)
        .setLatency(42);
    LegacyPlayerListItemPacket.Item otherLegacy =
        new LegacyPlayerListItemPacket.Item(otherId).setName("Other");
    List<LegacyPlayerListItemPacket.Item> legacyItems =
        new ArrayList<>(List.of(legacy, otherLegacy));
    List<UUID> removals = new ArrayList<>(List.of(playerId, otherId));

    UpsertPlayerInfoHook.rewriteEntries(upserts, playerId, initialId, "UworldProbe");
    LegacyPlayerListItemHook.rewriteItems(legacyItems, playerId, initialId);
    RemovePlayerInfoHook.rewriteProfiles(removals, playerId, initialId);

    assertEquals(initialId, upserts.getFirst().getProfileId());
    assertEquals(initialId, upserts.getFirst().getProfile().getId());
    assertEquals("UworldProbe", upserts.getFirst().getProfile().getName());
    assertSame(displayName, upserts.getFirst().getDisplayName());
    assertEquals(2, upserts.getFirst().getGameMode());
    assertEquals(42, upserts.getFirst().getLatency());
    assertTrue(upserts.getFirst().isListed());
    assertEquals(7, upserts.getFirst().getListOrder());
    assertTrue(upserts.getFirst().isShowHat());
    assertSame(otherUpsert, upserts.get(1));
    assertEquals(initialId, legacyItems.getFirst().getUuid());
    assertEquals("UworldProbe", legacyItems.getFirst().getName());
    assertEquals(Component.text("Legacy display"), legacyItems.getFirst().getDisplayName());
    assertEquals(2, legacyItems.getFirst().getGameMode());
    assertEquals(42, legacyItems.getFirst().getLatency());
    assertSame(otherLegacy, legacyItems.get(1));
    assertEquals(List.of(initialId, otherId), removals);
  }
}
