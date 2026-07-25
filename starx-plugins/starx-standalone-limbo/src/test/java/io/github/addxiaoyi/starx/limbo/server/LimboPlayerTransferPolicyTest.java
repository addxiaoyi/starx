package io.github.addxiaoyi.starx.limbo.server;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.network.ProtocolVersion;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

final class LimboPlayerTransferPolicyTest {

  @Test
  void modernLoginQueueOwnsTheConfigurationTransition() throws Exception {
    Method policy = assertDoesNotThrow(() -> LimboPlayerImpl.class.getDeclaredMethod(
        "loginQueueOwnsConfigTransition", boolean.class, ProtocolVersion.class));
    policy.setAccessible(true);

    assertTrue((boolean) policy.invoke(
        null, true, ProtocolVersion.MINECRAFT_1_21_11));
    assertFalse((boolean) policy.invoke(
        null, false, ProtocolVersion.MINECRAFT_1_21_11));
    assertFalse((boolean) policy.invoke(
        null, true, ProtocolVersion.MINECRAFT_1_19_1));
  }
}
