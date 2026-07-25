package io.github.addxiaoyi.starx.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.addxiaoyi.starx.common.binding.BindingChallengeService;
import io.github.addxiaoyi.starx.common.binding.JdbcBindingChallengeRepository;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class PersistentBindingVerificationServiceTest {
  @TempDir Path tempDir;

  @Test
  void codeSurvivesServiceRestartAndCanOnlyBeConsumedOnce() throws Exception {
    SQLiteDataSource source = database();
    UUID playerId = UUID.fromString("3bc40bc3-6a0a-47aa-89ff-254b549d64ab");
    Clock clock = Clock.fixed(Instant.parse("2026-07-22T12:00:00Z"), ZoneOffset.UTC);
    BindingChallengeService challenges =
        new BindingChallengeService(new JdbcBindingChallengeRepository(source));

    BindingVerificationService first = persistent(challenges, playerId, clock);
    String code = first.generateCode(playerId);
    BindingVerificationService restarted = persistent(challenges, playerId, clock);

    assertEquals(playerId, restarted.verifyCode(code));
    assertNull(first.verifyCode(code));
  }

  @Test
  void rejectedAcceptanceDoesNotBurnTheCode() throws Exception {
    SQLiteDataSource source = database();
    UUID playerId = UUID.fromString("2f334575-9cf9-41ec-a951-f24687a31b40");
    Clock clock = Clock.fixed(Instant.parse("2026-07-22T12:00:00Z"), ZoneOffset.UTC);
    BindingVerificationService codes = persistent(
        new BindingChallengeService(new JdbcBindingChallengeRepository(source)), playerId, clock);
    String code = codes.generateCode(playerId);

    assertNull(codes.verifyCodeIf(code, ignored -> false));
    assertEquals(playerId, codes.verifyCode(code));
  }

  @Test
  void failedBindingActionReleasesCodeForRetry() throws Exception {
    SQLiteDataSource source = database();
    UUID playerId = UUID.fromString("5f82333e-9d6a-4da0-934a-8c753d554cb0");
    Clock clock = Clock.fixed(Instant.parse("2026-07-22T12:00:00Z"), ZoneOffset.UTC);
    BindingVerificationService codes = persistent(
        new BindingChallengeService(new JdbcBindingChallengeRepository(source)), playerId, clock);
    String code = codes.generateCode(playerId);

    assertThrows(IllegalStateException.class,
        () -> codes.verifyAndExecute(code, ignored -> {
          throw new IllegalStateException("database failed");
        }));
    assertEquals(playerId, codes.verifyAndExecute(code, ignored -> true));
    assertNull(codes.verifyAndExecute(code, ignored -> true));
  }

  private SQLiteDataSource database() throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve(UUID.randomUUID() + ".db").toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("CREATE TABLE starx_accounts (account_id VARCHAR(64) PRIMARY KEY, created_at BIGINT NOT NULL)");
      sql.execute(JdbcBindingChallengeRepository.CREATE_TABLE_SQL);
      sql.execute("INSERT INTO starx_accounts VALUES ('account-1', 1)");
    }
    return source;
  }

  private static BindingVerificationService persistent(
      BindingChallengeService challenges, UUID playerId, Clock clock) {
    return new BindingVerificationService(
        challenges,
        ignored -> "account-1",
        accountId -> accountId.equals("account-1") ? playerId : null,
        clock,
        Duration.ofMinutes(5));
  }
}
