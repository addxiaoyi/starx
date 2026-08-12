package io.github.addxiaoyi.starx.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.common.crypto.PasswordHasher;
import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import io.github.addxiaoyi.starx.common.event.LocalEventBus;
import io.github.addxiaoyi.starx.common.model.StarxUser;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class AuthServiceSessionIdentityTest {
  private static final String SESSION_USERNAME = "SessionUser";
  private static final String EXTERNAL_USERNAME = "ExternalUser";
  private static final String SESSION_EXPIRED = "认证会话已过期，请重新连接。";

  @Test
  void loginUsesTheSessionUsernameWhenCallerSuppliesAnotherUsername() {
    UUID connectionUuid = UUID.randomUUID();
    UUID sessionAccountUuid = UUID.randomUUID();
    UUID externalAccountUuid = UUID.randomUUID();
    String credential = "session-credential";
    String credentialHash = PasswordHasher.hash(credential);
    RecordingUsers users = new RecordingUsers(
        user(sessionAccountUuid, SESSION_USERNAME, credentialHash),
        user(externalAccountUuid, EXTERNAL_USERNAME, credentialHash));
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthLease lease = AuthLease.create();
    InetAddress address = InetAddress.getLoopbackAddress();

    try {
      AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
      assertTrue(auth.openConnection(
          lease, connectionUuid, SESSION_USERNAME, address, "device-a"));

      AuthResult result = auth.login(
          lease,
          connectionUuid,
          EXTERNAL_USERNAME,
          credential,
          null,
          address,
          "device-a");

      assertTrue(result.success());
      assertEquals(AuthSession.State.AUTHENTICATED, result.state());
      assertEquals(sessionAccountUuid, users.lastLoginUuid);
      assertEquals(List.of(SESSION_USERNAME), users.usernameLookups);
      assertTrue(sessions.isState(
          connectionUuid, lease, AuthSession.State.AUTHENTICATED));
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void expiredLeaseIsReportedBeforeResolvingAnAccount() {
    AtomicReference<Instant> clock = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), clock::get);
    AuthLease lease = AuthLease.create();
    UUID connectionUuid = UUID.randomUUID();
    RejectingUsers users = new RejectingUsers();

    try {
      sessions.open(connectionUuid, SESSION_USERNAME, InetAddress.getLoopbackAddress(), lease);
      clock.set(clock.get().plus(Duration.ofMinutes(6)));
      AuthService auth = new AuthService(users, new LocalEventBus(), sessions);

      AuthResult result = auth.requestWebLoginApproval(
          lease, connectionUuid, EXTERNAL_USERNAME);

      assertFalse(result.success());
      assertEquals(SESSION_EXPIRED, result.message());
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void nonGuestSessionIsReportedAsExpiredBeforeResolvingAnAccount() {
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthLease lease = AuthLease.create();
    UUID connectionUuid = UUID.randomUUID();
    RejectingUsers users = new RejectingUsers();

    try {
      sessions.open(connectionUuid, SESSION_USERNAME, InetAddress.getLoopbackAddress(), lease);
      assertTrue(sessions.transition(
          connectionUuid,
          lease,
          AuthSession.State.GUEST,
          AuthSession.State.AUTHENTICATING));
      AuthService auth = new AuthService(users, new LocalEventBus(), sessions);

      AuthResult result = auth.requestWebLoginApproval(
          lease, connectionUuid, EXTERNAL_USERNAME);

      assertFalse(result.success());
      assertEquals(SESSION_EXPIRED, result.message());
      assertTrue(sessions.isState(
          connectionUuid, lease, AuthSession.State.AUTHENTICATING));
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void trustedLoginUsesTheSessionUsernameWhenCallerSuppliesAnotherUsername() {
    UUID connectionUuid = UUID.randomUUID();
    UUID sessionAccountUuid = UUID.randomUUID();
    UUID externalAccountUuid = UUID.randomUUID();
    String credentialHash = PasswordHasher.hash("session-credential");
    RecordingUsers users = new RecordingUsers(
        user(sessionAccountUuid, SESSION_USERNAME, credentialHash),
        user(externalAccountUuid, EXTERNAL_USERNAME, credentialHash));
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthLease lease = AuthLease.create();

    try {
      AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
      assertTrue(auth.openConnection(
          lease, connectionUuid, SESSION_USERNAME, InetAddress.getLoopbackAddress()));

      AuthResult result = auth.autoLoginTrusted(
          lease,
          connectionUuid,
          EXTERNAL_USERNAME,
          InetAddress.getLoopbackAddress(),
          "premium",
          true);

      assertTrue(result.success());
      assertEquals(sessionAccountUuid, users.lastLoginUuid);
    } finally {
      sessions.shutdown();
    }
  }

  private static StarxUser user(UUID uuid, String username, String passwordHash) {
    Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
    return new StarxUser(
        uuid,
        username,
        null,
        passwordHash,
        null,
        false,
        createdAt,
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
        false);
  }

  private static class RecordingUsers extends JdbcUserRepository {
    private final Map<UUID, StarxUser> users;
    private final List<String> usernameLookups = new ArrayList<>();
    private UUID lastLoginUuid;

    private RecordingUsers(StarxUser... users) {
      super(null);
      this.users = Map.of(users[0].uuid(), users[0], users[1].uuid(), users[1]);
    }

    @Override
    public Optional<StarxUser> findFullByUuid(UUID uuid) {
      return Optional.ofNullable(this.users.get(uuid));
    }

    @Override
    public Optional<StarxUser> findFullByUsername(String username) {
      this.usernameLookups.add(username);
      return this.users.values().stream()
          .filter(user -> user.username().equalsIgnoreCase(username))
          .findFirst();
    }

    @Override
    public void updateLastLogin(UUID uuid, Instant lastLogin) {
      this.lastLoginUuid = uuid;
    }

    @Override
    public void updatePremium(UUID uuid, boolean premium) {
      // The fixture isolates the session identity boundary.
    }
  }

  private static final class RejectingUsers extends JdbcUserRepository {
    private RejectingUsers() {
      super(null);
    }

    @Override
    public Optional<StarxUser> findFullByUuid(UUID uuid) {
      throw new AssertionError("account lookup must not run before session validation");
    }

    @Override
    public Optional<StarxUser> findFullByUsername(String username) {
      throw new AssertionError("account lookup must not run before session validation");
    }
  }
}
