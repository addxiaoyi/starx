package io.github.addxiaoyi.starx.velocity.module.uworld;

import com.velocitypowered.proxy.protocol.packet.ClientboundSoundEntityPacket;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;

final class UworldCountdownSound {

  private static final int LIMBO_PLAYER_ENTITY_ID = 1;

  private UworldCountdownSound() {
  }

  static ClientboundSoundEntityPacket packet(UworldCountdownFrame frame) {
    Sound sound = Sound.sound(
        Key.key("minecraft:block.note_block.pling"),
        Sound.Source.MASTER,
        0.8f,
        frame.pitch());
    return new ClientboundSoundEntityPacket(sound, null, LIMBO_PLAYER_ENTITY_ID);
  }
}
