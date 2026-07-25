package io.github.addxiaoyi.starx.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class ServerPlatformTest {

  @Test
  void detectsFoliaFromRegionizedServerClass() {
    assertEquals(ServerPlatform.FOLIA, ServerPlatform.detect(name -> true));
  }

  @Test
  void defaultsToPaperWhenFoliaClassIsAbsent() {
    assertEquals(ServerPlatform.PAPER, ServerPlatform.detect(name -> false));
  }
}
