package io.github.addxiaoyi.starx.common.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.common.crypto.PasswordHasher;
import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import io.github.addxiaoyi.starx.common.event.LocalEventBus;
import io.github.addxiaoyi.starx.common.model.StarxUser;
import java.net.InetAddress;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class AuthServiceWebApprovalTest {
  @TempDir Path tempDir;

  @Test
  void cancellationRemovesAPendingWebApprovalSession() {
    UUID playerId = UUID.randomUUID();
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthService auth = new AuthService(new EmptyUsers(), new LocalEventBus(), sessions);
    AuthLease lease = AuthLease.create();
    assertTrue(sessions.open(
        playerId, "Alex", InetAddress.getLoopbackAddress(), lease) != null);
    assertTrue(sessions.transition(
        playerId, lease, AuthSession.State.GUEST, AuthSession.State.WEB_APPROVAL_PENDING));

    try {
      assertTrue(auth.cancelAuthentication(playerId, lease));
      assertTrue(sessions.get(playerId, lease).isEmpty());
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void cancellationRemovesAnApprovedSessionForTheSameLease() {
    UUID playerId = UUID.randomUUID();
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthService auth = new AuthService(new EmptyUsers(), new LocalEventBus(), sessions);
    AuthLease lease = AuthLease.create();
    assertTrue(sessions.open(
        playerId, "Alex", InetAddress.getLoopbackAddress(), lease) != null);
    assertTrue(sessions.transition(
        playerId, lease, AuthSession.State.GUEST, AuthSession.State.WEB_APPROVAL_PENDING));
    assertTrue(sessions.transition(
        playerId, lease,
        AuthSession.State.WEB_APPROVAL_PENDING, AuthSession.State.AUTHENTICATED));

    try {
      assertTrue(auth.cancelAuthentication(playerId, lease));
      assertTrue(sessions.get(playerId, lease).isEmpty());
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void approvesOnlyTheCurrentAuthenticatingLease() throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + this.tempDir.resolve("web-approval.db"));
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
      sql.execute("""
          CREATE TABLE starx_website_bindings (
            player_uuid VARCHAR(36) NOT NULL,
            username VARCHAR(255) NOT NULL,
            verified BOOLEAN NOT NULL DEFAULT FALSE,
            PRIMARY KEY (player_uuid, username))
          """);
    }
    UUID playerId = UUID.randomUUID();
    JdbcUserRepository users = new JdbcUserRepository(source);
    users.create(new StarxUser(
        playerId, "Alex", null, PasswordHasher.hash("ValidPassword_123"), null,
        false, Instant.now(), null, null, List.of(), null, "local", "completed",
        null, null, null, null, 0L, null, false));
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
    AuthLease current = AuthLease.create();
    AuthLease stale = AuthLease.create();
    sessions.open(playerId, "Alex", InetAddress.getLoopbackAddress(), "device-a", current);
    assertTrue(sessions.transition(
        playerId, current, AuthSession.State.GUEST, AuthSession.State.WEB_APPROVAL_PENDING));

    try {
      assertFalse(auth.approveWebLogin(stale, playerId).success());
      assertTrue(sessions.isState(playerId, current, AuthSession.State.WEB_APPROVAL_PENDING));

      assertTrue(auth.approveWebLogin(current, playerId).success());
      assertTrue(sessions.isState(playerId, current, AuthSession.State.AUTHENTICATED));
      assertTrue(auth.isAuthenticated(current, playerId));
      assertTrue(auth.approveWebLogin(current, playerId).success());
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void webApprovalResolvesTheStoredAccountFromTheCurrentConnectionUsername() {
    UUID accountUuid = offlineUuid("Alex");
    UUID connectionUuid = UUID.randomUUID();
    AliasUsers users = new AliasUsers(new StarxUser(
        accountUuid, "Alex", null, PasswordHasher.hash("ValidPassword_123"), null,
        false, Instant.now(), null, null, List.of(), null, "local", "completed",
        null, null, null, null, 0L, null, false));
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
    auth.bindMinecraftIdentityResolver(ignored -> Set.of(connectionUuid, accountUuid));
    AuthLease lease = AuthLease.create();
    assertTrue(sessions.open(
        connectionUuid, "Alex", InetAddress.getLoopbackAddress(), lease) != null);
    assertTrue(sessions.transition(
        connectionUuid, lease, AuthSession.State.GUEST, AuthSession.State.WEB_APPROVAL_PENDING));

    try {
      AuthResult result = auth.approveWebLogin(lease, connectionUuid);

      assertTrue(result.success());
      assertTrue(sessions.isState(
          connectionUuid, lease, AuthSession.State.AUTHENTICATED));
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void webApprovalUsesTheLiveSessionNameForAMigratedAccount() throws Exception {
    UUID legacyUuid = offlineUuid("LegacyName");
    UUID currentUuid = UUID.randomUUID();
    BoundAliasUsers users = new BoundAliasUsers(new StarxUser(
        legacyUuid, "LegacyName", null, PasswordHasher.hash("ValidPassword_123"), null,
        false, Instant.now(), null, null, List.of(), null, "local", "completed",
        null, null, null, null, 0L, null, false), "CurrentName");
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
    AtomicReference<String> requestedName = new AtomicReference<>();
    auth.bindMinecraftIdentityResolver(ignored -> Set.of(currentUuid, legacyUuid));
    auth.bindWebLoginApprovalGateway((uuid, username, activeLease) -> {
      requestedName.set(username);
      return "https://star-web.top/minecraft/approve?token=test&action=approve_login";
    });
    AuthLease lease = AuthLease.create();
    assertTrue(sessions.open(
        currentUuid, "CurrentName", InetAddress.getLoopbackAddress(), lease) != null);

    try {
      AuthResult result = auth.requestWebLoginApproval(lease, currentUuid, "CurrentName");

      assertTrue(result.success());
      assertEquals("CurrentName", requestedName.get());
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void webApprovalChecksTheWebsiteBindingAgainstTheLiveSessionName() {
    UUID playerId = UUID.randomUUID();
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    BoundAliasUsers users = new BoundAliasUsers(new StarxUser(
        playerId, "OldName", null, PasswordHasher.hash("ValidPassword_123"), null,
        false, Instant.now(), null, null, List.of(), null, "local", "completed",
        null, null, null, null, 0L, null, false), "CurrentName");
    AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
    auth.bindWebLoginApprovalGateway((uuid, username, lease) ->
        "https://star-web.top/minecraft/approve?token=test&action=approve_login");
    AuthLease lease = AuthLease.create();
    assertTrue(sessions.open(
        playerId, "CurrentName", InetAddress.getLoopbackAddress(), lease) != null);

    try {
      AuthResult result = auth.requestWebLoginApproval(lease, playerId, "CurrentName");

      assertTrue(result.success());
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void passwordRiskDecisionUsesTheLiveSessionNameForWebsiteBinding() throws Exception {
    UUID playerId = UUID.randomUUID();
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    BoundAliasUsers users = new BoundAliasUsers(new StarxUser(
        playerId, "OldName", null, PasswordHasher.hash("ValidPassword_123"), null,
        false, Instant.now(), null, null, List.of(), null, "local", "completed",
        null, null, null, null, 0L, null, false), "CurrentName");
    AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
    auth.bindWebLoginApprovalGateway((uuid, username, lease) ->
        "https://star-web.top/minecraft/approve?token=test&action=approve_login");
    AuthLease lease = AuthLease.create();
    InetAddress address = InetAddress.getByName("203.0.113.42");
    assertTrue(sessions.open(playerId, "CurrentName", address, "new-device", lease) != null);

    try {
      AuthResult result = auth.login(
          lease, playerId, "CurrentName", "ValidPassword_123", null, address, "new-device");

      assertTrue(result.success());
      assertEquals(AuthSession.State.WEB_APPROVAL_PENDING, result.state());
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void webApprovalChecksTheLeaseBeforeLookingUpTheAccount() {
    UUID connectionUuid = UUID.randomUUID();
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthService auth = new AuthService(new EmptyUsers(), new LocalEventBus(), sessions);
    AuthLease current = AuthLease.create();
    AuthLease expired = AuthLease.create();
    assertTrue(sessions.open(
        connectionUuid, "MissingUser", InetAddress.getLoopbackAddress(), current) != null);

    try {
      AuthResult result = auth.requestWebLoginApproval(expired, connectionUuid, "MissingUser");

      assertFalse(result.success());
      assertEquals("认证会话已过期，请重新连接。", result.message());
      assertTrue(sessions.isState(connectionUuid, current, AuthSession.State.GUEST));
    } finally {
      sessions.shutdown();
    }
  }

  private static UUID offlineUuid(String username) {
    return UUID.nameUUIDFromBytes(
        ("OfflinePlayer:" + username).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  @Test
  void highRiskUnboundLoginFallsBackToLocalAuthentication() throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + this.tempDir.resolve("risk-web-approval.db"));
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
      sql.execute("""
          CREATE TABLE starx_website_bindings (
            player_uuid VARCHAR(36) NOT NULL,
            username VARCHAR(255) NOT NULL,
            verified BOOLEAN NOT NULL DEFAULT FALSE,
            PRIMARY KEY (player_uuid, username))
          """);
    }
    UUID playerId = UUID.randomUUID();
    String password = "ValidPassword_123";
    JdbcUserRepository users = new JdbcUserRepository(source);
    users.create(new StarxUser(
        playerId, "Alex", null, PasswordHasher.hash(password), null,
        false, Instant.now(), null, null, List.of(), null, "local", "completed",
        null, null, null, null, 0L, null, false));
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
    AtomicReference<AuthLease> requestedLease = new AtomicReference<>();
    auth.bindWebLoginApprovalGateway((uuid, username, lease) -> {
      requestedLease.set(lease);
      return "https://star-web.top/minecraft/approve?token=test&action=approve_login";
    });
    AuthLease lease = AuthLease.create();
    InetAddress address = InetAddress.getByName("203.0.113.9");
    auth.openConnection(lease, playerId, "Alex", address, "new-device");

    try {
      AuthResult result = auth.login(
          lease, playerId, "Alex", password, null, address, "new-device");

      assertTrue(result.success());
      assertEquals(AuthSession.State.AUTHENTICATED, result.state());
      assertNull(requestedLease.get());
      assertNull(result.webApprovalUrl());
      assertTrue(users.findFullByUuid(playerId).orElseThrow().totpSecret() == null);
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void unboundPlayerCannotStartWebsiteLogin() throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + this.tempDir.resolve("direct-web-login.db"));
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
      sql.execute("""
          CREATE TABLE starx_website_bindings (
            player_uuid VARCHAR(36) NOT NULL,
            username VARCHAR(255) NOT NULL,
            verified BOOLEAN NOT NULL DEFAULT FALSE,
            PRIMARY KEY (player_uuid, username))
          """);
    }
    UUID playerId = UUID.randomUUID();
    JdbcUserRepository users = new JdbcUserRepository(source);
    users.create(new StarxUser(
        playerId, "Alex", null, PasswordHasher.hash("ValidPassword_123"), null,
        false, Instant.now(), null, null, List.of(), null, "local", "completed",
        null, null, null, null, 0L, null, false));
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
    AuthLease lease = AuthLease.create();
    InetAddress address = InetAddress.getByName("203.0.113.9");
    auth.bindWebLoginApprovalGateway((uuid, username, activeLease) ->
        "https://star-web.top/minecraft/approve?token=test&action=approve_login");
    assertTrue(auth.openConnection(lease, playerId, "Alex", address, "device-a"));

    try {
      AuthResult result = new AuthCommandHandler(auth).handleCredentials(
          lease, playerId, "Alex", "/login web", address, "device-a");

      assertFalse(result.success());
      assertEquals("请先完成网站绑定，再使用网站登录", result.message());
      assertEquals(AuthSession.State.GUEST, sessions.get(playerId, lease).orElseThrow().state());

      try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
        sql.execute("INSERT INTO starx_website_bindings(player_uuid, username, verified) "
            + "VALUES ('" + playerId + "', 'Alex', TRUE)");
      }
      AuthResult boundResult = new AuthCommandHandler(auth).handleCredentials(
          lease, playerId, "Alex", "/login web", address, "device-a");

      assertTrue(boundResult.success());
      assertEquals(AuthSession.State.WEB_APPROVAL_PENDING, boundResult.state());
      assertTrue(boundResult.webApprovalUrl().contains("action=approve_login"));
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void unregisteredPlayerMustRegisterBeforeStartingWebsiteLogin() throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + this.tempDir.resolve("unregistered-web-login.db"));
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
    }
    UUID playerId = UUID.randomUUID();
    JdbcUserRepository users = new JdbcUserRepository(source);
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
    AtomicBoolean approvalRequested = new AtomicBoolean();
    auth.bindWebLoginApprovalGateway((uuid, username, lease) -> {
      approvalRequested.set(true);
      return "https://star-web.top/minecraft/approve?token=test&action=approve_login";
    });
    AuthLease lease = AuthLease.create();
    InetAddress address = InetAddress.getByName("203.0.113.10");
    assertTrue(auth.openConnection(lease, playerId, "NewPlayer", address, "device-b"));

    try {
      AuthResult result = new AuthCommandHandler(auth).handleCredentials(
          lease, playerId, "NewPlayer", "/login web", address, "device-b");

      assertFalse(result.success());
      assertEquals("请先注册游戏账号，再使用网站登录", result.message());
      assertFalse(approvalRequested.get());
    } finally {
      sessions.shutdown();
    }
  }

  private static final class AliasUsers extends JdbcUserRepository {
    private final StarxUser user;

    private AliasUsers(StarxUser user) {
      super(null);
      this.user = user;
    }

    @Override
    public Optional<StarxUser> findFullByUuid(UUID uuid) {
      return this.user.uuid().equals(uuid) ? Optional.of(this.user) : Optional.empty();
    }

    @Override
    public Optional<StarxUser> findFullByUsername(String username) {
      return this.user.username().equalsIgnoreCase(username)
          ? Optional.of(this.user) : Optional.empty();
    }

    @Override
    public void updateLastLogin(UUID uuid, Instant lastLogin) {
    }
  }

  private static final class BoundAliasUsers extends JdbcUserRepository {
    private final StarxUser user;
    private final String bindingName;

    private BoundAliasUsers(StarxUser user, String bindingName) {
      super(null);
      this.user = user;
      this.bindingName = bindingName;
    }

    @Override
    public Optional<StarxUser> findFullByUuid(UUID uuid) {
      return this.user.uuid().equals(uuid) ? Optional.of(this.user) : Optional.empty();
    }

    @Override
    public Optional<StarxUser> findFullByUsername(String username) {
      return this.user.username().equalsIgnoreCase(username)
          ? Optional.of(this.user) : Optional.empty();
    }

    @Override
    public boolean hasTrustedWebsiteBinding(UUID uuid, String username) {
      return this.user.uuid().equals(uuid) && this.bindingName.equalsIgnoreCase(username);
    }
  }

  private static final class EmptyUsers extends JdbcUserRepository {
    private EmptyUsers() {
      super(null);
    }

    @Override
    public Optional<StarxUser> findFullByUuid(UUID uuid) {
      return Optional.empty();
    }

    @Override
    public Optional<StarxUser> findFullByUsername(String username) {
      return Optional.empty();
    }
  }
}
