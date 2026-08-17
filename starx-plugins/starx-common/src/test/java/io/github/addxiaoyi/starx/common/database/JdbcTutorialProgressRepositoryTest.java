package io.github.addxiaoyi.starx.common.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import java.util.Set;
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

  @Test
  void readsAndResetsProgressAcrossKnownUuidAliases(@TempDir Path tempDir) throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("tutorial-alias.db").toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute(JdbcTutorialProgressRepository.CREATE_TABLE_SQL);
    }
    UUID legacy = UUID.randomUUID();
    UUID current = UUID.randomUUID();
    JdbcTutorialProgressRepository repository = new JdbcTutorialProgressRepository(source);

    assertEquals(3, repository.complete(legacy, 3, 100L));
    assertEquals(3, repository.step(Set.of(current, legacy)));
    repository.reset(Set.of(current, legacy));
    assertEquals(0, repository.step(Set.of(current, legacy)));
  }

  @Test
  void completeReturnsExistingAliasProgressWhenConfiguredStepCountIsLower(@TempDir Path tempDir)
      throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("tutorial-boundary.db").toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute(JdbcTutorialProgressRepository.CREATE_TABLE_SQL);
    }
    UUID legacy = UUID.randomUUID();
    UUID current = UUID.randomUUID();
    JdbcTutorialProgressRepository repository = new JdbcTutorialProgressRepository(source);

    assertEquals(5, repository.complete(legacy, 5, 100L));
    assertEquals(5, repository.complete(current, Set.of(current, legacy), 3, 200L));
    assertEquals(5, repository.step(Set.of(current, legacy)));
  }

  @Test
  void completeDoesNotReportSuccessWhenTheProgressInsertFails(@TempDir Path tempDir)
      throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("tutorial-write-failure.db").toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute(JdbcTutorialProgressRepository.CREATE_TABLE_SQL);
      sql.execute("CREATE TRIGGER fail_tutorial_insert BEFORE INSERT ON starx_tutorial_progress "
          + "BEGIN SELECT RAISE(ABORT, 'simulated tutorial write failure'); END");
    }
    JdbcTutorialProgressRepository repository = new JdbcTutorialProgressRepository(source);

    assertThrows(IllegalStateException.class, () -> repository.complete(UUID.randomUUID(), 3, 100L));
  }
}
