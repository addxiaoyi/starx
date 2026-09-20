package io.github.addxiaoyi.starx.common.binding;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class BindingChallengeServiceTest {
  @TempDir Path tempDir;
  private BindingChallengeService challenges;

  @BeforeEach
  void setUp() throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("binding-service.db").toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("CREATE TABLE starx_accounts (account_id VARCHAR(64) PRIMARY KEY, created_at BIGINT NOT NULL)");
      sql.execute(JdbcBindingChallengeRepository.CREATE_TABLE_SQL);
      sql.execute("INSERT INTO starx_accounts VALUES ('account-1', 1)");
    }
    challenges = new BindingChallengeService(new JdbcBindingChallengeRepository(source));
  }

  @Test
  void confirmedChallengeCanOnlyBeConsumedOnce() {
    Instant now = Instant.parse("2026-07-22T12:00:00Z");
    String id = challenges.begin("account-1", "QQ", "482193", now, Duration.ofMinutes(5));

    assertTrue(challenges.confirm(id, "482193", now.plusSeconds(2)));
    assertTrue(challenges.consume(id, now.plusSeconds(3)));
    assertFalse(challenges.consume(id, now.plusSeconds(4)));
    assertFalse(challenges.confirm(id, "482193", now.plusSeconds(5)));
  }

  @Test
  void wrongOrExpiredTokenNeverConfirms() {
    Instant now = Instant.parse("2026-07-22T12:00:00Z");
    String wrong = challenges.begin("account-1", "EMAIL", "123456", now, Duration.ofMinutes(5));
    String expired = challenges.begin("account-1", "SKIN", "skin-token", now, Duration.ofSeconds(10));

    assertFalse(challenges.confirm(wrong, "000000", now.plusSeconds(1)));
    assertTrue(challenges.confirm(wrong, "123456", now.plusSeconds(2)));
    assertFalse(challenges.confirm(expired, "skin-token", now.plusSeconds(11)));
    assertFalse(challenges.consume(expired, now.plusSeconds(12)));
  }

  @Test
  void terminalChallengeReleasesShortTokenForFutureUse() {
    Instant now = Instant.parse("2026-07-22T12:00:00Z");
    String first = challenges.begin("account-1", "QQ", "482193", now, Duration.ofMinutes(5));
    assertTrue(challenges.confirm(first, "482193", now.plusSeconds(1)));
    assertTrue(challenges.consume(first, now.plusSeconds(2)));

    String second = challenges.begin(
        "account-1", "QQ", "482193", now.plusSeconds(3), Duration.ofMinutes(5));
    assertTrue(challenges.confirm(second, "482193", now.plusSeconds(4)));
  }
}
