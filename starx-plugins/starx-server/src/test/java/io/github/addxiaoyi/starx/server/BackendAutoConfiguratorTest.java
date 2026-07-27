package io.github.addxiaoyi.starx.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackendAutoConfiguratorTest {
  @TempDir
  Path temporary;

  @Test
  void infersStableNodeIdentifiers() {
    assertEquals("survival-1", BackendAutoConfigurator.inferNodeId(
        Path.of("network", "survival-1"), null));
    assertEquals("configured-node", BackendAutoConfigurator.inferNodeId(
        Path.of("network", "paper"), "Configured Node"));
    String generated = BackendAutoConfigurator.inferNodeId(
        Path.of("network", "paper").toAbsolutePath(), null);
    assertTrue(generated.matches("backend-[a-f0-9]{8}"), generated);
  }

  @Test
  void discoversSiblingVelocityAndNormalizesWildcardBind() throws Exception {
    Path paper = this.temporary.resolve("paper");
    Path velocityConfig = this.temporary.resolve(
        "velocity/plugins/starx/config.yml");
    Files.createDirectories(velocityConfig.getParent());
    Files.writeString(velocityConfig, """
        api-key: "secret-value"
        http:
          bind: "0.0.0.0"
          port: 9811
        """);

    var endpoint = BackendAutoConfigurator.discoverVelocity(paper, null);

    assertTrue(endpoint.isPresent());
    assertEquals("http://127.0.0.1:9811", endpoint.orElseThrow().baseUrl());
    assertEquals("secret-value", endpoint.orElseThrow().apiKey());
    assertEquals(velocityConfig.toAbsolutePath().normalize(),
        endpoint.orElseThrow().configPath());
  }

  @Test
  void bracketsNonLoopbackIpv6VelocityBind() throws Exception {
    Path config = this.temporary.resolve("velocity-ipv6.yml");
    Files.writeString(config, """
        api-key: "secret-value"
        http:
          bind: "2001:db8::10"
          port: 9812
        """);

    var endpoint = BackendAutoConfigurator.discoverVelocity(
        this.temporary.resolve("paper"), config);

    assertTrue(endpoint.isPresent());
    assertEquals("http://[2001:db8::10]:9812", endpoint.orElseThrow().baseUrl());
  }

  @Test
  void ignoresVelocityConfigWithoutCredential() throws Exception {
    Path config = this.temporary.resolve("velocity.yml");
    Files.writeString(config, """
        api-key: ""
        http:
          port: 8788
        """);

    assertFalse(BackendAutoConfigurator.discoverVelocity(
        this.temporary.resolve("paper"), config).isPresent());
  }
}
