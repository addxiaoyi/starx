package io.github.addxiaoyi.starx.velocity.module.proxytools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class QueueTargetPolicyTest {

  @Test
  void selectsAHealthyShard() {
    QueueTargetPolicy policy = new QueueTargetPolicy(
        (preferred, queues) -> Optional.of("survival-2"));

    assertEquals("survival-2", policy.resolve("survival-1", Map.of()).orElseThrow());
  }

  @Test
  void keepsThePlayerQueuedWhenNoHealthyNodeExists() {
    QueueTargetPolicy policy = new QueueTargetPolicy(
        (preferred, queues) -> Optional.empty());

    assertTrue(policy.resolve("survival-1", Map.of()).isEmpty());
  }
}
