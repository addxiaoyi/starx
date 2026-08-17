package io.github.addxiaoyi.starx.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import io.github.addxiaoyi.starx.common.binding.BindingChallengeService;
import io.github.addxiaoyi.starx.common.binding.JdbcBindingChallengeRepository;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.junit.jupiter.api.Test;

class CrossDeviceApprovalServiceTest {
  private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000123");

  @Test
  void concurrentMemoryApprovalExecutesActionOnce() throws Exception {
    CrossDeviceApprovalService service = new CrossDeviceApprovalService();
    CrossDeviceApprovalService.Challenge challenge = service.create(
        PLAYER_ID, "Alex", CrossDeviceApprovalService.Action.BIND_EMAIL);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger executions = new AtomicInteger();
    AtomicInteger approved = new AtomicInteger();

    try (var pool = Executors.newFixedThreadPool(8)) {
      for (int i = 0; i < 16; i++) {
        pool.submit(() -> {
          start.await();
          CrossDeviceApprovalService.Approval response = service.approveAndExecute(
              challenge.token(), PLAYER_ID, "Alex",
              CrossDeviceApprovalService.Action.BIND_EMAIL, ignored -> {
                executions.incrementAndGet();
                return true;
              });
          if (response.success()) approved.incrementAndGet();
          return null;
        });
      }
      start.countDown();
      pool.shutdown();
      assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    }

    assertEquals(1, executions.get());
    assertEquals(1, approved.get());
  }

  @Test
  void tokenCollisionDoesNotReplaceAnExistingMemoryChallenge() {
    String firstToken = "collision-token-with-at-least-32-characters-0001";
    String secondToken = "collision-token-with-at-least-32-characters-0002";
    String[] generated = {firstToken, firstToken, secondToken};
    AtomicInteger index = new AtomicInteger();
    CrossDeviceApprovalService service = new CrossDeviceApprovalService(
        Clock.systemUTC(), Duration.ofMinutes(5),
        () -> generated[Math.min(index.getAndIncrement(), generated.length - 1)]);

    CrossDeviceApprovalService.Challenge first = service.create(
        PLAYER_ID, "Alex", CrossDeviceApprovalService.Action.BIND_EMAIL);
    CrossDeviceApprovalService.Challenge second = service.create(
        UUID.randomUUID(), "Blair", CrossDeviceApprovalService.Action.BIND_EMAIL);

    assertEquals(firstToken, first.token());
    assertEquals(secondToken, second.token());
    assertTrue(service.approve(first.token(), PLAYER_ID, "Alex",
        CrossDeviceApprovalService.Action.BIND_EMAIL).success());
  }

