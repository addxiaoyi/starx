package io.github.addxiaoyi.starx.velocity.module.proxytools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ReconnectTargetPolicyTest {

  @Test
  void usesHealthyReplacementInsteadOfTheRememberedMaintenanceNode() {
    ReconnectTargetPolicy policy = new ReconnectTargetPolicy(
        preferred -> Optional.of("survival-2"));

    assertEquals("survival-2", policy.resolve("survival-1").orElseThrow());
  }

  @Test
  void leavesVelocityDefaultUntouchedWhenNoHealthyNodeExists() {
    ReconnectTargetPolicy policy = new ReconnectTargetPolicy(preferred -> Optional.empty());

    assertTrue(policy.resolve("survival-1").isEmpty());
  }
}
