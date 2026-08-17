package io.github.addxiaoyi.starx.common.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.common.crypto.PasswordHasher;
import io.github.addxiaoyi.starx.common.crypto.TotpGenerator;
import io.github.addxiaoyi.starx.common.database.JdbcTrustedDeviceRepository;
import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import io.github.addxiaoyi.starx.common.event.LocalEventBus;
import io.github.addxiaoyi.starx.common.model.IpSession;
import io.github.addxiaoyi.starx.common.model.StarxUser;
import java.net.InetAddress;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class AuthServiceCredentialRevocationTest {
  @TempDir Path tempDir;

  @Test
  void passwordChangeRevokesEveryPreviousTrustGrant() throws Exception {
    Fixture fixture = this.fixture();
    try {
      AuthResult result = fixture.auth.changePassword(
          fixture.playerId, fixture.oldPassword, "NewPassword_456");

      assertTrue(result.success());
      assertFalse(fixture.sessions.get(fixture.playerId).isPresent());
      assertFalse(fixture.ipSessions.hasRecentSessionMinutes(
          fixture.playerId, fixture.address.getHostAddress(), fixture.deviceId, 30));
      assertTrue(fixture.devices.listActive(fixture.playerId, Instant.now()).isEmpty());
      assertTrue(fixture.users.findFullByUuid(fixture.playerId).orElseThrow()
          .trustedDevices().isEmpty());
    } finally {
      fixture.sessions.shutdown();
    }
  }

  @Test
  void passwordChangeNotifiesEveryOnlineMinecraftAlias() throws Exception {
    Fixture fixture = this.fixture();
    UUID legacyUuid = UUID.randomUUID();
    AuthLease legacyLease = AuthLease.create();
    List<UUID> disconnected = new ArrayList<>();
    fixture.auth.bindMinecraftIdentityResolver(
        ignored -> Set.of(fixture.playerId, legacyUuid));
    fixture.sessions.open(
        legacyUuid, "AlexLegacy", fixture.address, "legacy-device", legacyLease);
    LocalEventBus events = new LocalEventBus();
    events.subscribe("player:credentials:changed", event -> {
      Object uuid = event.payload().get("uuid");
      if (uuid instanceof UUID value) disconnected.add(value);
    });
    AuthService auth = new AuthService(
        fixture.users, events, fixture.sessions, fixture.ipSessions, fixture.devices);
    auth.bindMinecraftIdentityResolver(ignored -> Set.of(fixture.playerId, legacyUuid));

    try {
      assertTrue(auth.changePassword(
          fixture.playerId, fixture.oldPassword, "NewPassword_456").success());
      assertEquals(Set.of(fixture.playerId, legacyUuid), Set.copyOf(disconnected));
    } finally {
      fixture.sessions.shutdown();
    }
  }

  @Test
  void passwordChangeRemovesConnectionsUsingNonIdentityConnectionIds() throws Exception {
    Fixture fixture = this.fixture();
    UUID accountAlias = UUID.randomUUID();
    UUID connectionId = UUID.randomUUID();
    AuthLease connectionLease = AuthLease.create();
    fixture.auth.bindMinecraftIdentityResolver(
        ignored -> Set.of(fixture.playerId, accountAlias));
    fixture.sessions.open(connectionId, "Alex", fixture.address, "connection-device", connectionLease);
    AuthService auth = new AuthService(
        fixture.users, new LocalEventBus(), fixture.sessions, fixture.ipSessions, fixture.devices);
    auth.bindMinecraftIdentityResolver(ignored -> Set.of(fixture.playerId, accountAlias));

    try {
      assertTrue(auth.changePassword(
          fixture.playerId, fixture.oldPassword, "NewPassword_456").success());
      assertTrue(fixture.sessions.get(connectionId, connectionLease).isEmpty());
    } finally {
      fixture.sessions.shutdown();
    }
  }

  @Test
  void failedPasswordChangePreservesExistingTrust() throws Exception {
    Fixture fixture = this.fixture();
    try {
      AuthResult result = fixture.auth.changePassword(
          fixture.playerId, "wrong-password", "NewPassword_456");

      assertFalse(result.success());
      assertTrue(fixture.sessions.get(fixture.playerId).isPresent());
      assertTrue(fixture.ipSessions.hasRecentSessionMinutes(
          fixture.playerId, fixture.address.getHostAddress(), fixture.deviceId, 30));
      assertFalse(fixture.devices.listActive(fixture.playerId, Instant.now()).isEmpty());
    } finally {
      fixture.sessions.shutdown();
    }
  }

  @Test
  void passwordWriteFailurePreservesExistingTrust() throws Exception {
    Fixture fixture = this.fixture();
    StarxUser account = fixture.users.findFullByUuid(fixture.playerId).orElseThrow();
    AuthService auth = new AuthService(
        new FailingPasswordUserRepository(account), new LocalEventBus(), fixture.sessions,
        fixture.ipSessions, fixture.devices);
    try {
      AuthResult result = auth.changePassword(
          fixture.playerId, fixture.oldPassword, "NewPassword_456");

      assertFalse(result.success());
      assertTrue(fixture.sessions.get(fixture.playerId).isPresent());
      assertTrue(fixture.ipSessions.hasRecentSessionMinutes(
          fixture.playerId, fixture.address.getHostAddress(), fixture.deviceId, 30));
      assertFalse(fixture.devices.listActive(fixture.playerId, Instant.now()).isEmpty());
      assertTrue(fixture.users.findFullByUuid(fixture.playerId).orElseThrow()
          .trustedDevices().contains(fixture.deviceId));
    } finally {
      fixture.sessions.shutdown();
    }
  }

  @Test
  void revocationFailureNeverCommitsTheNewPasswordFirst() throws Exception {
    Fixture fixture = this.fixture();
    IpSessionStore failingStore = new IpSessionStore() {
      @Override public void save(IpSession session) { }
      @Override public Optional<IpSession> findByUuidAndIp(UUID uuid, String ip) {
        return Optional.empty();
      }
      @Override public boolean hasRecentSession(UUID uuid, String ip, int hours) { return false; }
      @Override public List<IpSession> findRecentSessions(UUID uuid, int hours) { return List.of(); }
      @Override public Optional<IpSession> findLatestByUuid(UUID uuid) { return Optional.empty(); }
      @Override public void deleteByUuid(UUID uuid) {
        throw new IllegalStateException("revocation unavailable");
      }
    };
    AuthService auth = new AuthService(
        fixture.users, new LocalEventBus(), fixture.sessions, failingStore, fixture.devices);
    try {
      assertThrows(IllegalStateException.class, () -> auth.changePassword(
          fixture.playerId, fixture.oldPassword, "NewPassword_456"));

      String storedHash = fixture.users.findFullByUuid(fixture.playerId).orElseThrow().passwordHash();
      assertTrue(PasswordHasher.verify(fixture.oldPassword, storedHash));
      assertFalse(PasswordHasher.verify("NewPassword_456", storedHash));
    } finally {
      fixture.sessions.shutdown();
    }
  }

  @Test
  void revocationRollbackDoesNotOverwriteAConcurrentPasswordChange() throws Exception {
    Fixture fixture = this.fixture();
    String concurrentPassword = "ConcurrentPassword_789";
    JdbcUserRepository racingUsers = new JdbcUserRepository(fixture.source) {
      @Override
      public void markPasswordMigrated(UUID uuid, String passwordHash, Instant migratedAt) {
        super.markPasswordMigrated(uuid, passwordHash, migratedAt);
        super.markPasswordMigrated(uuid, PasswordHasher.hash(concurrentPassword), Instant.now());
      }
    };
    AuthService auth = new AuthService(
        racingUsers, new LocalEventBus(), fixture.sessions, failingIpSessionStore(), fixture.devices);
    try {
      assertThrows(IllegalStateException.class, () -> auth.changePassword(
          fixture.playerId, fixture.oldPassword, "NewPassword_456"));

      String storedHash = fixture.users.findFullByUuid(fixture.playerId).orElseThrow().passwordHash();
      assertTrue(PasswordHasher.verify(concurrentPassword, storedHash));
      assertFalse(PasswordHasher.verify(fixture.oldPassword, storedHash));
    } finally {
      fixture.sessions.shutdown();
    }
  }

  @Test
  void administrativeResetUsesTheSameRevocationBoundary() throws Exception {
    Fixture fixture = this.fixture();
    try {
      AuthResult result = fixture.auth.resetPassword("Alex", "ResetPassword_789");

      assertTrue(result.success());
      assertFalse(fixture.sessions.get(fixture.playerId).isPresent());
      assertFalse(fixture.ipSessions.hasRecentSessionMinutes(
          fixture.playerId, fixture.address.getHostAddress(), fixture.deviceId, 30));
      assertTrue(fixture.devices.listActive(fixture.playerId, Instant.now()).isEmpty());
      String storedHash = fixture.users.findFullByUuid(fixture.playerId).orElseThrow().passwordHash();
      assertTrue(PasswordHasher.verify("ResetPassword_789", storedHash));
      assertFalse(PasswordHasher.verify(fixture.oldPassword, storedHash));
    } finally {
      fixture.sessions.shutdown();
    }
  }

  @Test
  void administrativeResetCompletesPendingLocalMigration() throws Exception {
    Fixture fixture = this.fixture();
    fixture.users.updateMigrationState(fixture.playerId, "pending");
    try {
      AuthResult result = fixture.auth.resetPassword("Alex", "ResetPassword_789");

      assertTrue(result.success());
      StarxUser user = fixture.users.findFullByUuid(fixture.playerId).orElseThrow();
      assertEquals("completed", user.migrationState());
      assertNotNull(user.passwordMigratedAt());
    } finally {
      fixture.sessions.shutdown();
    }
  }

  @Test
  void passwordChangeCompletesPendingLocalMigration() throws Exception {
    Fixture fixture = this.fixture();
    fixture.users.updateMigrationState(fixture.playerId, "pending");
    try {
      AuthResult result = fixture.auth.changePassword(
          fixture.playerId, fixture.oldPassword, "ChangedPassword_789");

      assertTrue(result.success());
      StarxUser user = fixture.users.findFullByUuid(fixture.playerId).orElseThrow();
      assertEquals("completed", user.migrationState());
      assertNotNull(user.passwordMigratedAt());
      assertTrue(PasswordHasher.verify("ChangedPassword_789", user.passwordHash()));
    } finally {
      fixture.sessions.shutdown();
    }
  }

  @Test
  void enablingTotpRevokesOldBypassWithoutDroppingTheCurrentSession() throws Exception {
    Fixture fixture = this.fixture();
    try {
      AuthResult result = fixture.auth.enableTotp(fixture.playerId, fixture.oldPassword);

      assertTrue(result.success());
      assertTrue(fixture.sessions.get(fixture.playerId).isPresent());
      assertFalse(fixture.ipSessions.hasRecentSessionMinutes(
          fixture.playerId, fixture.address.getHostAddress(), fixture.deviceId, 30));
      assertTrue(fixture.devices.listActive(fixture.playerId, Instant.now()).isEmpty());
      assertTrue(fixture.users.findFullByUuid(fixture.playerId).orElseThrow()
          .trustedDevices().isEmpty());
    } finally {
      fixture.sessions.shutdown();
    }
  }

  @Test
  void disablingTotpRevokesOldBypassWithoutDroppingTheCurrentSession() throws Exception {
    Fixture fixture = this.fixture();
    fixture.users.enableTotp(
        fixture.playerId, TotpGenerator.generateSecret(), "[]");
    try {
      AuthResult result = fixture.auth.disableTotp(fixture.playerId, fixture.oldPassword);

      assertTrue(result.success());
      assertTrue(fixture.sessions.get(fixture.playerId).isPresent());
      assertFalse(fixture.ipSessions.hasRecentSessionMinutes(
          fixture.playerId, fixture.address.getHostAddress(), fixture.deviceId, 30));
      assertTrue(fixture.devices.listActive(fixture.playerId, Instant.now()).isEmpty());
      assertTrue(fixture.users.findFullByUuid(fixture.playerId).orElseThrow()
          .trustedDevices().isEmpty());
    } finally {
      fixture.sessions.shutdown();
    }
  }

  @Test
  void disablingBlankTotpIsRejectedAsNotEnabled() throws Exception {
    Fixture fixture = this.fixture();
    assertTrue(fixture.users.enableTotp(fixture.playerId, "", "[]"));
    try {
      AuthResult result = fixture.auth.disableTotp(fixture.playerId, fixture.oldPassword);

      assertFalse(result.success());
      assertEquals("", fixture.users.findFullByUuid(fixture.playerId).orElseThrow().totpSecret());
    } finally {
      fixture.sessions.shutdown();
    }
  }

  @Test
  void enablingTotpKeepsOnlyTheCurrentMinecraftIdentitySession() throws Exception {
    Fixture fixture = this.fixture();
    UUID currentConnection = UUID.randomUUID();
    AuthLease currentLease = AuthLease.create();
    AuthLease legacyLease = AuthLease.create();
    fixture.auth.bindMinecraftIdentityResolver(
        ignored -> Set.of(fixture.playerId, currentConnection));
    fixture.sessions.open(
        currentConnection, "Alex", fixture.address, fixture.deviceId, currentLease);
    fixture.sessions.open(
        fixture.playerId, "Alex", fixture.address, fixture.deviceId, legacyLease);

    try {
      AuthResult result = fixture.auth.enableTotp(currentConnection, fixture.oldPassword);

      assertTrue(result.success());
      assertTrue(fixture.sessions.get(currentConnection, currentLease).isPresent());
      assertTrue(fixture.sessions.get(fixture.playerId, legacyLease).isEmpty());
    } finally {
      fixture.sessions.shutdown();
    }
  }

  @Test
  void failedTotpEnablePreservesExistingTrust() throws Exception {
    Fixture fixture = this.fixture();
    StarxUser account = fixture.users.findFullByUuid(fixture.playerId).orElseThrow();
    AuthService auth = new AuthService(
        new FailingTotpUserRepository(account, false, false), new LocalEventBus(), fixture.sessions,
        fixture.ipSessions, fixture.devices);
    try {
      AuthResult result = auth.enableTotp(fixture.playerId, fixture.oldPassword);

      assertFalse(result.success());
      assertTrue(fixture.sessions.get(fixture.playerId).isPresent());
      assertTrue(fixture.ipSessions.hasRecentSessionMinutes(
          fixture.playerId, fixture.address.getHostAddress(), fixture.deviceId, 30));
      assertFalse(fixture.devices.listActive(fixture.playerId, Instant.now()).isEmpty());
    } finally {
      fixture.sessions.shutdown();
    }
  }

  @Test
  void failedTotpDisablePreservesExistingTrust() throws Exception {
    Fixture fixture = this.fixture();
    assertTrue(fixture.users.enableTotp(
        fixture.playerId, TotpGenerator.generateSecret(), "[]"));
    StarxUser account = fixture.users.findFullByUuid(fixture.playerId).orElseThrow();
    AuthService auth = new AuthService(
        new FailingTotpUserRepository(account, true, false), new LocalEventBus(), fixture.sessions,
        fixture.ipSessions, fixture.devices);
    try {
      AuthResult result = auth.disableTotp(fixture.playerId, fixture.oldPassword);

      assertFalse(result.success());
      assertTrue(fixture.sessions.get(fixture.playerId).isPresent());
      assertTrue(fixture.ipSessions.hasRecentSessionMinutes(
          fixture.playerId, fixture.address.getHostAddress(), fixture.deviceId, 30));
      assertFalse(fixture.devices.listActive(fixture.playerId, Instant.now()).isEmpty());
    } finally {
      fixture.sessions.shutdown();
    }
  }

  @Test
  void revocationFailureDoesNotEnableTotp() throws Exception {
    Fixture fixture = this.fixture();
    AuthService auth = new AuthService(
        fixture.users, new LocalEventBus(), fixture.sessions,
        failingIpSessionStore(), fixture.devices);
    try {
      assertThrows(IllegalStateException.class,
          () -> auth.enableTotp(fixture.playerId, fixture.oldPassword));

      assertTrue(fixture.users.findFullByUuid(fixture.playerId).orElseThrow()
          .totpSecret() == null);
    } finally {
      fixture.sessions.shutdown();
    }
  }

  @Test
  void totpEnableRollbackDoesNotDisableAConcurrentEnrollment() throws Exception {
    Fixture fixture = this.fixture();
    String concurrentSecret = TotpGenerator.generateSecret();
    AuthService auth = new AuthService(
        new RacingTotpUserRepository(fixture.source, concurrentSecret, false),
        new LocalEventBus(), fixture.sessions, failingIpSessionStore(), fixture.devices);
    try {
      assertThrows(IllegalStateException.class,
          () -> auth.enableTotp(fixture.playerId, fixture.oldPassword));

      assertEquals(concurrentSecret,
          fixture.users.findFullByUuid(fixture.playerId).orElseThrow().totpSecret());
    } finally {
      fixture.sessions.shutdown();
    }
  }

  @Test
  void revocationFailureDoesNotDisableTotp() throws Exception {
    Fixture fixture = this.fixture();
    String secret = TotpGenerator.generateSecret();
    assertTrue(fixture.users.enableTotp(fixture.playerId, secret, "[]"));
    AuthService auth = new AuthService(
        fixture.users, new LocalEventBus(), fixture.sessions,
        failingIpSessionStore(), fixture.devices);
    try {
      assertThrows(IllegalStateException.class,
          () -> auth.disableTotp(fixture.playerId, fixture.oldPassword));

      assertTrue(secret.equals(fixture.users.findFullByUuid(fixture.playerId).orElseThrow()
          .totpSecret()));
    } finally {
      fixture.sessions.shutdown();
    }
  }

  @Test
  void totpDisableRollbackDoesNotOverwriteAConcurrentEnrollment() throws Exception {
    Fixture fixture = this.fixture();
    String originalSecret = TotpGenerator.generateSecret();
    String concurrentSecret = TotpGenerator.generateSecret();
    assertTrue(fixture.users.enableTotp(fixture.playerId, originalSecret, "[]"));
    AuthService auth = new AuthService(
        new RacingTotpUserRepository(fixture.source, concurrentSecret, true),
        new LocalEventBus(), fixture.sessions, failingIpSessionStore(), fixture.devices);
    try {
      assertThrows(IllegalStateException.class,
          () -> auth.disableTotp(fixture.playerId, fixture.oldPassword));

      assertEquals(concurrentSecret,
          fixture.users.findFullByUuid(fixture.playerId).orElseThrow().totpSecret());
    } finally {
      fixture.sessions.shutdown();
    }
  }

  private static IpSessionStore failingIpSessionStore() {
    return new IpSessionStore() {
      @Override public void save(IpSession session) { }
      @Override public Optional<IpSession> findByUuidAndIp(UUID uuid, String ip) {
        return Optional.empty();
      }
      @Override public boolean hasRecentSession(UUID uuid, String ip, int hours) { return false; }
      @Override public List<IpSession> findRecentSessions(UUID uuid, int hours) { return List.of(); }
      @Override public Optional<IpSession> findLatestByUuid(UUID uuid) { return Optional.empty(); }
      @Override public void deleteByUuid(UUID uuid) {
        throw new IllegalStateException("revocation unavailable");
      }
    };
  }

  private static final class FailingPasswordUserRepository extends JdbcUserRepository {
    private final StarxUser account;

    private FailingPasswordUserRepository(StarxUser account) {
      super(null);
      this.account = account;
    }

    @Override
    public Optional<StarxUser> findFullByUuid(UUID uuid) {
      return this.account.uuid().equals(uuid) ? Optional.of(this.account) : Optional.empty();
    }

    @Override
    public Optional<StarxUser> findFullByUsername(String username) {
      return this.account.username().equalsIgnoreCase(username)
          ? Optional.of(this.account) : Optional.empty();
    }

    @Override
    public void markPasswordMigrated(UUID uuid, String passwordHash, Instant migratedAt) {
      throw new IllegalStateException("database unavailable");
    }
  }

  private static final class FailingTotpUserRepository extends JdbcUserRepository {
    private final StarxUser account;
    private final boolean totpAlreadyEnabled;
    private final boolean updateResult;

    private FailingTotpUserRepository(
        StarxUser account, boolean totpAlreadyEnabled, boolean updateResult) {
      super(null);
      this.account = account;
      this.totpAlreadyEnabled = totpAlreadyEnabled;
      this.updateResult = updateResult;
    }

    @Override
    public Optional<StarxUser> findFullByUuid(UUID uuid) {
      return this.account.uuid().equals(uuid) ? Optional.of(this.account) : Optional.empty();
    }

    @Override
    public boolean enableTotp(UUID uuid, String secret, String recoveryCodes) {
      return !this.totpAlreadyEnabled && this.updateResult;
    }

    @Override
    public boolean disableTotp(UUID uuid) {
      return this.totpAlreadyEnabled && this.updateResult;
    }

    @Override
    public void updateTrustedDevices(UUID uuid, List<String> trustedDevices) {
      // The fixture controls trust stores independently from the user row.
    }
  }

  private static final class RacingTotpUserRepository extends JdbcUserRepository {
    private final String concurrentSecret;
    private final boolean disable;

    private RacingTotpUserRepository(
        SQLiteDataSource source, String concurrentSecret, boolean disable) {
      super(source);
      this.concurrentSecret = concurrentSecret;
      this.disable = disable;
    }

    @Override
    public boolean enableTotp(UUID uuid, String secret, String recoveryCodes) {
      boolean updated = super.enableTotp(uuid, secret, recoveryCodes);
      if (updated && !this.disable) super.updateTotpSecret(uuid, this.concurrentSecret);
      return updated;
    }

    @Override
    public boolean disableTotp(UUID uuid) {
      boolean updated = super.disableTotp(uuid);
      if (updated && this.disable) super.updateTotpSecret(uuid, this.concurrentSecret);
      return updated;
    }
  }

  private Fixture fixture() throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + this.tempDir.resolve(UUID.randomUUID() + ".db"));
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("""
          CREATE TABLE starx_users (
            uuid VARCHAR(36) PRIMARY KEY, username VARCHAR(255), email VARCHAR(255),
            password_hash VARCHAR(255), totp_secret VARCHAR(255), premium BOOLEAN,
            created_at TIMESTAMP, last_login_at TIMESTAMP, external_user_id VARCHAR(255),
            trusted_devices TEXT, recovery_codes VARCHAR(512), source_system VARCHAR(50),
            migration_state VARCHAR(20), password_migrated_at TIMESTAMP, last_login_ip VARCHAR(255),
            last_login_isp VARCHAR(255), last_login_location VARCHAR(255), total_playtime BIGINT,
            last_logout_at TIMESTAMP, welcome_message_shown BOOLEAN)
          """);
      sql.execute(JdbcTrustedDeviceRepository.CREATE_TABLE_SQL);
    }
    UUID playerId = UUID.randomUUID();
    String oldPassword = "ValidPassword_123";
    String deviceId = "device-a";
    InetAddress address = InetAddress.getByName("203.0.113.42");
    JdbcUserRepository users = new JdbcUserRepository(source);
    users.create(new StarxUser(playerId, "Alex", null, PasswordHasher.hash(oldPassword), null,
        false, Instant.now(), null, null, List.of(deviceId), null, "local", "completed",
        null, null, null, null, 0L, null, false));
    JdbcTrustedDeviceRepository devices = new JdbcTrustedDeviceRepository(source);
    devices.observe(playerId, deviceId, "203.0.113.0/24", "Client",
        Instant.now().plus(Duration.ofDays(30)), Instant.now());
    InMemoryIpSessionStore ipSessions = new InMemoryIpSessionStore();
    ipSessions.save(IpSession.create(playerId, address.getHostAddress(), "local", deviceId));
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthLease lease = AuthLease.create();
    sessions.open(playerId, "Alex", address, deviceId, lease);
    AuthService auth = new AuthService(
        users, new LocalEventBus(), sessions, ipSessions, devices);
    return new Fixture(source, auth, users, devices, ipSessions, sessions, playerId, oldPassword,
        deviceId, address);
  }

  private record Fixture(
      SQLiteDataSource source,
      AuthService auth,
      JdbcUserRepository users,
      JdbcTrustedDeviceRepository devices,
      InMemoryIpSessionStore ipSessions,
      SessionManager sessions,
      UUID playerId,
      String oldPassword,
      String deviceId,
      InetAddress address) {
  }
}
