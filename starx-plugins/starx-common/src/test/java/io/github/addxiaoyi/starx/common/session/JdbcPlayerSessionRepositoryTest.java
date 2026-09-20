package io.github.addxiaoyi.starx.common.session;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import java.util.Set;
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

  @Test
  void aggregatesHistoryAcrossKnownUuidAliases() {
    UUID legacy = UUID.randomUUID();
    UUID current = UUID.randomUUID();
    String id = sessions.start(legacy, "survival", 100L);
    sessions.finish(id, 900L, DisconnectReason.NORMAL);

    assertEquals(800L, sessions.summary(Set.of(current, legacy)).orElseThrow().totalPlaytime());
    assertEquals(java.util.Map.of("survival", 800L), sessions.playtimeByServer(Set.of(current, legacy)));
  }

  @Test
  void closesAllLingeringSessionsAfterProxyRestart() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    String firstSession = sessions.start(first, "lobby", 100L);
    String secondSession = sessions.start(second, "survival", 200L);

    assertEquals(2, sessions.finishActive(500L, DisconnectReason.PROXY_STOP));
    assertTrue(sessions.activeSession(first).isEmpty());
    assertTrue(sessions.activeSession(second).isEmpty());
    assertEquals(400L, sessions.summary(first).orElseThrow().totalPlaytime());
    assertEquals(300L, sessions.summary(second).orElseThrow().totalPlaytime());

    sessions.finish(firstSession, 900L, DisconnectReason.NORMAL);
    sessions.finish(secondSession, 900L, DisconnectReason.NORMAL);
    assertEquals(DisconnectReason.PROXY_STOP, sessions.summary(first).orElseThrow().disconnectReason());
  }
}
