package io.github.addxiaoyi.starx.common.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class JdbcTutorialProgressRepositoryTest {
  @Test
  void progressSurvivesRestartAndNeverExceedsStepCount(@TempDir Path tempDir) throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("tutorial.db").toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute(JdbcTutorialProgressRepository.CREATE_TABLE_SQL);
    }
    UUID playerId = UUID.fromString("2b3654af-27f7-4aeb-8f77-d9e227176036");
    JdbcTutorialProgressRepository first = new JdbcTutorialProgressRepository(source);
    assertEquals(0, first.step(playerId));
    assertEquals(1, first.advance(playerId, 3, 100L));
    assertEquals(2, first.advance(playerId, 3, 200L));

    JdbcTutorialProgressRepository restarted = new JdbcTutorialProgressRepository(source);
    assertEquals(2, restarted.step(playerId));
    assertEquals(3, restarted.advance(playerId, 3, 300L));
    assertEquals(3, restarted.advance(playerId, 3, 400L));
    assertEquals(7, restarted.complete(playerId, 7, 500L));
    assertEquals(7, first.step(playerId));
    restarted.reset(playerId);
    assertEquals(0, first.step(playerId));
  }
}
