package io.github.addxiaoyi.starx.common.platform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.common.database.JdbcRuntimeSettingRepository;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class MaintenanceStateServiceTest {
  @Test
  void enabledStateSurvivesProxyRestart(@TempDir Path tempDir) throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("maintenance.db").toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute(JdbcRuntimeSettingRepository.CREATE_TABLE_SQL);
    }
    JdbcRuntimeSettingRepository settings = new JdbcRuntimeSettingRepository(source);
    MaintenanceStateService first = new MaintenanceStateService(settings);
    assertFalse(first.load());
    first.save(true, 100L);

    MaintenanceStateService restarted = new MaintenanceStateService(
        new JdbcRuntimeSettingRepository(source));
    assertTrue(restarted.load());
    restarted.save(false, 200L);
    assertFalse(first.load());
  }
}
