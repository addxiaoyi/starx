package io.github.addxiaoyi.starx.common.database;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class BindingUniquenessGuardTest {
  @TempDir Path tempDir;

  @Test
  void reportsDuplicateIdentityBeforeCreatingUniqueIndex() throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("binding-migration.db").toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("CREATE TABLE starx_player_bindings (player_uuid VARCHAR(36) PRIMARY KEY, qq_id VARCHAR(64), discord_id VARCHAR(64), created_at BIGINT NOT NULL)");
      sql.execute("INSERT INTO starx_player_bindings VALUES ('one', '10001', NULL, 1)");
      sql.execute("INSERT INTO starx_player_bindings VALUES ('two', '10001', NULL, 2)");

      IllegalStateException error = assertThrows(
          IllegalStateException.class, () -> BindingUniquenessGuard.verify(connection));
      assertTrue(error.getMessage().contains("QQ=10001 count=2"));
    }
  }
}
