package io.github.addxiaoyi.starx.velocity.module.uworld;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.velocitypowered.proxy.protocol.packet.ClientboundSoundEntityPacket;
import org.junit.jupiter.api.Test;

final class UworldCountdownSoundTest {

  @Test
  void targetsTheEntitySpawnedByTheEmbeddedLimboWorld() {
    UworldCountdownFrame frame = UworldCountdownFrame.at(300, 5);

    ClientboundSoundEntityPacket packet = UworldCountdownSound.packet(frame);

    assertEquals(1, packet.getEmitterEntityId());
    assertEquals(frame.pitch(), packet.getSound().pitch());
  }
}
