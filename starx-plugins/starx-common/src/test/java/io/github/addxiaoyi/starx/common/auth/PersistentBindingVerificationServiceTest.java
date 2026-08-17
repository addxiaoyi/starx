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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
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
  void newestPersistentCodeReplacesAnOlderCodeForTheSameAccount() throws Exception {
    SQLiteDataSource source = database();
    UUID playerId = UUID.randomUUID();
    int[] values = {123456, 123457};
    AtomicInteger index = new AtomicInteger();
    BindingVerificationService codes = new BindingVerificationService(
        new BindingChallengeService(new JdbcBindingChallengeRepository(source)),
        ignored -> "account-1", ignored -> playerId,
        Clock.fixed(Instant.parse("2026-07-22T12:00:00Z"), ZoneOffset.UTC),
        Duration.ofMinutes(5), uuid -> Set.of(uuid),
        () -> values[index.getAndIncrement()]);

    String first = codes.generateCode(playerId);
    String second = codes.generateCode(playerId);

    assertNull(codes.verifyCode(first));
    assertEquals(playerId, codes.verifyCode(second));
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

  @Test
  void memoryBindingCodeAcceptsAHistoricalMinecraftUuid() {
    UUID offlineUuid = UUID.randomUUID();
    UUID premiumUuid = UUID.randomUUID();
    Clock clock = Clock.fixed(Instant.parse("2026-07-22T12:00:00Z"), ZoneOffset.UTC);
    BindingVerificationService codes = new BindingVerificationService(
        requested -> Set.of(offlineUuid, premiumUuid), clock, Duration.ofMinutes(5));
    String code = codes.generateCode(offlineUuid);

    assertEquals(offlineUuid, codes.verifyCodeIf(code, premiumUuid::equals));
  }

  @Test
  void memoryBindingCodeExpiresAtItsExactDeadline() {
    MutableClock clock = new MutableClock(Instant.parse("2026-07-22T12:00:00Z"));
    UUID playerId = UUID.randomUUID();
    BindingVerificationService codes = new BindingVerificationService(
        ignored -> Set.of(playerId), clock, Duration.ofMinutes(5));
    String code = codes.generateCode(playerId);

    clock.advance(Duration.ofMinutes(5));

    assertNull(codes.verifyCode(code));
    assertNull(codes.verifyAndExecute(code, ignored -> true));
  }

  @Test
  void newestMemoryCodeReplacesAnOlderCodeForTheSamePlayer() {
    UUID playerId = UUID.randomUUID();
    int[] values = {123456, 123457};
    AtomicInteger index = new AtomicInteger();
    BindingVerificationService codes = new BindingVerificationService(
        uuid -> Set.of(uuid), Clock.systemUTC(), Duration.ofMinutes(5),
        () -> values[index.getAndIncrement()]);

    String first = codes.generateCode(playerId);
    String second = codes.generateCode(playerId);

    assertNull(codes.verifyCode(first));
    assertEquals(playerId, codes.verifyCode(second));
  }

  @Test
  void codeCollisionDoesNotReplaceAnotherPlayersPendingCode() {
    UUID firstPlayer = UUID.randomUUID();
    UUID secondPlayer = UUID.randomUUID();
    int[] generated = {123456, 123456, 123457};
    AtomicInteger index = new AtomicInteger();
    BindingVerificationService codes = new BindingVerificationService(
        uuid -> Set.of(uuid),
        Clock.systemUTC(),
        Duration.ofMinutes(5),
        () -> generated[Math.min(index.getAndIncrement(), generated.length - 1)]);

    String firstCode = codes.generateCode(firstPlayer);
    String secondCode = codes.generateCode(secondPlayer);

    assertEquals("123456", firstCode);
    assertEquals("123457", secondCode);
    assertEquals(firstPlayer, codes.verifyCode(firstCode));
    assertEquals(secondPlayer, codes.verifyCode(secondCode));
  }

  @Test
  void persistentCodeCollisionRetriesWithAnotherCode() throws Exception {
    SQLiteDataSource source = database();
    BindingChallengeService challenges =
        new BindingChallengeService(new JdbcBindingChallengeRepository(source));
    Clock clock = Clock.fixed(Instant.parse("2026-07-22T12:00:00Z"), ZoneOffset.UTC);
    UUID firstPlayer = UUID.randomUUID();
    UUID secondPlayer = UUID.randomUUID();
    BindingVerificationService first = new BindingVerificationService(
        challenges, ignored -> "account-1", ignored -> firstPlayer, clock, Duration.ofMinutes(5),
        uuid -> Set.of(uuid), () -> 123456);
    int[] generated = {123456, 123457};
    AtomicInteger index = new AtomicInteger();
    BindingVerificationService second = new BindingVerificationService(
        challenges, ignored -> "account-2", ignored -> secondPlayer, clock, Duration.ofMinutes(5),
        uuid -> Set.of(uuid),
        () -> generated[Math.min(index.getAndIncrement(), generated.length - 1)]);

    assertEquals("123456", first.generateCode(firstPlayer));
    assertEquals("123457", second.generateCode(secondPlayer));
  }

  private SQLiteDataSource database() throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve(UUID.randomUUID() + ".db").toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("CREATE TABLE starx_accounts (account_id VARCHAR(64) PRIMARY KEY, created_at BIGINT NOT NULL)");
      sql.execute(JdbcBindingChallengeRepository.CREATE_TABLE_SQL);
      sql.execute("INSERT INTO starx_accounts VALUES ('account-1', 1)");
      sql.execute("INSERT INTO starx_accounts VALUES ('account-2', 1)");
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

  private static final class MutableClock extends Clock {
    private Instant current;

    private MutableClock(Instant current) {
      this.current = current;
    }

    private void advance(Duration duration) {
      this.current = this.current.plus(duration);
    }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return this.current; }
  }
}
