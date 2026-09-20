package io.github.addxiaoyi.starx.common.account;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class JdbcAccountDeletionRepositoryTest {
  @TempDir Path tempDir;

  @Test
  void supportsCoolingOffCancellationAndSingleExecutionClaim() throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("deletion.db").toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute(JdbcAccountDeletionRepository.CREATE_TABLE_SQL);
    }
    JdbcAccountDeletionRepository repo = new JdbcAccountDeletionRepository(source);
    UUID player = UUID.randomUUID();

    String first = repo.request(player, 1_000L, 8_000L);
    assertEquals(first, repo.request(player, 2_000L, 9_000L));
    assertFalse(repo.claimDue(first, 7_999L));
    assertFalse(repo.cancel(first, UUID.randomUUID(), 3_000L));
    assertTrue(repo.cancel(first, player, 3_000L));
    assertFalse(repo.claimDue(first, 9_000L));

    String second = repo.request(player, 10_000L, 20_000L);
    assertTrue(repo.claimDue(second, 20_000L));
    assertFalse(repo.claimDue(second, 21_000L));
  }

  @Test
  void exposesTheLatestRequestStateForThePersonalCenter() throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("deletion-status.db").toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute(JdbcAccountDeletionRepository.CREATE_TABLE_SQL);
    }
    JdbcAccountDeletionRepository repo = new JdbcAccountDeletionRepository(source);
    UUID player = UUID.randomUUID();
    String requestId = repo.request(player, 1_000L, 8_000L);

    JdbcAccountDeletionRepository.RequestStatus pending = repo.latest(player).orElseThrow();
    assertEquals(requestId, pending.requestId());
    assertEquals("PENDING", pending.state());
    assertEquals(8_000L, pending.executeAfter());

    assertTrue(repo.cancel(requestId, player, 2_000L));
    assertEquals("CANCELLED", repo.latest(player).orElseThrow().state());
  }

  @Test
  void treatsHistoricalMinecraftUuidsAsTheSameDeletionAccount() throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("deletion-alias.db").toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute(JdbcAccountDeletionRepository.CREATE_TABLE_SQL);
    }
    JdbcAccountDeletionRepository repo = new JdbcAccountDeletionRepository(source);
    UUID current = UUID.fromString("11111111-1111-1111-1111-111111111111");
    UUID legacy = UUID.fromString("22222222-2222-2222-2222-222222222222");
    Set<UUID> knownUuids = Set.of(current, legacy);

    String requestId = repo.request(legacy, 1_000L, 8_000L);

    assertEquals(requestId, repo.request(current, knownUuids, 2_000L, 9_000L));
    assertEquals(requestId, repo.latest(knownUuids).orElseThrow().requestId());
    assertTrue(repo.cancel(requestId, knownUuids, 3_000L));
    assertEquals("CANCELLED", repo.latest(knownUuids).orElseThrow().state());
  }

  @Test
  void concurrentRequestsReturnOnePendingRequest() throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("deletion-race.db").toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute(JdbcAccountDeletionRepository.CREATE_TABLE_SQL);
    }
    JdbcAccountDeletionRepository repo = new JdbcAccountDeletionRepository(source);
    UUID player = UUID.randomUUID();
    Set<String> ids = ConcurrentHashMap.newKeySet();
    CountDownLatch start = new CountDownLatch(1);

    try (var pool = Executors.newFixedThreadPool(8)) {
      for (int i = 0; i < 16; i++) {
        long requestedAt = 1_000L + i;
        pool.submit(() -> {
          start.await();
          ids.add(repo.request(player, requestedAt, requestedAt + 10_000L));
          return null;
        });
      }
      start.countDown();
      pool.shutdown();
      assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    }

    assertEquals(1, ids.size());
  }

  @Test
  void staleClaimReturnsToPendingAfterWorkerCrash() throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("deletion-stale.db").toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute(JdbcAccountDeletionRepository.CREATE_TABLE_SQL);
    }
    JdbcAccountDeletionRepository repo = new JdbcAccountDeletionRepository(source);
    UUID player = UUID.randomUUID();
    String requestId = repo.request(player, 1_000L, 2_000L);
    assertTrue(repo.claimDue(requestId, 2_000L));

    assertEquals(0, repo.releaseStaleClaims(2_999L, 1_000L));
    assertEquals(1, repo.releaseStaleClaims(3_000L, 1_000L));
    assertEquals("PENDING", repo.latest(player).orElseThrow().state());
  }

  @Test
  void staleWorkerCannotCompleteAReplacementClaim() throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("deletion-claim-owner.db").toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute(JdbcAccountDeletionRepository.CREATE_TABLE_SQL);
    }
    JdbcAccountDeletionRepository repo = new JdbcAccountDeletionRepository(source);
    UUID player = UUID.randomUUID();
    String requestId = repo.request(player, 1_000L, 2_000L);
    String firstToken = repo.claimDueToken(requestId, 2_000L).orElseThrow();

    assertEquals(1, repo.releaseStaleClaims(3_000L, 1_000L));
    String secondToken = repo.claimDueToken(requestId, 3_001L).orElseThrow();

    assertFalse(repo.complete(requestId, firstToken, 3_002L));
    assertTrue(repo.complete(requestId, secondToken, 3_003L));
    assertEquals("COMPLETED", repo.latest(player).orElseThrow().state());
  }

  @Test
  void requestDoesNotCreateASecondTaskWhileTheFirstIsClaimed() throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("deletion-claimed-request.db").toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute(JdbcAccountDeletionRepository.CREATE_TABLE_SQL);
    }
    JdbcAccountDeletionRepository repo = new JdbcAccountDeletionRepository(source);
    UUID player = UUID.randomUUID();
    String first = repo.request(player, 1_000L, 2_000L);
    assertTrue(repo.claimDue(first, 2_000L));

    String second = repo.request(player, 2_001L, 3_000L);

    assertEquals(first, second);
    assertEquals(1, countRequests(source, player));
    assertEquals("CLAIMED", repo.latest(player).orElseThrow().state());
  }

  private static int countRequests(SQLiteDataSource source, UUID player) throws Exception {
    try (Connection connection = source.getConnection();
         var query = connection.prepareStatement(
             "SELECT COUNT(*) FROM starx_account_deletions WHERE player_uuid = ?")) {
      query.setString(1, player.toString());
      try (var rows = query.executeQuery()) {
        rows.next();
        return rows.getInt(1);
      }
    }
  }
}
