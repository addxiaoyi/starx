package io.github.addxiaoyi.starx.velocity.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class VelocityAutoConfiguratorTest {
  @TempDir
  Path temporary;

  @Test
  void detectsPluginsGeneratesSecretAndSelectsAvailableServer() throws Exception {
    Path config = this.temporary.resolve("config.yml");
    Files.writeString(config, """
        schema-version: 3
        auto-config:
          enabled: true
          generate-api-key: true
          manage-optional-integrations: true
          manage-texture-source: true
          select-auth-target: true
          report-file: "auto-detection.json"
        api-key: ""
        website-sync:
          node-id: "auto"
          textures:
            enabled: false
        modules:
          starx.integrations.luckperms:
            enabled: false
          starx.integrations.floodgate:
            enabled: true
          starx.integrations.tab:
            enabled: true
          starx.integrations.plan:
            enabled: true
          starx.proxytools.raknet:
            enabled: false
        uworld:
          auth:
            target-server: "lobby"
        """);

    VelocityAutoConfigurator.Result result = VelocityAutoConfigurator.apply(
        config,
        Set.of("luckperms", "skinsrestorer", "geyser"),
        Set.of("survival-1", "hub-1"),
        "node-a",
        true,
        ignored -> { });

    Map<String, Object> root = load(config);
    assertTrue(result.changed());
    assertEquals(96, String.valueOf(root.get("api-key")).length());
    assertEquals("proxy-node-a", nested(root, "website-sync", "node-id"));
    assertEquals(true, nested(root, "website-sync", "textures", "enabled"));
    assertEquals(true, nested(root, "modules", "starx.integrations.luckperms", "enabled"));
    assertEquals(false, nested(root, "modules", "starx.integrations.floodgate", "enabled"));
    assertEquals(false, nested(root, "modules", "starx.integrations.tab", "enabled"));
    assertEquals(false, nested(root, "modules", "starx.integrations.plan", "enabled"));
    assertEquals(true, nested(root, "modules", "starx.proxytools.raknet", "enabled"));
    assertEquals("hub-1", nested(root, "uworld", "auth", "target-server"));
    String report = Files.readString(this.temporary.resolve("auto-detection.json"));
    assertTrue(report.contains("\"platform\": \"velocity\""));
    assertFalse(report.contains(String.valueOf(root.get("api-key"))));
  }

  @Test
  void manualManagementSwitchesPreserveExplicitValues() throws Exception {
    Path config = this.temporary.resolve("config.yml");
    Files.writeString(config, """
        auto-config:
          enabled: true
          generate-api-key: false
          manage-optional-integrations: false
          manage-texture-source: false
          select-auth-target: false
          report-file: ""
        api-key: ""
        website-sync:
          node-id: "proxy-manual"
          textures:
            enabled: true
        modules:
          starx.integrations.luckperms:
            enabled: true
        uworld:
          auth:
            target-server: "custom"
        """);

    VelocityAutoConfigurator.Result result = VelocityAutoConfigurator.apply(
        config, Set.of(), Set.of("lobby"), "ignored", false, ignored -> { });

    Map<String, Object> root = load(config);
    assertFalse(result.changed());
    assertEquals("", root.get("api-key"));
    assertEquals("proxy-manual", nested(root, "website-sync", "node-id"));
    assertEquals(true, nested(root, "website-sync", "textures", "enabled"));
    assertEquals(true, nested(root, "modules", "starx.integrations.luckperms", "enabled"));
    assertEquals("custom", nested(root, "uworld", "auth", "target-server"));
  }

  @Test
  void skipsSequenceMappingsAndContinuesEditingManagedScalars() throws Exception {
    Path config = this.temporary.resolve("config.yml");
    Files.writeString(config, """
        auto-config:
          enabled: true
          generate-api-key: false
          manage-optional-integrations: true
          manage-texture-source: true
          select-auth-target: false
          report-file: ""
        api-key: "manual"
        unrelated:
          entries:
            -
              name: "first"
              enabled: true
        website-sync:
          node-id: "proxy-manual"
          textures:
            enabled: false
        modules:
          starx.integrations.luckperms:
            enabled: false
          starx.integrations.floodgate:
            enabled: true
          starx.integrations.tab:
            enabled: true
          starx.integrations.plan:
            enabled: true
          starx.proxytools.raknet:
            enabled: false
        """);

    VelocityAutoConfigurator.Result result = VelocityAutoConfigurator.apply(
        config,
        Set.of("luckperms", "skinsrestorer", "geyser"),
        Set.of("lobby"),
        "ignored",
        false,
        ignored -> { });

    Map<String, Object> root = load(config);
    assertTrue(result.changed());
    assertEquals(true, nested(root, "unrelated", "entries", "0", "enabled"));
    assertEquals(true, nested(root, "website-sync", "textures", "enabled"));
    assertEquals(true, nested(root, "modules", "starx.integrations.luckperms", "enabled"));
    assertEquals(false, nested(root, "modules", "starx.integrations.floodgate", "enabled"));
    assertEquals(true, nested(root, "modules", "starx.proxytools.raknet", "enabled"));
  }

  @Test
  void targetSelectionUsesStablePriority() {
    assertEquals("lobby", VelocityAutoConfigurator.chooseTargetServer(
        Set.of("survival", "lobby", "hub")));
    assertEquals("auth-hub", VelocityAutoConfigurator.chooseTargetServer(
        Set.of("zeta", "auth-hub")));
    assertEquals("alpha", VelocityAutoConfigurator.chooseTargetServer(
        Set.of("zeta", "alpha")));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> load(Path path) throws Exception {
    return (Map<String, Object>) new Yaml().load(Files.readString(path));
  }

  private static Object nested(Map<String, Object> root, String... path) {
    Object current = root;
    for (String part : path) {
      if (current instanceof List<?> list) {
        current = list.get(Integer.parseInt(part));
      } else {
        current = ((Map<?, ?>) current).get(part);
      }
    }
    return current;
  }
}
