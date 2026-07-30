package io.github.addxiaoyi.starx.common.binding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

final class BindingChallengeExecutionLeaseTest {
  private static final Duration LEASE = Duration.ofSeconds(5);

  @TempDir Path tempDir;

  @Test
  void expiredLeaseCanBeRecoveredWithStableOperationId() throws Exception {
    SQLiteDataSource source = database();
    BindingChallengeService service = new BindingChallengeService(
        new JdbcBindingChallengeRepository(source), LEASE);
    Instant now = Instant.parse("2026-07-28T12:00:00Z");
    String token = "482193";
    service.begin("account-1", "QQ", token, now, Duration.ofMinutes(5));
    BindingChallenge stored = service.inspectExecutable("QQ", token, now);

    BindingChallengeService.Execution first = service.acquire(stored, now);
    assertNull(service.acquire(stored, now.plusSeconds(1)));

    Instant recoveredAt = now.plus(LEASE).plusSeconds(1);
    BindingChallenge recovered = service.inspectExecutable("QQ", token, recoveredAt);
    BindingChallengeService.Execution second = service.acquire(recovered, recoveredAt);

    assertEquals(first.operationId(), second.operationId());
    assertFalse(service.consume(first, recoveredAt));
    assertTrue(service.consume(second, recoveredAt));
    assertNull(service.inspectExecutable("QQ", token, recoveredAt.plusSeconds(1)));
  }

  private SQLiteDataSource database() throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("lease.db").toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("CREATE TABLE starx_accounts (account_id VARCHAR(64) PRIMARY KEY, created_at BIGINT NOT NULL)");
      sql.execute(JdbcBindingChallengeRepository.CREATE_TABLE_SQL);
      sql.execute("INSERT INTO starx_accounts VALUES ('account-1', 1)");
    }
    return source;
  }
}
