package io.github.addxiaoyi.starx.velocity.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class MiniMotdMigrationTest {

  @TempDir
  Path temp;

  @Test
  void parsesRealHoconObjectArrayAndPreservesMiniMessage() throws Exception {
    Path starx = Files.createDirectories(temp.resolve("starx"));
    Path source = Files.createDirectories(temp.resolve("minimotd-velocity/icons"));
    Files.writeString(source.getParent().resolve("main.conf"), """
        motds = [
          { line1 = \"<gradient:red:blue><bold>One</bold>\", line2 = \"<gray>Two\", icon = \"random\" },
          { line1 = \"<gold>Maintenance <red>now\", line2 = \"ignored\", icon = \"random\" }
        ]
        player-count-settings { max-players-enabled = true, max-players = 42 }
        icon-enabled = true
        """);
    Files.write(source.resolve("one.png"), png());

    Map<String, Object> root = root();
    MiniMotdMigration.Result result = MiniMotdMigration.migrate(starx, root, ignored -> { });
    Map<String, Object> motd = module(root);

    assertTrue(result.migrated());
    assertEquals("<gradient:red:blue><bold>One</bold>\n<gray>Two", motd.get("normal"));
    assertEquals("<gold>Maintenance <red>now\nignored", motd.get("maintenance"));
    assertEquals(42, motd.get("maximum-players"));
    assertEquals("server-icon.png", motd.get("icon-path"));
    assertFalse(Files.exists(starx.resolve(".minimotd-migrated")));
  }

  @Test
  void fillsOnlyDefaultModuleValuesAndNeverWritesRootMotd() throws Exception {
    Path starx = Files.createDirectories(temp.resolve("starx"));
    Path source = Files.createDirectories(temp.resolve("minimotd-velocity"));
    Files.writeString(source.resolve("main.conf"), "motds=[{line1=\"<red>Imported\",line2=\"<blue>Imported maintenance\",icon=\"random\"}]\n");

    Map<String, Object> root = root();
    Map<String, Object> motd = module(root);
    motd.put("normal", "<green>Customized");
    motd.put("maintenance", "<aqua>Customized maintenance");
    motd.put("maximum-players", 77);
    motd.put("icon-path", "custom");
    Map<String, Object> managed = new LinkedHashMap<>();
    managed.put("path", "managed.png");
    motd.put("managed-icon", managed);
    root.put("motd", new LinkedHashMap<>(Map.of("normal", "legacy-root")));

    MiniMotdMigration.migrate(starx, root, ignored -> { });

    assertEquals("<green>Customized", motd.get("normal"));
    assertEquals("<aqua>Customized maintenance", motd.get("maintenance"));
    assertEquals(77, motd.get("maximum-players"));
    assertEquals("custom", motd.get("icon-path"));
    assertEquals(managed, motd.get("managed-icon"));
    assertEquals("legacy-root", ((Map<?, ?>) root.get("motd")).get("normal"));
  }

  @Test
  void explicitIconUsesSafeBasenameAndCopiesAtomically() throws Exception {
    Path starx = Files.createDirectories(temp.resolve("starx"));
    Path source = Files.createDirectories(temp.resolve("minimotd-velocity/icons"));
    Files.writeString(source.getParent().resolve("main.conf"), "motds=[{line1=\"one\",line2=\"two\",icon=\"custom\"}]\nicon-enabled=true\n");
    Files.write(source.resolve("custom.png"), png());

    Map<String, Object> root = root();
    MiniMotdMigration.Result result = MiniMotdMigration.migrate(starx, root, ignored -> { });

    assertTrue(result.iconCopied());
    assertTrue(Files.isRegularFile(starx.resolve("server-icon.png")));
  }

  @Test
  void rejectsTraversalSymlinkInvalidAndOversizedIcons() throws Exception {
    Path starx = Files.createDirectories(temp.resolve("starx"));
    Path source = Files.createDirectories(temp.resolve("minimotd-velocity/icons"));
    Files.writeString(source.getParent().resolve("main.conf"), "motds=[{line1=\"one\",line2=\"two\",icon=\"../escape\"}]\nicon-enabled=true\n");
    Files.write(source.resolve("escape.png"), new byte[] {1, 2, 3});

    Map<String, Object> root = root();
    MiniMotdMigration.Result result = MiniMotdMigration.migrate(starx, root, ignored -> { });

    assertFalse(result.migrated());
    assertFalse(Files.exists(starx.resolve("server-icon.png")));
    assertFalse(Files.exists(starx.resolve(".minimotd-migrated")));
  }

  @Test
  void markerIsWrittenOnlyAfterCallerPersistsConfig() throws Exception {
    Path starx = Files.createDirectories(temp.resolve("starx"));
    Path source = Files.createDirectories(temp.resolve("minimotd-velocity"));
    Files.writeString(source.resolve("main.conf"), "motds=[{line1=\"one\",line2=\"two\",icon=\"random\"}]\n");
    Map<String, Object> root = root();

    MiniMotdMigration.Result result = MiniMotdMigration.migrate(starx, root, ignored -> { });
    assertFalse(Files.exists(starx.resolve(".minimotd-migrated")));

    MiniMotdMigration.markComplete(result);
    assertTrue(Files.exists(starx.resolve(".minimotd-migrated")));
    assertFalse(MiniMotdMigration.migrate(starx, root(), ignored -> { }).migrated());
  }

  @Test
  void configLoaderPersistsAtomicallyBeforeWritingMarker() throws Exception {
    Path starx = Files.createDirectories(temp.resolve("starx"));
    Path source = Files.createDirectories(temp.resolve("minimotd-velocity"));
    Files.writeString(source.resolve("main.conf"), "motds=[{line1=\"<green>One\",line2=\"<white>Two\",icon=\"random\"}]\n");
    Path config = starx.resolve("config.yml");
    Files.writeString(config, "modules:\n  starx.motd:\n    enabled: true\n    normal: \"欢迎来到 StarX！\"\n    maintenance: \"StarX 正在维护中。\"\n    maximum-players: 100\n");

    ConfigLoader.load(config);

    String persisted = new Yaml().dump(ConfigLayout.readEffectiveRoot(config));
    assertTrue(persisted.contains("<green>One") && persisted.contains("<white>Two"));
    assertTrue(Files.isRegularFile(starx.resolve("config.yml.minimotd-backup")));
    assertTrue(Files.isRegularFile(starx.resolve(".minimotd-migrated")));
  }

  @Test
  void randomIconChoosesFirstSafeSortedPng() throws Exception {
    Path starx = Files.createDirectories(temp.resolve("starx"));
    Path icons = Files.createDirectories(temp.resolve("minimotd-velocity/icons"));
    Files.writeString(icons.getParent().resolve("main.conf"), "motds=[{line1=\"one\",line2=\"two\",icon=\"random\"}]\nicon-enabled=true\n");
    byte[] first = png();
    byte[] second = png();
    second[second.length - 1] ^= 1;
    Files.write(icons.resolve("z.png"), second);
    Files.write(icons.resolve("a.png"), first);

    MiniMotdMigration.Result result = MiniMotdMigration.migrate(starx, root(), ignored -> { });

    assertTrue(result.iconCopied());
    assertEquals(first.length, Files.size(starx.resolve("server-icon.png")));
  }

  @Test
  void rejectsSymlinkAndOversizedPngCandidates() throws Exception {
    Path starx = Files.createDirectories(temp.resolve("starx"));
    Path icons = Files.createDirectories(temp.resolve("minimotd-velocity/icons"));
    Files.writeString(icons.getParent().resolve("main.conf"), "motds=[{line1=\"one\",line2=\"two\",icon=\"random\"}]\nicon-enabled=true\n");
    Files.write(icons.resolve("oversized.png"), new byte[8 * 1_024 * 1_024 + 1]);
    Path outside = temp.resolve("outside.png");
    Files.write(outside, png());
    try {
      Files.createSymbolicLink(icons.resolve("linked.png"), outside);
    } catch (UnsupportedOperationException | java.nio.file.FileSystemException ignored) {
      return;
    }

    MiniMotdMigration.Result result = MiniMotdMigration.migrate(starx, root(), ignored -> { });

    assertFalse(result.iconCopied());
    assertFalse(Files.exists(starx.resolve("server-icon.png")));
  }
  private static Map<String, Object> root() {
    Map<String, Object> module = new LinkedHashMap<>();
    module.put("normal", "欢迎来到 StarX！");
    module.put("maintenance", "StarX 正在维护中。");
    module.put("maximum-players", 100);
    module.put("icon-path", "");
    Map<String, Object> modules = new LinkedHashMap<>();
    modules.put("starx.motd", module);
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("modules", modules);
    return root;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> module(Map<String, Object> root) {
    return (Map<String, Object>) ((Map<String, Object>) root.get("modules")).get("starx.motd");
  }

  private static byte[] png() throws Exception {
    BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
    image.setRGB(0, 0, Color.RED.getRGB());
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImageIO.write(image, "png", output);
    return output.toByteArray();
  }
}
