package io.github.addxiaoyi.starx.limbo.injection.login;

import static com.velocitypowered.proxy.protocol.ProtocolUtils.Direction.SERVERBOUND;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.packet.ClientSettingsPacket;
import com.velocitypowered.proxy.protocol.packet.config.FinishedUpdatePacket;
import io.github.addxiaoyi.starx.limbo.protocol.LimboProtocol;
import org.junit.jupiter.api.Test;

final class LoginTasksQueueStatePolicyTest {

  @Test
  void modernLoginQueueRetainsLimboRegistryUntilConfigurationAcknowledgement() {
    StateRegistry limbo = LimboProtocol.getLimboStateRegistry();
    ProtocolVersion version = ProtocolVersion.MINECRAFT_1_21_11;

    assertInstanceOf(
        FinishedUpdatePacket.class,
        limbo.getProtocolRegistry(SERVERBOUND, version).createPacket(0x0F));
    assertEquals(
        0x0F,
        StateRegistry.PLAY.getProtocolRegistry(SERVERBOUND, version)
            .getPacketId(FinishedUpdatePacket.INSTANCE));
    assertEquals(
        0x00,
        StateRegistry.CONFIG.getProtocolRegistry(SERVERBOUND, version)
            .getPacketId(new ClientSettingsPacket()));

    assertTrue(LoginTasksQueue.retainsLimboRegistryUntilConfigAcknowledgement(limbo, version));
    assertFalse(LoginTasksQueue.retainsLimboRegistryUntilConfigAcknowledgement(
        StateRegistry.PLAY, version));
    assertFalse(LoginTasksQueue.retainsLimboRegistryUntilConfigAcknowledgement(
        limbo, ProtocolVersion.MINECRAFT_1_19_1));
  }
}
