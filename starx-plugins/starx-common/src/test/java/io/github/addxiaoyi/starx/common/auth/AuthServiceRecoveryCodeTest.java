package io.github.addxiaoyi.starx.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.common.crypto.PasswordHasher;
import io.github.addxiaoyi.starx.common.crypto.TotpGenerator;
import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import io.github.addxiaoyi.starx.common.event.LocalEventBus;
import io.github.addxiaoyi.starx.common.model.StarxUser;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

final class AuthServiceRecoveryCodeTest {

  private static final String PASSWORD = "ValidPassword_123";

  @TempDir
  Path tempDir;

  private JdbcUserRepository users;
  private SessionManager sessions;
  private AuthService auth;
  private UUID playerId;
  private List<String> recoveryCodes;
  private String totpSecret;

  @BeforeEach
  void setUp() throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + this.tempDir.resolve("auth.db").toAbsolutePath());
    try (Connection connection = source.getConnection();
         Statement statement = connection.createStatement()) {
      statement.execute("""
          CREATE TABLE starx_users (
            uuid VARCHAR(36) PRIMARY KEY,
            username VARCHAR(255) NOT NULL,
            email VARCHAR(255),
            password_hash VARCHAR(255),
            totp_secret VARCHAR(255),
            premium BOOLEAN NOT NULL DEFAULT FALSE,
            created_at TIMESTAMP NOT NULL,
            last_login_at TIMESTAMP,
            external_user_id VARCHAR(255),
            trusted_devices TEXT,
            recovery_codes VARCHAR(512),
            source_system VARCHAR(50),
            migration_state VARCHAR(20),
            password_migrated_at TIMESTAMP,
            last_login_ip VARCHAR(255),
            last_login_isp VARCHAR(255),
            last_login_location VARCHAR(255),
            total_playtime BIGINT DEFAULT 0,
            last_logout_at TIMESTAMP,
            welcome_message_shown BOOLEAN DEFAULT FALSE
          )
          """);
    }

    this.users = new JdbcUserRepository(source);
    this.sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    LocalEventBus events = new LocalEventBus();
    this.auth = new AuthService(this.users, events, this.sessions);
    this.playerId = offlineUuid("player");
    this.users.create(new StarxUser(
        this.playerId,
        "player",
        null,
        PasswordHasher.hash(PASSWORD),
        null,
        false,
        Instant.now(),
        null,
        null,
        List.of(),
        null,
        "local",
        "completed",
        null,
        null,
        null,
        null,
        0L,
        null,
        false));

    AuthResult enabledResult = this.auth.enableTotp(this.playerId, PASSWORD);
    assertTrue(enabledResult.success());
    this.recoveryCodes = enabledResult.recoveryCodes();
    assertNotNull(this.recoveryCodes);
    StarxUser enabled = this.users.findFullByUuid(this.playerId).orElseThrow();
    this.totpSecret = enabled.totpSecret();
    assertNotNull(this.totpSecret);
    assertTrue(enabled.recoveryCodes().startsWith("["));
  }

  @AfterEach
  void tearDown() {
    if (this.sessions != null) {
      this.sessions.shutdown();
    }
  }

  @Test
  void consumesOnlyTheMatchingRecoveryCode() throws InterruptedException {
    AuthLease firstLease = this.openAuthenticatingSession();

    assertTrue(this.auth.verifyRecoveryCode(
        firstLease, this.playerId, this.recoveryCodes.get(0)).success());

    AuthLease secondLease = this.openAuthenticatingSession();
    assertFalse(this.auth.verifyRecoveryCode(
        secondLease, this.playerId, this.recoveryCodes.get(0)).success());
    Thread.sleep(1_100L);
    assertTrue(this.auth.verifyRecoveryCode(
        secondLease, this.playerId, this.recoveryCodes.get(1)).success());
  }

  @Test
  void staleLeaseCannotConsumeTheReplacementConnectionsCode() {
    AuthLease staleLease = this.openAuthenticatingSession();
    AuthLease currentLease = this.openAuthenticatingSession();

    assertFalse(this.auth.verifyRecoveryCode(
        staleLease, this.playerId, this.recoveryCodes.get(0)).success());
    assertTrue(this.auth.verifyRecoveryCode(
        currentLease, this.playerId, this.recoveryCodes.get(0)).success());
  }

  @Test
  void staleLeaseCannotVerifyTotpForTheReplacementConnection() {
    AuthLease staleLease = this.openAuthenticatingSession();
    AuthLease currentLease = this.openAuthenticatingSession();

    assertFalse(this.auth.verifyTotp(
        staleLease,
        this.playerId,
        TotpGenerator.generate(this.totpSecret, Instant.now())).success());
    assertTrue(this.auth.verifyTotp(
        currentLease,
        this.playerId,
        TotpGenerator.generate(this.totpSecret, Instant.now())).success());
  }

  @Test
  void invalidTotpAttemptIsRateLimitedBeforeAValidRetry() {
    AuthLease lease = this.openAuthenticatingSession();
    String validCode = TotpGenerator.generate(this.totpSecret, Instant.now());
    char replacement = validCode.charAt(5) == '0' ? '1' : '0';
    String invalidCode = validCode.substring(0, 5) + replacement;

    assertFalse(this.auth.verifyTotp(lease, this.playerId, invalidCode).success());
    assertEquals(1, this.auth.bruteForceProtector().getAttemptCount(this.playerId));
    assertFalse(this.auth.verifyTotp(lease, this.playerId, validCode).success());
  }

  @Test
  void totpVerificationResolvesTheStoredAccountFromTheCurrentConnectionUsername() {
    UUID connectionUuid = this.playerId;
    this.auth.bindMinecraftIdentityResolver(ignored -> Set.of(connectionUuid));
    AuthLease lease = AuthLease.create();
    assertNotNull(this.sessions.open(connectionUuid, "player", null, lease));
    assertTrue(this.sessions.transition(
        connectionUuid, lease, AuthSession.State.GUEST, AuthSession.State.AUTHENTICATING));

    AuthResult result = this.auth.verifyTotp(
        lease, connectionUuid, TotpGenerator.generate(this.totpSecret, Instant.now()));

    assertTrue(result.success());
    assertTrue(this.sessions.isState(
        connectionUuid, lease, AuthSession.State.AUTHENTICATED));
  }

  @Test
  void totpVerificationResolvesAStoredAccountThroughArenamedUuidAlias() {
    UUID connectionUuid = this.playerId;
    this.auth.bindMinecraftIdentityResolver(ignored -> Set.of(connectionUuid));
    AuthLease lease = AuthLease.create();
    assertNotNull(this.sessions.open(connectionUuid, "renamed-player", null, lease));
    assertTrue(this.sessions.transition(
        connectionUuid, lease, AuthSession.State.GUEST, AuthSession.State.AUTHENTICATING));

    AuthResult result = this.auth.verifyTotp(
        lease, connectionUuid, TotpGenerator.generate(this.totpSecret, Instant.now()));

    assertTrue(result.success());
    assertTrue(this.sessions.isState(
        connectionUuid, lease, AuthSession.State.AUTHENTICATED));
  }

  @Test
  void recoveryCodeResolvesTheStoredAccountFromTheCurrentConnectionUsername() {
    UUID connectionUuid = UUID.randomUUID();
    this.auth.bindMinecraftIdentityResolver(ignored -> Set.of(connectionUuid, this.playerId));
    AuthLease lease = AuthLease.create();
    assertNotNull(this.sessions.open(connectionUuid, "player", null, lease));
    assertTrue(this.sessions.transition(
        connectionUuid, lease, AuthSession.State.GUEST, AuthSession.State.AUTHENTICATING));

    AuthResult result = this.auth.verifyRecoveryCode(
        lease, connectionUuid, this.recoveryCodes.get(0));

    assertTrue(result.success());
    assertTrue(this.sessions.isState(
        connectionUuid, lease, AuthSession.State.AUTHENTICATED));
  }

  @Test
  void concurrentConsumersCanUseARecoveryCodeOnlyOnce() throws Exception {
    AuthLease lease = this.openAuthenticatingSession();
    String recoveryCode = this.recoveryCodes.get(0);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Future<Boolean> first = workers.submit(() -> {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
          throw new IllegalStateException("Timed out waiting to race recovery-code consumers");
        }
        return this.auth.verifyRecoveryCode(lease, this.playerId, recoveryCode).success();
      });
      Future<Boolean> second = workers.submit(() -> {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
          throw new IllegalStateException("Timed out waiting to race recovery-code consumers");
        }
        return this.auth.verifyRecoveryCode(lease, this.playerId, recoveryCode).success();
      });

      assertTrue(ready.await(10, TimeUnit.SECONDS));
      start.countDown();
      int successes = (first.get(30, TimeUnit.SECONDS) ? 1 : 0)
          + (second.get(30, TimeUnit.SECONDS) ? 1 : 0);

      assertEquals(1, successes);
    } finally {
      start.countDown();
      workers.shutdownNow();
    }
  }

  @Test
  void recoveryCodeUpdateUsesCompareAndSet() {
    String stored = this.users.findFullByUuid(this.playerId).orElseThrow().recoveryCodes();

    assertTrue(this.users.replaceRecoveryCodes(this.playerId, stored, "[]"));
    assertFalse(this.users.replaceRecoveryCodes(this.playerId, stored, "[]"));
  }

  @Test
  void failedAuthenticationRestoresTheConsumedRecoveryCode() {
    UUID connectionUuid = this.playerId;
    java.util.concurrent.atomic.AtomicBoolean failIdentityPersistence =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    this.auth.bindMinecraftIdentityObserver((uuid, username, source) -> {
      if (failIdentityPersistence.getAndSet(false)) {
        throw new IllegalStateException("identity persistence unavailable");
      }
    });
    AuthLease lease = AuthLease.create();
    assertNotNull(this.sessions.open(connectionUuid, "player", null, lease));
    assertTrue(this.sessions.transition(
        connectionUuid, lease, AuthSession.State.GUEST, AuthSession.State.AUTHENTICATING));

    AuthResult failed = this.auth.verifyRecoveryCode(
        lease, connectionUuid, this.recoveryCodes.get(0));

    assertFalse(failed.success(), failed.message());
    AuthLease retryLease = AuthLease.create();
    assertNotNull(this.sessions.open(connectionUuid, "player", null, retryLease));
    assertTrue(this.sessions.transition(
        connectionUuid, retryLease, AuthSession.State.GUEST, AuthSession.State.AUTHENTICATING),
        String.valueOf(this.sessions.get(connectionUuid).map(AuthSession::state).orElse(null)));
    AuthResult retry = this.auth.verifyRecoveryCode(
        retryLease, connectionUuid, this.recoveryCodes.get(0));
    assertTrue(retry.success(), retry.message());
  }

  private AuthLease openAuthenticatingSession() {
    AuthLease lease = AuthLease.create();
    assertNotNull(this.sessions.open(this.playerId, "player", null, lease));
    assertTrue(this.sessions.transition(
        this.playerId,
        lease,
        AuthSession.State.GUEST,
        AuthSession.State.AUTHENTICATING));
    return lease;
  }

  private static UUID offlineUuid(String username) {
    return UUID.nameUUIDFromBytes(
        ("OfflinePlayer:" + username).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
}
