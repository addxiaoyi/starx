package io.github.addxiaoyi.starx.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.addxiaoyi.starx.common.auth.uniauth.UniAuthBridge;
import io.github.addxiaoyi.starx.common.auth.uniauth.UniAuthClient;
import io.github.addxiaoyi.starx.common.auth.uniauth.UniAuthConfig;
import io.github.addxiaoyi.starx.common.crypto.PasswordHasher;
import io.github.addxiaoyi.starx.common.crypto.TotpGenerator;
import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import io.github.addxiaoyi.starx.common.event.LocalEventBus;
import io.github.addxiaoyi.starx.common.model.StarxUser;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class AuthServiceUniAuthCompatibilityTest {
  private static final String PASSWORD = "LocalPassword_123";

  @Test
  void localPasswordWinsWhenImportedAccountStillHasPendingMigration() {
    UUID accountUuid = offlineUuid("PendingUser");
    InMemoryUsers users = new InMemoryUsers(user(accountUuid, "PendingUser", "pending"));
    UniAuthConfig config = config();
    UniAuthBridge bridge = new UniAuthBridge(config, new UniAuthClient(config), users);

    UniAuthBridge.BridgeResult result = bridge.authenticate(
        accountUuid, "PendingUser", PASSWORD).join();

    assertTrue(result.success());
    assertEquals("Login successful (local)", result.message());
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"pending", "legacy", "unknown"})
  void localPasswordWinsForAnyMigrationStateWhenHashIsPresent(String migrationState) {
    UUID accountUuid = offlineUuid("StateUser");
    InMemoryUsers users = new InMemoryUsers(user(accountUuid, "StateUser", migrationState));
    UniAuthConfig config = config();
    UniAuthBridge bridge = new UniAuthBridge(config, new UniAuthClient(config), users);

    UniAuthBridge.BridgeResult result = bridge.authenticate(
        accountUuid, "StateUser", PASSWORD).join();

    assertTrue(result.success());
    assertEquals("Login successful (local)", result.message());
  }

  @Test
  void localLoginUsesTheCurrentConnectionUuidWhenStoredIdentityDiffers() throws Exception {
    UUID accountUuid = offlineUuid("PremiumUser");
    UUID connectionUuid = UUID.randomUUID();
    InMemoryUsers users = new InMemoryUsers(user(accountUuid, "PremiumUser", "completed"));
    UniAuthConfig config = config();
    UniAuthBridge bridge = new UniAuthBridge(config, new UniAuthClient(config), users);
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthLease lease = AuthLease.create();
    InetAddress address = InetAddress.getLoopbackAddress();
    try {
      assertTrue(sessions.open(connectionUuid, "PremiumUser", address, "device-a", lease) != null);
      AuthService auth = new AuthService(
          users,
          new LocalEventBus(),
          sessions,
          config,
          bridge,
          new InMemoryIpSessionStore());

      AuthResult result = auth.login(
          lease,
          connectionUuid,
          "PremiumUser",
          PASSWORD,
          null,
          address,
          "device-a");

      assertTrue(result.success());
      assertTrue(sessions.isState(
          connectionUuid, lease, AuthSession.State.AUTHENTICATED));
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void uniauthPasswordStillRequiresTotpForAHighRiskConnection() throws Exception {
    UUID accountUuid = offlineUuid("RemoteUser");
    UUID connectionUuid = UUID.randomUUID();
    String secret = TotpGenerator.generateSecret();
    InMemoryUsers users = new InMemoryUsers(
        userWithoutPassword(accountUuid, "RemoteUser", "completed", secret));
    HttpServer server = this.startSuccessfulUniAuthServer();
    UniAuthConfig config = new UniAuthConfig(
        true,
        "http://127.0.0.1:" + server.getAddress().getPort() + "/",
        "test-key",
        1000,
        true);
    UniAuthBridge bridge = new UniAuthBridge(config, new UniAuthClient(config), users);
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthLease lease = AuthLease.create();
    InetAddress address = InetAddress.getByName("203.0.113.9");
    try {
      assertTrue(sessions.open(
          connectionUuid, "RemoteUser", address, "new-device", lease) != null);
      AuthService auth = new AuthService(
          users,
          new LocalEventBus(),
          sessions,
          config,
          bridge,
          new InMemoryIpSessionStore());

      AuthResult passwordResult = auth.login(
          lease,
          connectionUuid,
          "RemoteUser",
          PASSWORD,
          null,
          address,
          "new-device");

      assertTrue(passwordResult.success());
      assertEquals(AuthSession.State.AUTHENTICATING, passwordResult.state());
      AuthResult totpResult = auth.verifyTotp(
          lease,
          connectionUuid,
          TotpGenerator.generate(secret, Instant.now()));
      assertTrue(totpResult.success());
      assertEquals(AuthSession.State.AUTHENTICATED, totpResult.state());
    } finally {
      server.stop(0);
      sessions.shutdown();
    }
  }

  @Test
  void loginUsesTheSessionUsernameInsteadOfCallerSuppliedUsername() {
    UUID connectionUuid = UUID.randomUUID();
    StarxUser sessionUser = user(
        connectionUuid,
        "SessionUser",
        "completed",
        PasswordHasher.hash("SessionPassword_123"));
    StarxUser suppliedUser = user(
        UUID.randomUUID(),
        "SuppliedUser",
        "completed",
        PasswordHasher.hash("SuppliedPassword_123"));
    MultipleUsers users = new MultipleUsers(List.of(sessionUser, suppliedUser));
    UniAuthConfig config = config();
    UniAuthBridge bridge = new UniAuthBridge(config, new UniAuthClient(config), users);
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthLease lease = AuthLease.create();
    InetAddress address = InetAddress.getLoopbackAddress();
    try {
      assertTrue(sessions.open(
          connectionUuid, "SessionUser", address, "device-a", lease) != null);

      AuthService auth = new AuthService(
          users,
          new LocalEventBus(),
          sessions,
          config,
          bridge,
          new InMemoryIpSessionStore());

      AuthResult result = auth.login(
          lease,
          connectionUuid,
          "SuppliedUser",
          "SuppliedPassword_123",
          null,
          address,
          "device-a");

      assertFalse(result.success());
      assertEquals(
          AuthSession.State.GUEST,
          sessions.get(connectionUuid, lease).orElseThrow().state());
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void uniauthBridgePrefersTheConnectionUuidBeforeUsernameFallback() {
    UUID connectionUuid = UUID.randomUUID();
    StarxUser connectionUser = user(
        connectionUuid,
        "CurrentName",
        "completed",
        PasswordHasher.hash("CurrentPassword_123"));
    StarxUser sameNameUser = user(
        UUID.randomUUID(),
        "SessionName",
        "completed",
        PasswordHasher.hash("SessionPassword_123"));
    MultipleUsers users = new MultipleUsers(List.of(connectionUser, sameNameUser));
    UniAuthConfig config = config();
    UniAuthBridge bridge = new UniAuthBridge(config, new UniAuthClient(config), users);

    UniAuthBridge.BridgeResult result = bridge.authenticate(
        connectionUuid, "SessionName", "SessionPassword_123").join();

    assertFalse(result.success());
    assertEquals("Invalid password", result.message());
  }

  private HttpServer startSuccessfulUniAuthServer() throws Exception {
    var keyPairGenerator = KeyPairGenerator.getInstance("RSA");
    keyPairGenerator.initialize(2048);
    String publicKey = Base64.getEncoder().encodeToString(
        keyPairGenerator.generateKeyPair().getPublic().getEncoded());
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/publickey", exchange -> {
      byte[] body = publicKey.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      try (var output = exchange.getResponseBody()) {
        output.write(body);
      }
    });
    server.createContext("/login", exchange -> {
      byte[] body = "{\"code\":200,\"data\":{}}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      try (var output = exchange.getResponseBody()) {
        output.write(body);
      }
    });
    server.start();
    return server;
  }

  private static UniAuthConfig config() {
    return new UniAuthConfig(
        true,
        "http://127.0.0.1:1/",
        "test-key",
        100,
        true,
        UniAuthConfig.ProfileSyncConfig.defaults());
  }

  private static UUID offlineUuid(String username) {
    return UUID.nameUUIDFromBytes(
        ("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
  }

  private static StarxUser user(UUID uuid, String username, String migrationState) {
    return user(uuid, username, migrationState, PasswordHasher.hash(PASSWORD));
  }

  private static StarxUser userWithoutPassword(
      UUID uuid, String username, String migrationState, String totpSecret) {
    return user(uuid, username, migrationState, null, totpSecret);
  }

  private static StarxUser user(
      UUID uuid, String username, String migrationState, String passwordHash) {
    return user(uuid, username, migrationState, passwordHash, null);
  }

  private static StarxUser user(
      UUID uuid,
      String username,
      String migrationState,
      String passwordHash,
      String totpSecret) {
    return new StarxUser(
        uuid,
        username,
        null,
        passwordHash,
        totpSecret,
        false,
        Instant.now(),
        null,
        null,
        List.of(),
        null,
        "uniauth",
        migrationState,
        null,
        null,
        null,
        null,
        0L,
        null,
        false);
  }

  private static final class InMemoryUsers extends JdbcUserRepository {
    private final StarxUser user;

    private InMemoryUsers(StarxUser user) {
      super(null);
      this.user = user;
    }

    @Override
    public Optional<StarxUser> findFullByUsername(String username) {
      return this.user.username().equalsIgnoreCase(username)
          ? Optional.of(this.user) : Optional.empty();
    }

    @Override
    public Optional<StarxUser> findFullByUuid(UUID uuid) {
      return this.user.uuid().equals(uuid)
          ? Optional.of(this.user) : Optional.empty();
    }

    @Override
    public void updateLastLogin(UUID uuid, Instant lastLogin) {
      // The test only needs to verify the authentication/session identity boundary.
    }

    @Override
    public void markPasswordMigrated(UUID uuid, String passwordHash, Instant migratedAt) {
      // The remote-login test isolates the authentication decision from persistence details.
    }

    @Override
    public boolean hasTrustedWebsiteBinding(UUID uuid, String username) {
      return false;
    }
  }

  private static final class MultipleUsers extends JdbcUserRepository {
    private final List<StarxUser> users;

    private MultipleUsers(List<StarxUser> users) {
      super(null);
      this.users = users;
    }

    @Override
    public Optional<StarxUser> findFullByUsername(String username) {
      return this.users.stream()
          .filter(user -> user.username().equalsIgnoreCase(username))
          .findFirst();
    }

    @Override
    public Optional<StarxUser> findFullByUuid(UUID uuid) {
      return this.users.stream()
          .filter(user -> user.uuid().equals(uuid))
          .findFirst();
    }

    @Override
    public void updateLastLogin(UUID uuid, Instant lastLogin) {
      // The fixture isolates the session identity boundary.
    }

    @Override
    public void markPasswordMigrated(UUID uuid, String passwordHash, Instant migratedAt) {
      // The fixture does not exercise persistence.
    }

    @Override
    public boolean hasTrustedWebsiteBinding(UUID uuid, String username) {
      return false;
    }
  }
}
