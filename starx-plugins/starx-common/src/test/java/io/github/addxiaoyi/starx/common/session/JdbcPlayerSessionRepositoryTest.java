package io.github.addxiaoyi.starx.common.session;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class JdbcPlayerSessionRepositoryTest {
  @TempDir Path tempDir;
  private JdbcPlayerSessionRepository sessions;

  @BeforeEach
  void setUp() throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("sessions.db").toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute(JdbcPlayerSessionRepository.CREATE_SESSIONS_SQL);
      sql.execute(JdbcPlayerSessionRepository.CREATE_SEGMENTS_SQL);
    }
    sessions = new JdbcPlayerSessionRepository(source);
  }

  @Test
  void closesAPlayingSessionExactlyOnce() {
    UUID player = UUID.randomUUID();
    String id = sessions.start(player, "lobby", 1_000L);
    sessions.finish(id, 4_000L, DisconnectReason.NORMAL);
    sessions.finish(id, 9_000L, DisconnectReason.KICKED);

    PlayerSessionSummary summary = sessions.summary(player).orElseThrow();
    assertEquals(3_000L, summary.totalPlaytime());
    assertEquals(1, summary.loginCount());
    assertEquals(DisconnectReason.NORMAL, summary.disconnectReason());
  }

  @Test
  void recordsASeparateSegmentForEachServer() {
    UUID player = UUID.randomUUID();
    String id = sessions.start(player, "lobby", 100L);
    sessions.transition(id, "survival", 600L);
    sessions.finish(id, 1_100L, DisconnectReason.TIMEOUT);

    assertEquals(500L, sessions.playtime(player, "lobby"));
    assertEquals(500L, sessions.playtime(player, "survival"));
    assertEquals(java.util.Map.of("lobby", 500L, "survival", 500L),
        sessions.playtimeByServer(player));
  }
}