  @Test
  void persistentApprovalSurvivesRestartAndRejectsReplay(@TempDir Path tempDir) throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("approval.db").toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("CREATE TABLE starx_accounts (account_id VARCHAR(64) PRIMARY KEY, created_at BIGINT NOT NULL)");
      sql.execute(JdbcBindingChallengeRepository.CREATE_TABLE_SQL);
      sql.execute("INSERT INTO starx_accounts VALUES ('account-1', 1)");
    }
    Clock clock = Clock.fixed(Instant.parse("2026-07-22T00:00:00Z"), ZoneOffset.UTC);
    BindingChallengeService challenges =
        new BindingChallengeService(new JdbcBindingChallengeRepository(source));
    CrossDeviceApprovalService first = new CrossDeviceApprovalService(
        clock, Duration.ofMinutes(5), () -> "persistent-opaque-token-with-enough-entropy",
        challenges, ignored -> "account-1", ignored -> PLAYER_ID, ignored -> "alex");
    CrossDeviceApprovalService.Challenge challenge = first.create(
        PLAYER_ID, "Alex", CrossDeviceApprovalService.Action.BIND_SKIN_ACCOUNT);
    CrossDeviceApprovalService restarted = new CrossDeviceApprovalService(
        clock, Duration.ofMinutes(5), () -> "unused-token-with-enough-entropy-after-restart",
        challenges, ignored -> "account-1", ignored -> PLAYER_ID, ignored -> "alex");

    assertTrue(restarted.approve(challenge.token(), PLAYER_ID, "Alex",
        CrossDeviceApprovalService.Action.BIND_SKIN_ACCOUNT).success());
    assertFalse(first.approve(challenge.token(), PLAYER_ID, "Alex",
        CrossDeviceApprovalService.Action.BIND_SKIN_ACCOUNT).success());
  }

  @Test
  void persistentTokenCollisionRetriesWithoutReplacingTheOriginalChallenge(@TempDir Path tempDir)
      throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("persistent-token-collision.db").toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("CREATE TABLE starx_accounts (account_id VARCHAR(64) PRIMARY KEY, created_at BIGINT NOT NULL)");
      sql.execute(JdbcBindingChallengeRepository.CREATE_TABLE_SQL);
      sql.execute("INSERT INTO starx_accounts VALUES ('account-1', 1)");
    }
    String firstToken = "persistent-collision-token-with-at-least-32-0001";
    String secondToken = "persistent-collision-token-with-at-least-32-0002";
    String[] generated = {firstToken, firstToken, secondToken};
    AtomicInteger index = new AtomicInteger();
    CrossDeviceApprovalService service = new CrossDeviceApprovalService(
        Clock.systemUTC(), Duration.ofMinutes(5),
        () -> generated[Math.min(index.getAndIncrement(), generated.length - 1)],
        new BindingChallengeService(new JdbcBindingChallengeRepository(source)),
        ignored -> "account-1", ignored -> PLAYER_ID, ignored -> "Alex");

    CrossDeviceApprovalService.Challenge first = service.create(
        PLAYER_ID, "Alex", CrossDeviceApprovalService.Action.BIND_EMAIL);
    CrossDeviceApprovalService.Challenge second = service.create(
        PLAYER_ID, "Alex", CrossDeviceApprovalService.Action.BIND_EMAIL);

    assertEquals(firstToken, first.token());
    assertEquals(secondToken, second.token());
    assertTrue(service.approve(first.token(), PLAYER_ID, "Alex",
        CrossDeviceApprovalService.Action.BIND_EMAIL).success());
  }

  @Test
  void persistentApprovalExpiresWhileItsActionIsExecuting(@TempDir Path tempDir) throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("approval-expiry.db").toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("CREATE TABLE starx_accounts (account_id VARCHAR(64) PRIMARY KEY, created_at BIGINT NOT NULL)");
      sql.execute(JdbcBindingChallengeRepository.CREATE_TABLE_SQL);
      sql.execute("INSERT INTO starx_accounts VALUES ('account-1', 1)");
    }
    MutableClock clock = new MutableClock(Instant.parse("2026-07-22T00:00:00Z"), ZoneOffset.UTC);
    BindingChallengeService challenges =
        new BindingChallengeService(new JdbcBindingChallengeRepository(source));
    CrossDeviceApprovalService service = new CrossDeviceApprovalService(
        clock, Duration.ofMinutes(5), () -> "expiring-approval-token-with-enough-entropy",
        challenges, ignored -> "account-1", ignored -> PLAYER_ID, ignored -> "alex");
    CrossDeviceApprovalService.Challenge challenge = service.create(
        PLAYER_ID, "Alex", CrossDeviceApprovalService.Action.BIND_EMAIL);
    clock.advance(Duration.ofMinutes(4).plusSeconds(59));

    assertEquals(CrossDeviceApprovalService.Status.APPROVED,
        service.approveAndExecute(
            challenge.token(), PLAYER_ID, "Alex", CrossDeviceApprovalService.Action.BIND_EMAIL,
            ignored -> {
              clock.advance(Duration.ofSeconds(2));
              return true;
            }).status());
  }

  @Test
  void memoryApprovalCompletesWhenExecutionCrossesTheExpiryBoundary() {
    MutableClock clock = new MutableClock(Instant.parse("2026-07-22T00:00:00Z"), ZoneOffset.UTC);
    CrossDeviceApprovalService service = new CrossDeviceApprovalService(
        clock, Duration.ofMinutes(5), () -> "memory-expiry-token-with-enough-entropy");
    CrossDeviceApprovalService.Challenge challenge = service.create(
        PLAYER_ID, "Alex", CrossDeviceApprovalService.Action.BIND_EMAIL);

    assertEquals(CrossDeviceApprovalService.Status.APPROVED,
        service.approveAndExecute(
            challenge.token(), PLAYER_ID, "Alex", CrossDeviceApprovalService.Action.BIND_EMAIL,
            ignored -> {
              clock.advance(Duration.ofMinutes(6));
              return true;
            }).status());
  }

  @Test
  void memoryApprovalDoesNotStartAfterWaitingPastExpiry() throws Exception {
    MutableClock clock = new MutableClock(Instant.parse("2026-07-22T00:00:00Z"), ZoneOffset.UTC);
    CrossDeviceApprovalService service = new CrossDeviceApprovalService(
        clock, Duration.ofMinutes(5), () -> "memory-race-token-with-enough-entropy");
    CrossDeviceApprovalService.Challenge challenge = service.create(
        PLAYER_ID, "Alex", CrossDeviceApprovalService.Action.BIND_EMAIL);
    CountDownLatch firstStarted = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    AtomicInteger executions = new AtomicInteger();
    AtomicReference<CrossDeviceApprovalService.Approval> secondResult = new AtomicReference<>();

    Thread first = new Thread(() -> service.approveAndExecute(
        challenge.token(), PLAYER_ID, "Alex", CrossDeviceApprovalService.Action.BIND_EMAIL,
        ignored -> {
          executions.incrementAndGet();
          firstStarted.countDown();
          try {
            releaseFirst.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
          }
          return false;
        }));
    first.start();
    assertTrue(firstStarted.await(5, TimeUnit.SECONDS));

    Thread second = new Thread(() -> secondResult.set(service.approveAndExecute(
        challenge.token(), PLAYER_ID, "Alex", CrossDeviceApprovalService.Action.BIND_EMAIL,
        ignored -> {
          executions.incrementAndGet();
          return true;
        })));
    second.start();
    clock.advance(Duration.ofMinutes(6));
    releaseFirst.countDown();
    first.join(5_000);
    second.join(5_000);

    assertEquals(CrossDeviceApprovalService.Status.EXPIRED, secondResult.get().status());
    assertEquals(1, executions.get());
  }

  @Test
  void passesPlayerNameToPersistentAccountResolver(@TempDir Path tempDir) throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("resolver-context.db").toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("CREATE TABLE starx_accounts (account_id VARCHAR(64) PRIMARY KEY, created_at BIGINT NOT NULL)");
      sql.execute(JdbcBindingChallengeRepository.CREATE_TABLE_SQL);
      sql.execute("INSERT INTO starx_accounts VALUES ('account-1', 1)");
    }
    AtomicReference<String> resolvedName = new AtomicReference<>();
    CrossDeviceApprovalService service = new CrossDeviceApprovalService(
        new BindingChallengeService(new JdbcBindingChallengeRepository(source)),
        (playerId, username) -> {
          resolvedName.set(username);
          return "account-1";
        },
        ignored -> PLAYER_ID,
        ignored -> "Alex");

    service.create(PLAYER_ID, "Alex", CrossDeviceApprovalService.Action.BIND_EMAIL);

    assertEquals("alex", resolvedName.get());
  }

  @Test
  void persistentConfirmationDoesNotLetTheRequestRenameTheIdentity(@TempDir Path tempDir)
      throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("confirmation-identity.db").toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("CREATE TABLE starx_accounts (account_id VARCHAR(64) PRIMARY KEY, created_at BIGINT NOT NULL)");
      sql.execute(JdbcBindingChallengeRepository.CREATE_TABLE_SQL);
      sql.execute("INSERT INTO starx_accounts VALUES ('account-1', 1)");
    }
    AtomicReference<String> currentName = new AtomicReference<>("alex");
    CrossDeviceApprovalService service = new CrossDeviceApprovalService(
        new BindingChallengeService(new JdbcBindingChallengeRepository(source)),
        (playerId, username) -> {
          if (username != null) currentName.set(username);
          return "account-1";
        },
        ignored -> PLAYER_ID,
        ignored -> currentName.get());

    CrossDeviceApprovalService.Challenge challenge = service.create(
        PLAYER_ID, "Alex", CrossDeviceApprovalService.Action.BIND_EMAIL);

    assertEquals(CrossDeviceApprovalService.Status.MISMATCH,
        service.approve(challenge.token(), PLAYER_ID, "Impostor",
            CrossDeviceApprovalService.Action.BIND_EMAIL).status());
    assertEquals("alex", currentName.get());
  }

  @Test
  void persistentConfirmationFailsClosedWhenItsAccountWasDeleted(@TempDir Path tempDir)
      throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("deleted-account.db").toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("CREATE TABLE starx_accounts (account_id VARCHAR(64) PRIMARY KEY, created_at BIGINT NOT NULL)");
      sql.execute(JdbcBindingChallengeRepository.CREATE_TABLE_SQL);
      sql.execute("INSERT INTO starx_accounts VALUES ('account-1', 1)");
    }
    BindingChallengeService challenges =
        new BindingChallengeService(new JdbcBindingChallengeRepository(source));
    CrossDeviceApprovalService service = new CrossDeviceApprovalService(
        Clock.systemUTC(), Duration.ofMinutes(5),
        () -> "deleted-account-token-with-enough-entropy",
        challenges, ignored -> "account-1", ignored -> null, ignored -> "Alex");
    CrossDeviceApprovalService.Challenge challenge = service.create(
        PLAYER_ID, "Alex", CrossDeviceApprovalService.Action.BIND_EMAIL);
    AtomicInteger executions = new AtomicInteger();

    assertEquals(CrossDeviceApprovalService.Status.UNKNOWN,
        service.approveAndExecute(
            challenge.token(), PLAYER_ID, "Alex",
            CrossDeviceApprovalService.Action.BIND_EMAIL,
            ignored -> {
              executions.incrementAndGet();
              return true;
            }).status());
    assertEquals(0, executions.get());
  }

  @Test
  void bindsApprovalToPlayerNameAndActionAndConsumesOnce() {
    MutableClock clock = new MutableClock(Instant.parse("2026-07-22T00:00:00Z"), ZoneOffset.UTC);
    CrossDeviceApprovalService service = new CrossDeviceApprovalService(
        clock, Duration.ofMinutes(5), () -> "opaque-token-with-256-bits-of-test-entropy");
    CrossDeviceApprovalService.Challenge challenge = service.create(
        PLAYER_ID, "Alex", CrossDeviceApprovalService.Action.BIND_EMAIL);

    assertFalse(service.approve(sha256(challenge.token()), PLAYER_ID, "Alex",
        CrossDeviceApprovalService.Action.BIND_EMAIL).success());
    assertFalse(service.approve(challenge.token(), UUID.randomUUID(), "Alex",
        CrossDeviceApprovalService.Action.BIND_EMAIL).success());
    assertTrue(service.approve(challenge.token(), PLAYER_ID, "Alex",
        CrossDeviceApprovalService.Action.BIND_EMAIL).success());
    assertFalse(service.approve(challenge.token(), PLAYER_ID, "Alex",
        CrossDeviceApprovalService.Action.BIND_EMAIL).success());
  }

  @Test
  void memoryApprovalAcceptsAKnownMinecraftIdentityAlias() {
    UUID legacyUuid = UUID.randomUUID();
    UUID currentUuid = UUID.randomUUID();
    CrossDeviceApprovalService service = new CrossDeviceApprovalService(
        Clock.systemUTC(), Duration.ofMinutes(5),
        () -> "memory-alias-token-with-enough-test-entropy");
    service.bindKnownMinecraftUuidsResolver(ignored -> Set.of(legacyUuid, currentUuid));
    CrossDeviceApprovalService.Challenge challenge = service.create(
        legacyUuid, "Alex", CrossDeviceApprovalService.Action.BIND_EMAIL);

    assertTrue(service.approve(challenge.token(), currentUuid, "Alex",
        CrossDeviceApprovalService.Action.BIND_EMAIL).success());
  }

  private static String sha256(String value) {
    try {
      byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest);
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new AssertionError(error);
    }
  }

  @Test
  void rejectsExpiredAndMismatchedActions() {
    MutableClock clock = new MutableClock(Instant.parse("2026-07-22T00:00:00Z"), ZoneOffset.UTC);
    CrossDeviceApprovalService service = new CrossDeviceApprovalService(
        clock, Duration.ofMinutes(5), () -> "another-opaque-token-with-enough-test-entropy");
    CrossDeviceApprovalService.Challenge challenge = service.create(
        PLAYER_ID, "Alex", CrossDeviceApprovalService.Action.ENABLE_TOTP);

    assertEquals(CrossDeviceApprovalService.Status.MISMATCH,
        service.approve(challenge.token(), PLAYER_ID, "Alex",
            CrossDeviceApprovalService.Action.BIND_SKIN_ACCOUNT).status());
    clock.advance(Duration.ofMinutes(6));
    assertEquals(CrossDeviceApprovalService.Status.EXPIRED,
        service.approve(challenge.token(), PLAYER_ID, "Alex",
            CrossDeviceApprovalService.Action.ENABLE_TOTP).status());
  }

  @Test
  void cancellationIsIdentityBoundAndPreventsLaterApproval() {
    CrossDeviceApprovalService service = new CrossDeviceApprovalService(
        Clock.systemUTC(), Duration.ofMinutes(5),
        () -> "cancelable-opaque-token-with-enough-test-entropy");
    CrossDeviceApprovalService.Challenge challenge = service.create(
        PLAYER_ID, "Alex", CrossDeviceApprovalService.Action.BIND_EMAIL);

    assertEquals(CrossDeviceApprovalService.Status.MISMATCH,
        service.cancel(challenge.token(), UUID.randomUUID(), "Alex").status());
    assertEquals(CrossDeviceApprovalService.Status.CANCELLED,
        service.cancel(challenge.token(), PLAYER_ID, "Alex").status());
    assertEquals(CrossDeviceApprovalService.Status.UNKNOWN,
        service.approve(challenge.token(), PLAYER_ID, "Alex",
            CrossDeviceApprovalService.Action.BIND_EMAIL).status());
  }

  @Test
  void keepsTokenWhenAtomicActionFailsAndConsumesItAfterSuccess() {
    CrossDeviceApprovalService service = new CrossDeviceApprovalService(
        Clock.systemUTC(), Duration.ofMinutes(5),
        () -> "action-token-with-enough-test-entropy-256-bits");
    CrossDeviceApprovalService.Challenge challenge = service.create(
        PLAYER_ID, "Alex", CrossDeviceApprovalService.Action.BIND_EMAIL);

    assertEquals(CrossDeviceApprovalService.Status.EXECUTION_FAILED,
        service.approveAndExecute(challenge.token(), PLAYER_ID, "Alex",
            CrossDeviceApprovalService.Action.BIND_EMAIL, ignored -> false).status());
    assertEquals(CrossDeviceApprovalService.Status.APPROVED,
        service.approveAndExecute(challenge.token(), PLAYER_ID, "Alex",
            CrossDeviceApprovalService.Action.BIND_EMAIL, ignored -> true).status());
    assertEquals(CrossDeviceApprovalService.Status.UNKNOWN,
        service.approveAndExecute(challenge.token(), PLAYER_ID, "Alex",
            CrossDeviceApprovalService.Action.BIND_EMAIL, ignored -> true).status());
  }

  @Test
  void loginApprovalCarriesTheExactAuthLeaseAndConsumesOnce() {
    AuthLease lease = new AuthLease(
        UUID.fromString("10000000-0000-0000-0000-000000000001"));
    CrossDeviceApprovalService service = new CrossDeviceApprovalService(
        Clock.systemUTC(), Duration.ofMinutes(5),
        () -> "login-approval-token-with-enough-test-entropy");
    CrossDeviceApprovalService.Challenge challenge =
        service.createLogin(PLAYER_ID, "Alex", lease);
    AtomicInteger executions = new AtomicInteger();

    assertEquals(CrossDeviceApprovalService.Action.APPROVE_LOGIN, challenge.action());
    assertEquals(lease, challenge.authLease());
    assertEquals(CrossDeviceApprovalService.Status.APPROVED,
        service.approveAndExecute(
            challenge.token(), PLAYER_ID, "Alex",
            CrossDeviceApprovalService.Action.APPROVE_LOGIN, approved -> {
              assertEquals(lease, approved.authLease());
              executions.incrementAndGet();
              return true;
            }).status());
    assertEquals(CrossDeviceApprovalService.Status.UNKNOWN,
        service.approveAndExecute(
            challenge.token(), PLAYER_ID, "Alex",
            CrossDeviceApprovalService.Action.APPROVE_LOGIN, ignored -> true).status());
    assertEquals(1, executions.get());
  }

  private static final class MutableClock extends Clock {
    private Instant now;
    private final ZoneId zone;

    private MutableClock(Instant now, ZoneId zone) {
      this.now = now;
      this.zone = zone;
    }

    void advance(Duration duration) {
      this.now = this.now.plus(duration);
    }

    @Override public ZoneId getZone() { return this.zone; }
    @Override public Clock withZone(ZoneId zone) { return new MutableClock(this.now, zone); }
    @Override public Instant instant() { return this.now; }
  }
}
