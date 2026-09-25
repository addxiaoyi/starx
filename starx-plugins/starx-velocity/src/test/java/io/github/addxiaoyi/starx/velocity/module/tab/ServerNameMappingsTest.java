package io.github.addxiaoyi.starx.velocity.module.tab;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerNameMappingsTest {
  @TempDir Path directory;
  private final List<String> warnings = new ArrayList<>();
  private long timestamp = 1_800_000_000_000L;

  @Test
  void createsDefaultsAndFallsBackToActualBackendName() {
    var mappings = mappings();
    assertEquals("大厅", mappings.resolve("lobby"));
    assertEquals("unmapped", mappings.resolve("unmapped"));
    assertTrue(this.warnings.isEmpty());
    assertFalse(mappings.reloadIfChanged());
  }

  @Test
  void invalidSchemaKeepsLastGoodAliasesWithoutRepeatedWarnings() throws Exception {
    var mappings = mappings();
    write("servers: [lobby]");
    assertFalse(mappings.reloadIfChanged());
    assertEquals("大厅", mappings.resolve("lobby"));
    for (int i = 0; i < 100; i++) assertFalse(mappings.reloadIfChanged());
    assertEquals(1, this.warnings.size());
    write("servers:\n  lobby: 主大厅\n");
    assertTrue(mappings.reloadIfChanged());
    assertEquals("主大厅", mappings.resolve("lobby"));
  }

  @Test
  void rejectsMalformedDuplicateNonTextAndTaggedValues() throws Exception {
    var mappings = mappings();
    for (String invalid : List.of("servers: [", "servers:\n  lobby: one\n  lobby: two",
        "servers:\n  lobby: 123", "servers:\n  lobby: ''", "servers: !!java.util.Date {}",
        "servers:\n  lobby: one\n  ' lobby ': two")) {
      write(invalid);
      assertFalse(mappings.reloadIfChanged());
      assertEquals("大厅", mappings.resolve("lobby"));
    }
    assertEquals(6, this.warnings.size());
  }

  @Test
  void missingFileWarnsOnceAndRecoveryReloadsEvenWithOriginalTimestamp() throws Exception {
    var mappings = mappings();
    Path file = this.directory.resolve("cross-server-tab.yml");
    String original = Files.readString(file);
    FileTime modified = Files.getLastModifiedTime(file);
    Files.delete(file);
    for (int i = 0; i < 100; i++) assertFalse(mappings.reloadIfChanged());
    assertEquals(1, this.warnings.size());
    assertEquals("大厅", mappings.resolve("lobby"));
    Files.writeString(file, original);
    Files.setLastModifiedTime(file, modified);
    assertTrue(mappings.reloadIfChanged());
  }

  @Test
  void explicitEmptyMapClearsAliases() throws Exception {
    var mappings = mappings();
    write("servers: {}");
    assertTrue(mappings.reloadIfChanged());
    assertEquals(0, mappings.size());
    assertEquals("lobby", mappings.resolve("lobby"));
  }

  private ServerNameMappings mappings() {
    return new ServerNameMappings(this.directory.resolve("cross-server-tab.yml"), this.warnings::add);
  }

  private void write(String content) throws Exception {
    Path file = this.directory.resolve("cross-server-tab.yml");
    Files.writeString(file, content);
    Files.setLastModifiedTime(file, FileTime.fromMillis(++this.timestamp));
  }
}
