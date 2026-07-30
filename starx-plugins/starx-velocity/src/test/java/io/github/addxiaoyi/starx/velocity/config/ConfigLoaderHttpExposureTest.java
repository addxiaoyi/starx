package io.github.addxiaoyi.starx.velocity.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ConfigLoaderHttpExposureTest {

  @TempDir
  Path tempDir;

  @Test
  void readsBoundedPersistentPortPolicy() throws Exception {
    Path configFile = this.tempDir.resolve("port-policy.yml");
    Files.writeString(configFile, """
        http:
          bind: "127.0.0.1"
          port: 9200
          port-conflict-policy: "persist"
          fallback-range-start: 9200
          fallback-range-end: 9250
        """, StandardCharsets.UTF_8);

    StarxConfig config = ConfigLoader.load(configFile);

    assertEquals(
        StarxConfig.HttpConfig.PortConflictPolicy.PERSIST,
        config.http().portConflictPolicy());
    assertEquals(9200, config.http().fallbackRangeStart());
    assertEquals(9250, config.http().fallbackRangeEnd());
  }

  @Test
  void rejectsUnboundedFallbackRange() throws Exception {
    Path configFile = this.tempDir.resolve("invalid-range.yml");
    Files.writeString(configFile, """
        http:
          port: 8788
          port-conflict-policy: "fallback"
          fallback-range-start: 1000
          fallback-range-end: 6000
        """, StandardCharsets.UTF_8);

    assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(configFile));
  }

  @Test
  void readsAdministratorConfiguredFrpPublicUrl() throws Exception {
    Path configFile = this.tempDir.resolve("config.yml");
    Files.writeString(configFile, """
        http:
          bind: "127.0.0.1"
          port: 8788
          frp-public-url: "https://api.star.example.com/starx"
        """, StandardCharsets.UTF_8);

    StarxConfig config = ConfigLoader.load(configFile);

    assertEquals("https://api.star.example.com/starx", config.http().frpPublicUrl());
  }
}
