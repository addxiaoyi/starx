package io.github.addxiaoyi.starx.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.common.binding.BindingChallenge;
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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

final class PersistentChallengeRecoveryTest {
  private static final Duration EXECUTION_LEASE = Duration.ofSeconds(5);
  private static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);

  @TempDir Path tempDir;

  @Test
  void emailRecoveryReusesOperationIdWithoutRepeatingSideEffect() throws Exception {
    SQLiteDataSource source = database("email.db");
    MutableClock clock = new MutableClock(Instant.parse("2026-07-28T12:00:00Z"));
    BindingChallengeService challenges = challenges(source);
    UUID playerId = UUID.fromString("18bbd64b-ebc4-42c7-80de-a5a809615587");
    AtomicReference<String> code = new AtomicReference<>();
    EmailChallengeService emails = new EmailChallengeService(
        (email, value) -> code.set(value), CHALLENGE_TTL, challenges,
        ignored -> "account-1", clock);
    emails.begin(playerId, "player@example.com");

    BindingChallenge stored = challenges.inspectExecutable("EMAIL", code.get(), clock.instant());
    BindingChallengeService.Execution first = challenges.acquire(stored, clock.instant());
    Map<String, String> completed = new HashMap<>();
    completed.put(first.operationId(), stored.payload());
    AtomicInteger effects = new AtomicInteger(1);
    AtomicInteger callbacks = new AtomicInteger();

    assertFalse(emails.confirmAndExecute(playerId, code.get(), (operationId, email) -> {
      callbacks.incrementAndGet();
      return true;
    }));
    assertEquals(0, callbacks.get());

    clock.advance(EXECUTION_LEASE.plusSeconds(1));
    assertTrue(emails.confirmAndExecute(playerId, code.get(), (operationId, email) -> {
      callbacks.incrementAndGet();
      assertEquals(first.operationId(), operationId);
      if (completed.putIfAbsent(operationId, email) == null) effects.incrementAndGet();
      return true;
    }));
    assertEquals(1, callbacks.get());
    assertEquals(1, effects.get());
    assertFalse(emails.confirmAndExecute(playerId, code.get(), (operationId, email) -> true));
  }

  @Test
  void qqRecoveryReusesOperationIdWithoutRepeatingBinding() throws Exception {
    SQLiteDataSource source = database("qq.db");
    MutableClock clock = new MutableClock(Instant.parse("2026-07-28T12:00:00Z"));
    BindingChallengeService challenges = challenges(source);
    UUID playerId = UUID.fromString("76301e72-8eb4-4a61-995e-8f273cb3c6bf");
    BindingVerificationService bindings = new BindingVerificationService(
        challenges, ignored -> "account-1",
        account -> account.equals("account-1") ? playerId : null,
        clock, CHALLENGE_TTL);
    String code = bindings.generateCode(playerId);

    BindingChallenge stored = challenges.inspectExecutable("QQ", code, clock.instant());
    BindingChallengeService.Execution first = challenges.acquire(stored, clock.instant());
    Map<String, UUID> completed = new HashMap<>();
    completed.put(first.operationId(), playerId);
    AtomicInteger effects = new AtomicInteger(1);
    AtomicInteger callbacks = new AtomicInteger();

    assertNull(bindings.verifyAndExecute(code, (operationId, candidate) -> {
      callbacks.incrementAndGet();
      return true;
    }));
    assertEquals(0, callbacks.get());

    clock.advance(EXECUTION_LEASE.plusSeconds(1));
    assertEquals(playerId, bindings.verifyAndExecute(code, (operationId, candidate) -> {
      callbacks.incrementAndGet();
      assertEquals(first.operationId(), operationId);
      if (completed.putIfAbsent(operationId, candidate) == null) effects.incrementAndGet();
      return true;
    }));
    assertEquals(1, callbacks.get());
    assertEquals(1, effects.get());
    assertNull(bindings.verifyAndExecute(code, (operationId, candidate) -> true));
  }

  @Test
  void qqCodeExpiresWhileBindingIsExecuting() throws Exception {
    SQLiteDataSource source = database("qq-expiry.db");
    MutableClock clock = new MutableClock(Instant.parse("2026-07-28T12:00:00Z"));
    BindingChallengeService challenges = challenges(source);
    UUID playerId = UUID.fromString("76301e72-8eb4-4a61-995e-8f273cb3c6bf");
    BindingVerificationService bindings = new BindingVerificationService(
        challenges, ignored -> "account-1",
        account -> account.equals("account-1") ? playerId : null,
        clock, CHALLENGE_TTL);
    String code = bindings.generateCode(playerId);

    assertNull(bindings.verifyAndExecute(code, (operationId, candidate) -> {
      clock.advance(Duration.ofMinutes(6));
      return true;
    }));
    assertNull(bindings.verifyAndExecute(code, (operationId, candidate) -> true));
  }

  @Test
  void crossDeviceRecoveryReusesOperationIdWithoutRepeatingAction() throws Exception {
    SQLiteDataSource source = database("cross-device.db");
    MutableClock clock = new MutableClock(Instant.parse("2026-07-28T12:00:00Z"));
    BindingChallengeService challenges = challenges(source);
    UUID playerId = UUID.fromString("6e05dc86-5ac5-4ed7-8819-ac9f5ed90dfe");
    String token = "cross-device-token-000000000000000000000001";
    CrossDeviceApprovalService approvals = new CrossDeviceApprovalService(
        clock, CHALLENGE_TTL, () -> token, challenges,
        ignored -> "account-1",
        account -> account.equals("account-1") ? playerId : null,
        account -> account.equals("account-1") ? "Player" : null);
    CrossDeviceApprovalService.Challenge created = approvals.create(
        playerId, "Player", CrossDeviceApprovalService.Action.BIND_EMAIL);

    BindingChallenge stored = challenges.inspectExecutable(
        "X_EMAIL", created.token(), clock.instant());
    BindingChallengeService.Execution first = challenges.acquire(stored, clock.instant());
    Map<String, String> completed = new HashMap<>();
    completed.put(first.operationId(), "player@example.com");
    AtomicInteger effects = new AtomicInteger(1);
    AtomicInteger callbacks = new AtomicInteger();

    assertEquals(CrossDeviceApprovalService.Status.UNKNOWN,
        approvals.approveAndExecute(
            token, playerId, "Player", CrossDeviceApprovalService.Action.BIND_EMAIL,
            (operationId, challenge) -> {
              callbacks.incrementAndGet();
              return true;
            }).status());
    assertEquals(0, callbacks.get());

    clock.advance(EXECUTION_LEASE.plusSeconds(1));
    assertEquals(CrossDeviceApprovalService.Status.APPROVED,
        approvals.approveAndExecute(
            token, playerId, "Player", CrossDeviceApprovalService.Action.BIND_EMAIL,
            (operationId, challenge) -> {
              callbacks.incrementAndGet();
              assertEquals(first.operationId(), operationId);
              if (completed.putIfAbsent(operationId, "player@example.com") == null) {
                effects.incrementAndGet();
              }
              return true;
            }).status());
    assertEquals(1, callbacks.get());
    assertEquals(1, effects.get());
    assertEquals(CrossDeviceApprovalService.Status.UNKNOWN,
        approvals.approveAndExecute(
            token, playerId, "Player", CrossDeviceApprovalService.Action.BIND_EMAIL,
            (operationId, challenge) -> true).status());
  }

  @Test
  void crossDeviceApprovalAcceptsAnotherUuidForTheSameAccount() throws Exception {
    SQLiteDataSource source = database("cross-device-alias.db");
    MutableClock clock = new MutableClock(Instant.parse("2026-07-28T12:00:00Z"));
    BindingChallengeService challenges = challenges(source);
    UUID legacyUuid = UUID.fromString("6e05dc86-5ac5-4ed7-8819-ac9f5ed90dfe");
    UUID currentUuid = UUID.fromString("7e05dc86-5ac5-4ed7-8819-ac9f5ed90dfe");
    CrossDeviceApprovalService approvals = new CrossDeviceApprovalService(
        clock, CHALLENGE_TTL, () -> "cross-device-alias-token-00000000000000000001", challenges,
        (playerId, username) -> "account-1",
        account -> account.equals("account-1") ? currentUuid : null,
        account -> account.equals("account-1") ? "Player" : null);

    CrossDeviceApprovalService.Challenge created = approvals.create(
        currentUuid, "Player", CrossDeviceApprovalService.Action.BIND_EMAIL);

    assertEquals(CrossDeviceApprovalService.Status.APPROVED,
        approvals.approveAndExecute(
            created.token(), legacyUuid, "PLAYER", CrossDeviceApprovalService.Action.BIND_EMAIL,
            (operationId, challenge) -> true).status());
  }

  private BindingChallengeService challenges(SQLiteDataSource source) {
    return new BindingChallengeService(
        new JdbcBindingChallengeRepository(source), EXECUTION_LEASE);
  }

  private SQLiteDataSource database(String fileName) throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve(fileName).toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("CREATE TABLE starx_accounts (account_id VARCHAR(64) PRIMARY KEY, created_at BIGINT NOT NULL)");
      sql.execute(JdbcBindingChallengeRepository.CREATE_TABLE_SQL);
      sql.execute("INSERT INTO starx_accounts VALUES ('account-1', 1)");
    }
    return source;
  }

  private static final class MutableClock extends Clock {
    private Instant current;

    private MutableClock(Instant current) {
      this.current = current;
    }

    private void advance(Duration duration) {
      this.current = this.current.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return this.current;
    }
  }
}
