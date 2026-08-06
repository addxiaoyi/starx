package io.github.addxiaoyi.starx.common.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class AuthServiceWebApprovalTest {
  @TempDir Path tempDir;

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
  void highRiskLoginRequestsWebApprovalWithoutEnablingTotp() throws Exception {
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
      assertEquals(AuthSession.State.WEB_APPROVAL_PENDING, result.state());
      assertEquals(lease, requestedLease.get());
      assertTrue(result.webApprovalUrl().contains("action=approve_login"));
      assertTrue(users.findFullByUuid(playerId).orElseThrow().totpSecret() == null);
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void registeredPlayerCanStartWebsiteLoginWithoutEnteringGamePassword() throws Exception {
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

      assertTrue(result.success());
      assertEquals(AuthSession.State.WEB_APPROVAL_PENDING, result.state());
      assertTrue(result.webApprovalUrl().contains("action=approve_login"));
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
}
