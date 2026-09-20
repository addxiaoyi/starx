package io.github.addxiaoyi.starx.common.database;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class JdbcRuntimeSettingRepositoryTest {
  @Test
  void booleanSettingSurvivesRepositoryRestart(@TempDir Path tempDir) throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("settings.db").toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute(JdbcRuntimeSettingRepository.CREATE_TABLE_SQL);
    }
    JdbcRuntimeSettingRepository first = new JdbcRuntimeSettingRepository(source);
    assertFalse(first.getBoolean("maintenance.enabled", false));
    first.putBoolean("maintenance.enabled", true, 100L);

    JdbcRuntimeSettingRepository restarted = new JdbcRuntimeSettingRepository(source);
    assertTrue(restarted.getBoolean("maintenance.enabled", false));
    restarted.putBoolean("maintenance.enabled", false, 200L);
    assertFalse(first.getBoolean("maintenance.enabled", true));
  }
}
