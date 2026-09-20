package io.github.addxiaoyi.starx.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class ServerPlatformTest {

  @Test
  void detectsFoliaFromRegionizedServerClass() {
    assertEquals(ServerPlatform.FOLIA,
        ServerPlatform.detect(name -> name.equals(ServerPlatform.FOLIA_SERVER_CLASS)));
  }

  @Test
  void detectsPaperFromPaperApiClass() {
    assertEquals(ServerPlatform.PAPER,
        ServerPlatform.detect(name -> name.equals(ServerPlatform.PAPER_SERVER_CLASS)));
  }

  @Test
  void rejectsSpigotAndCraftBukkit() {
    assertThrows(IllegalStateException.class, () -> ServerPlatform.detect(name -> false));
  }
}
