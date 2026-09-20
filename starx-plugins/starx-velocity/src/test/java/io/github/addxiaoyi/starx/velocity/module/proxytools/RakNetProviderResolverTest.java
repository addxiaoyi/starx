package io.github.addxiaoyi.starx.velocity.module.proxytools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import org.junit.jupiter.api.Test;

final class RakNetProviderResolverTest {

  @Test
  void prefersGeyserAndRecognizesRaknetify() {
    assertEquals(
        RakNetProviderResolver.Provider.GEYSER,
        RakNetProviderResolver.resolve(Set.of("floodgate", "geyser")));
    assertEquals(
        RakNetProviderResolver.Provider.RAKNETIFY,
        RakNetProviderResolver.resolve(Set.of("raknetify")));
    assertEquals(
        RakNetProviderResolver.Provider.NONE,
        RakNetProviderResolver.resolve(Set.of("starx")));
  }
}
