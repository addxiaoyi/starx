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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
