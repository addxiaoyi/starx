package io.github.addxiaoyi.starx.velocity.module.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class SmartAlertModuleTest {

  @Test
  void emitsWarningAndCriticalOncePerWindow() {
    AtomicLong now = new AtomicLong(1_000);
    SmartAlertModule.AlertBucketRegistry registry =
        new SmartAlertModule.AlertBucketRegistry(16, 1_000, now::get);

    assertFalse(registry.record("security:risk:high", "127.0.0.1").warning());
    assertFalse(registry.record("security:risk:high", "127.0.0.1").warning());
    assertTrue(registry.record("security:risk:high", "127.0.0.1").warning());
    registry.record("security:risk:high", "127.0.0.1");
    registry.record("security:risk:high", "127.0.0.1");
    registry.record("security:risk:high", "127.0.0.1");
    registry.record("security:risk:high", "127.0.0.1");
    registry.record("security:risk:high", "127.0.0.1");
    registry.record("security:risk:high", "127.0.0.1");
    assertTrue(registry.record("security:risk:high", "127.0.0.1").critical());
    assertFalse(registry.record("security:risk:high", "127.0.0.1").critical());
  }

  @Test
  void expiresBucketsAtTheWindowBoundary() {
    AtomicLong now = new AtomicLong(1_000);
    SmartAlertModule.AlertBucketRegistry registry =
        new SmartAlertModule.AlertBucketRegistry(16, 1_000, now::get);

    assertEquals(1, registry.record("security:alert", "one").count());
    now.addAndGet(1_000);

    assertEquals(1, registry.record("security:alert", "one").count());
    assertEquals(1, registry.size());
  }

  @Test
  void evictsLeastRecentlyUsedBucketsAtCapacity() {
    AtomicLong now = new AtomicLong(1_000);
    SmartAlertModule.AlertBucketRegistry registry =
        new SmartAlertModule.AlertBucketRegistry(2, 10_000, now::get);

    registry.record("security:alert", "one");
    registry.record("security:alert", "two");
    registry.record("security:alert", "one");
    registry.record("security:alert", "three");

    assertEquals(2, registry.size());
    assertEquals(3, registry.record("security:alert", "one").count());
    assertEquals(1, registry.record("security:alert", "two").count());
  }
}
