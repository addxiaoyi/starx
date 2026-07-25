package io.github.addxiaoyi.starx.velocity.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ConfigLoaderHttpExposureTest {

  @TempDir
  Path tempDir;

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
