package io.github.addxiaoyi.starx.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Set;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import io.github.addxiaoyi.starx.common.binding.BindingChallengeService;
import io.github.addxiaoyi.starx.common.binding.JdbcBindingChallengeRepository;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.junit.jupiter.api.Test;

final class EmailChallengeServiceTest {
  @Test
  void persistentEmailCodeSurvivesRestart(@TempDir Path tempDir) throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("email.db").toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("CREATE TABLE starx_accounts (account_id VARCHAR(64) PRIMARY KEY, created_at BIGINT NOT NULL)");
      sql.execute(JdbcBindingChallengeRepository.CREATE_TABLE_SQL);
      sql.execute("INSERT INTO starx_accounts VALUES ('account-1', 1)");
    }
    UUID playerId = UUID.fromString("2996d964-eec0-462d-98ca-8b3520479e3b");
    AtomicReference<String> delivered = new AtomicReference<>();
    Clock clock = Clock.fixed(Instant.parse("2026-07-22T12:00:00Z"), ZoneOffset.UTC);
    BindingChallengeService challenges =
        new BindingChallengeService(new JdbcBindingChallengeRepository(source));
    EmailChallengeService first = new EmailChallengeService(
        (email, code) -> delivered.set(code), Duration.ofMinutes(10),
        challenges, ignored -> "account-1", clock);
    first.begin(playerId, "Player@Example.com");
    EmailChallengeService restarted = new EmailChallengeService(
        (email, code) -> { }, Duration.ofMinutes(10),
        challenges, ignored -> "account-1", clock);

    assertEquals("player@example.com", restarted.confirm(playerId, delivered.get()));
    assertThrows(IllegalStateException.class,
        () -> first.confirm(playerId, delivered.get()));
  }

  @Test
  void persistentEmailResendRevokesThePreviousCode(@TempDir Path tempDir) throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("email-resend.db").toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("CREATE TABLE starx_accounts (account_id VARCHAR(64) PRIMARY KEY, created_at BIGINT NOT NULL)");
      sql.execute(JdbcBindingChallengeRepository.CREATE_TABLE_SQL);
      sql.execute("INSERT INTO starx_accounts VALUES ('account-1', 1)");
    }
    UUID playerId = UUID.randomUUID();
    AtomicReference<String> firstCode = new AtomicReference<>();
    AtomicReference<String> secondCode = new AtomicReference<>();
    AtomicInteger generated = new AtomicInteger(111111);
    Clock clock = Clock.fixed(Instant.parse("2026-07-22T12:00:00Z"), ZoneOffset.UTC);
    BindingChallengeService challenges =
        new BindingChallengeService(new JdbcBindingChallengeRepository(source));
    EmailChallengeService service = new EmailChallengeService(
        (email, code) -> {
          if (firstCode.get() == null) firstCode.set(code);
          else secondCode.set(code);
        }, Duration.ofMinutes(10), challenges, ignored -> "account-1", clock,
        uuid -> Set.of(uuid), () -> generated.getAndIncrement());

    service.begin(playerId, "player@example.com");
    service.begin(playerId, "player@example.com");

    assertThrows(IllegalStateException.class, () -> service.confirm(playerId, firstCode.get()));
    assertEquals("player@example.com", service.confirm(playerId, secondCode.get()));
  }

  @Test
  void persistentEmailResendRevokesAStaleExecutingCode(@TempDir Path tempDir) throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("email-resend-confirmed.db").toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("CREATE TABLE starx_accounts (account_id VARCHAR(64) PRIMARY KEY, created_at BIGINT NOT NULL)");
      sql.execute(JdbcBindingChallengeRepository.CREATE_TABLE_SQL);
      sql.execute("INSERT INTO starx_accounts VALUES ('account-1', 1)");
    }
    UUID playerId = UUID.randomUUID();
    AtomicReference<String> firstCode = new AtomicReference<>();
    AtomicReference<String> secondCode = new AtomicReference<>();
    AtomicInteger generated = new AtomicInteger(222222);
    Clock clock = Clock.fixed(Instant.parse("2026-07-22T12:00:00Z"), ZoneOffset.UTC);
    BindingChallengeService challenges = new BindingChallengeService(
        new JdbcBindingChallengeRepository(source), Duration.ofMinutes(10));
    EmailChallengeService service = new EmailChallengeService(
        (email, code) -> {
          if (firstCode.get() == null) firstCode.set(code);
          else secondCode.set(code);
        }, Duration.ofMinutes(10), challenges, ignored -> "account-1", clock,
        uuid -> Set.of(uuid), () -> generated.getAndIncrement());

    service.begin(playerId, "player@example.com");
    var stored = challenges.inspectExecutable("EMAIL", firstCode.get(), clock.instant());
    assertTrue(challenges.acquire(stored, clock.instant()) != null);
    assertThrows(
        JdbcBindingChallengeRepository.ChallengeInProgressException.class,
        () -> service.begin(playerId, "player@example.com"));

    assertFalse(service.confirmAndExecute(playerId, firstCode.get(), ignored -> true));
    assertEquals(null, secondCode.get());
  }

  @Test
  void persistentEmailCodeIsReleasedWhenBindingFails(@TempDir Path tempDir) throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("email-action.db").toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("CREATE TABLE starx_accounts (account_id VARCHAR(64) PRIMARY KEY, created_at BIGINT NOT NULL)");
      sql.execute(JdbcBindingChallengeRepository.CREATE_TABLE_SQL);
      sql.execute("INSERT INTO starx_accounts VALUES ('account-1', 1)");
    }
    UUID playerId = UUID.fromString("98ad24b3-b830-44d0-94fe-3286a92b467c");
    AtomicReference<String> delivered = new AtomicReference<>();
    Clock clock = Clock.fixed(Instant.parse("2026-07-22T12:00:00Z"), ZoneOffset.UTC);
    EmailChallengeService service = new EmailChallengeService(
        (email, code) -> delivered.set(code), Duration.ofMinutes(10),
        new BindingChallengeService(new JdbcBindingChallengeRepository(source)),
        ignored -> "account-1", clock);
    service.begin(playerId, "player@example.com");

    assertFalse(service.confirmAndExecute(playerId, delivered.get(), ignored -> false));
    assertTrue(service.confirmAndExecute(playerId, delivered.get(), ignored -> true));
    assertFalse(service.confirmAndExecute(playerId, delivered.get(), ignored -> true));
  }

  @Test
  void persistentEmailCodeExpiresWhileBindingIsExecuting(@TempDir Path tempDir) throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("email-expiry.db").toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("CREATE TABLE starx_accounts (account_id VARCHAR(64) PRIMARY KEY, created_at BIGINT NOT NULL)");
      sql.execute(JdbcBindingChallengeRepository.CREATE_TABLE_SQL);
      sql.execute("INSERT INTO starx_accounts VALUES ('account-1', 1)");
    }
    MutableClock clock = new MutableClock(Instant.parse("2026-07-22T12:00:00Z"));
    AtomicReference<String> delivered = new AtomicReference<>();
    UUID playerId = UUID.randomUUID();
    EmailChallengeService service = new EmailChallengeService(
        (email, code) -> delivered.set(code), Duration.ofMinutes(10),
        new BindingChallengeService(new JdbcBindingChallengeRepository(source)),
        ignored -> "account-1", clock);
    service.begin(playerId, "player@example.com");

    assertFalse(service.confirmAndExecute(playerId, delivered.get(), ignored -> {
      clock.advance(Duration.ofMinutes(11));
      return true;
    }));
    assertFalse(service.confirmAndExecute(playerId, delivered.get(), ignored -> true));
  }

  @Test
  void persistentEmailCodeCollisionRetriesWithAnotherCode(@TempDir Path tempDir) throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("email-collision.db").toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("CREATE TABLE starx_accounts (account_id VARCHAR(64) PRIMARY KEY, created_at BIGINT NOT NULL)");
      sql.execute(JdbcBindingChallengeRepository.CREATE_TABLE_SQL);
      sql.execute("INSERT INTO starx_accounts VALUES ('account-1', 1)");
      sql.execute("INSERT INTO starx_accounts VALUES ('account-2', 1)");
    }
    Clock clock = Clock.fixed(Instant.parse("2026-07-22T12:00:00Z"), ZoneOffset.UTC);
    BindingChallengeService challenges =
        new BindingChallengeService(new JdbcBindingChallengeRepository(source));
    AtomicReference<String> firstCode = new AtomicReference<>();
    AtomicReference<String> secondCode = new AtomicReference<>();
    UUID firstPlayer = UUID.randomUUID();
    UUID secondPlayer = UUID.randomUUID();
    EmailChallengeService first = new EmailChallengeService(
        (email, code) -> firstCode.set(code), Duration.ofMinutes(10), challenges,
        uuid -> uuid.equals(firstPlayer) ? "account-1" : "account-2",
        clock, uuid -> Set.of(uuid), () -> 123456);
    int[] generated = {123456, 123457};
    AtomicInteger index = new AtomicInteger();
    EmailChallengeService second = new EmailChallengeService(
        (email, code) -> secondCode.set(code), Duration.ofMinutes(10), challenges,
        uuid -> uuid.equals(firstPlayer) ? "account-1" : "account-2",
        clock, uuid -> Set.of(uuid),
        () -> generated[Math.min(index.getAndIncrement(), generated.length - 1)]);

    first.begin(firstPlayer, "first@example.com");
    second.begin(secondPlayer, "second@example.com");

    assertEquals("123456", firstCode.get());
    assertEquals("123457", secondCode.get());
  }

  @Test
  void memoryEmailCodeSurvivesFailedBinding() {
    AtomicReference<String> delivered = new AtomicReference<>();
    EmailChallengeService service = new EmailChallengeService(
        (email, code) -> delivered.set(code), Duration.ofMinutes(10));
    UUID playerId = UUID.randomUUID();
    service.begin(playerId, "player@example.com");

    assertFalse(service.confirmAndExecute(playerId, delivered.get(), ignored -> false));
    assertFalse(service.confirmAndExecute(playerId, delivered.get(), ignored -> {
      throw new IllegalStateException("binding unavailable");
    }));
    assertTrue(service.confirmAndExecute(playerId, delivered.get(), ignored -> true));
    assertThrows(IllegalStateException.class,
        () -> service.confirmAndExecute(playerId, delivered.get(), ignored -> true));
  }

  @Test
  void confirmsOnceAndRejectsReplay() {
    AtomicReference<String> delivered = new AtomicReference<>();
    EmailChallengeService service = new EmailChallengeService(
        (email, code) -> delivered.set(code), Duration.ofMinutes(10));
    UUID playerId = UUID.randomUUID();

    service.begin(playerId, " Player@Example.com ");
    assertTrue(delivered.get().matches("\\d{6}"));
    assertEquals("player@example.com", service.confirm(playerId, delivered.get()));
    assertThrows(IllegalStateException.class,
        () -> service.confirm(playerId, delivered.get()));
  }

  @Test
  void rejectsWrongCodeWithoutConsumingChallenge() {
    AtomicReference<String> delivered = new AtomicReference<>();
    EmailChallengeService service = new EmailChallengeService(
        (email, code) -> delivered.set(code), Duration.ofMinutes(10));
    UUID playerId = UUID.randomUUID();

    service.begin(playerId, "player@example.com");
    assertThrows(IllegalArgumentException.class, () -> service.confirm(playerId, "000000"));
    assertEquals("player@example.com", service.confirm(playerId, delivered.get()));
  }

  @Test
  void memoryChallengeUsesInjectedClock() {
    AtomicReference<String> delivered = new AtomicReference<>();
    Clock clock = Clock.fixed(Instant.parse("2035-01-01T00:00:00Z"), ZoneOffset.UTC);
    EmailChallengeService service = new EmailChallengeService(
        (email, code) -> delivered.set(code), Duration.ofMinutes(10), null, null, clock);
    UUID playerId = UUID.randomUUID();

    service.begin(playerId, "player@example.com");

    assertEquals("player@example.com", service.confirm(playerId, delivered.get()));
  }

  @Test
  void concurrentConfirmConsumesMemoryChallengeOnce() throws Exception {
    AtomicReference<String> delivered = new AtomicReference<>();
    EmailChallengeService service = new EmailChallengeService(
        (email, code) -> delivered.set(code), Duration.ofMinutes(10));
    UUID playerId = UUID.randomUUID();
    service.begin(playerId, "player@example.com");
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger successes = new AtomicInteger();

    try (var pool = Executors.newFixedThreadPool(8)) {
      for (int i = 0; i < 16; i++) {
        pool.submit(() -> {
          start.await();
          try {
            service.confirm(playerId, delivered.get());
            successes.incrementAndGet();
          } catch (IllegalArgumentException | IllegalStateException ignored) {
          }
          return null;
        });
      }
      start.countDown();
      pool.shutdown();
      assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    }

    assertEquals(1, successes.get());
  }

  @Test
  void memoryEmailCodeAcceptsAHistoricalMinecraftUuid() {
    AtomicReference<String> delivered = new AtomicReference<>();
    UUID offlineUuid = UUID.randomUUID();
    UUID premiumUuid = UUID.randomUUID();
    Clock clock = Clock.fixed(Instant.parse("2035-01-01T00:00:00Z"), ZoneOffset.UTC);
    EmailChallengeService service = new EmailChallengeService(
        (email, code) -> delivered.set(code), Duration.ofMinutes(10),
        null, null, clock,
        requested -> Set.of(offlineUuid, premiumUuid));

    service.begin(offlineUuid, "player@example.com");

    assertEquals("player@example.com", service.confirm(premiumUuid, delivered.get()));
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
