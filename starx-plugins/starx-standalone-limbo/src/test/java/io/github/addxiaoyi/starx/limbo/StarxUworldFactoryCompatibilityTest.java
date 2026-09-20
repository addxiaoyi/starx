package io.github.addxiaoyi.starx.limbo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.uworld.StarxUworldFactory;
import org.junit.jupiter.api.Test;

final class StarxUworldFactoryCompatibilityTest {

  @Test
  void legacyFactoryDelegatesToTheUworldEntryPoint() {
    assertTrue(StarxUworldFactory.class.isAssignableFrom(StarxLimboFactory.class));
    assertEquals(StarxUworldFactory.class, StarxLimboFactory.class.getSuperclass());
    assertTrue(StarxLimboFactory.class.isAnnotationPresent(Deprecated.class));
  }
}
