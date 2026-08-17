package io.github.addxiaoyi.starx.common.auth;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.addxiaoyi.starx.api.dto.UserDto;
import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import io.github.addxiaoyi.starx.common.event.LocalEventBus;
import io.github.addxiaoyi.starx.common.model.StarxUser;
import io.github.addxiaoyi.starx.common.model.IpSession;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class AuthServiceRegistrationBypassTest {

  @Test
  void registrationCountsAsARecentPasswordLogin() throws Exception {
    UUID playerId = UUID.randomUUID();
    FakeUsers users = new FakeUsers();
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    InetAddress address = InetAddress.getByName("203.0.113.42");
    AuthLease lease = AuthLease.create();
    try {
      AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
      auth.setIpBypassMinutes(30);
      assertTrue(auth.openConnection(lease, playerId, "NewPlayer", address, "device-a"));

      AuthResult registered = auth.register(
          lease, playerId, "NewPlayer", "ValidPassword_123", null);

      assertTrue(registered.success());
      assertTrue(auth.shouldBypassAuth(
          playerId, address.getHostAddress(), "device-a", false, false, false));
      org.junit.jupiter.api.Assertions.assertFalse(auth.shouldBypassAuth(
          playerId, address.getHostAddress(), "device-b", false, false, false));
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void registrationRemainsAuthenticatedWhenLoginHistoryIsUnavailable() throws Exception {
    UUID playerId = UUID.randomUUID();
    FakeUsers users = new FakeUsers();
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthLease lease = AuthLease.create();
    try {
      AuthService auth = new AuthService(
          users, new LocalEventBus(), sessions, null, null, failingIpSessionStore());
      assertTrue(auth.openConnection(
          lease, playerId, "NewPlayer", InetAddress.getLoopbackAddress(), "device-a"));

      AuthResult registered = auth.register(
          lease, playerId, "NewPlayer", "ValidPassword_123", null);

      assertTrue(registered.success());
      assertTrue(auth.isAuthenticated(lease, playerId));
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void registrationNormalizesOptionalEmailBeforePersistence() throws Exception {
    UUID playerId = UUID.randomUUID();
    FakeUsers users = new FakeUsers();
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthLease lease = AuthLease.create();
    try {
      AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
      assertTrue(auth.openConnection(lease, playerId, "NewPlayer", null));

      AuthResult registered = auth.register(
          lease, playerId, "NewPlayer", "ValidPassword_123", " Alice@Example.COM ");

      assertTrue(registered.success());
      assertEquals("alice@example.com", users.user.email());
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void registrationRejectsMalformedOptionalEmailAsAClientError() throws Exception {
    UUID playerId = UUID.randomUUID();
    FakeUsers users = new FakeUsers();
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthLease lease = AuthLease.create();
    try {
      AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
      assertTrue(auth.openConnection(lease, playerId, "NewPlayer", null));

      AuthResult registered = auth.register(
          lease, playerId, "NewPlayer", "ValidPassword_123", "not-an-email");

      org.junit.jupiter.api.Assertions.assertFalse(registered.success());
      org.junit.jupiter.api.Assertions.assertEquals(null, users.user);
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void registrationRejectsAnEmailAlreadyBoundToAnotherAccount() {
    UUID playerId = UUID.randomUUID();
    FakeUsers users = new FakeUsers();
    users.occupiedEmail = "alice@example.com";
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthLease lease = AuthLease.create();
    try {
      AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
      assertTrue(auth.openConnection(lease, playerId, "NewPlayer", null));

      AuthResult registered = auth.register(
          lease, playerId, "NewPlayer", "ValidPassword_123", "Alice@Example.com");

      org.junit.jupiter.api.Assertions.assertFalse(registered.success());
      assertEquals("该邮箱已被其他账号绑定", registered.message());
      assertEquals(null, users.user);
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void expiredRegistrationLeaseDoesNotLeaveAUserBehind() throws Exception {
    AtomicReference<Instant> clock = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    ExpiringUsers users = new ExpiringUsers(clock);
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), clock::get);
    UUID playerId = UUID.randomUUID();
    AuthLease lease = AuthLease.create();
    try {
      AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
      assertTrue(auth.openConnection(lease, playerId, "NewPlayer", null));

      AuthResult registered = auth.register(
          lease, playerId, "NewPlayer", "ValidPassword_123", null);

      org.junit.jupiter.api.Assertions.assertFalse(registered.success());
      org.junit.jupiter.api.Assertions.assertTrue(users.user == null);
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void expiredTrustedLoginLeaseDoesNotLeaveAUserBehind() throws Exception {
    AtomicReference<Instant> clock = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    ExpiringUsers users = new ExpiringUsers(clock);
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), clock::get);
    UUID playerId = UUID.randomUUID();
    AuthLease lease = AuthLease.create();
    try {
      AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
      assertTrue(auth.openConnection(lease, playerId, "NewPlayer", null));

      AuthResult loggedIn = auth.autoLoginTrusted(
          lease, playerId, "NewPlayer", null, "premium", true);

      org.junit.jupiter.api.Assertions.assertFalse(loggedIn.success());
      org.junit.jupiter.api.Assertions.assertTrue(users.user == null);
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void failedTrustedLoginCleanupReturnsAnExplicitFailure() throws Exception {
    AtomicReference<Instant> clock = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    ExpiringUsers users = new ExpiringUsers(clock, true);
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), clock::get);
    UUID playerId = UUID.randomUUID();
    AuthLease lease = AuthLease.create();
    try {
      AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
      assertTrue(auth.openConnection(lease, playerId, "NewPlayer", null));

      AuthResult loggedIn = auth.autoLoginTrusted(
          lease, playerId, "NewPlayer", null, "premium", true);

      org.junit.jupiter.api.Assertions.assertFalse(loggedIn.success());
      assertEquals("认证会话已过期，账户清理失败，请联系管理员", loggedIn.message());
      org.junit.jupiter.api.Assertions.assertTrue(users.user != null);
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void registrationIdentityFailureDoesNotWriteLoginHistoryBeforeRollback() throws Exception {
    UUID playerId = UUID.randomUUID();
    FakeUsers users = new FakeUsers();
    AtomicBoolean historyWritten = new AtomicBoolean();
    IpSessionStore history = new IpSessionStore() {
      @Override public void save(IpSession session) { historyWritten.set(true); }
      @Override public Optional<IpSession> findByUuidAndIp(UUID uuid, String ip) { return Optional.empty(); }
      @Override public boolean hasRecentSession(UUID uuid, String ip, int hours) { return false; }
      @Override public List<IpSession> findRecentSessions(UUID uuid, int hours) { return List.of(); }
      @Override public Optional<IpSession> findLatestByUuid(UUID uuid) { return Optional.empty(); }
      @Override public void deleteByUuid(UUID uuid) { }
    };
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthLease lease = AuthLease.create();
    try {
      AuthService auth = new AuthService(
          users, new LocalEventBus(), sessions, null, null, history);
      auth.bindMinecraftIdentityObserver((uuid, name, source) -> {
        throw new IllegalStateException("identity database unavailable");
      });
      assertTrue(auth.openConnection(
          lease, playerId, "NewPlayer", InetAddress.getLoopbackAddress(), "device-a"));

      AuthResult result = auth.register(
          lease, playerId, "NewPlayer", "ValidPassword_123", null);

      org.junit.jupiter.api.Assertions.assertFalse(result.success());
      org.junit.jupiter.api.Assertions.assertFalse(historyWritten.get());
      org.junit.jupiter.api.Assertions.assertTrue(users.user == null);
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void registrationIdentityFailureInvokesIdentityRollback() throws Exception {
    UUID playerId = UUID.randomUUID();
    FakeUsers users = new FakeUsers();
    AtomicBoolean identityRolledBack = new AtomicBoolean();
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthLease lease = AuthLease.create();
    try {
      AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
      auth.bindMinecraftIdentityObserver((uuid, name, source) -> {
        throw new IllegalStateException("identity database unavailable");
      });
      auth.bindMinecraftIdentityRollback(ignored -> identityRolledBack.set(true));
      assertTrue(auth.openConnection(
          lease, playerId, "NewPlayer", InetAddress.getLoopbackAddress(), "device-a"));

      assertFalse(auth.register(
          lease, playerId, "NewPlayer", "ValidPassword_123", null).success());
      assertTrue(identityRolledBack.get());
    } finally {
      sessions.shutdown();
    }
  }

  private static final class FakeUsers extends JdbcUserRepository {
    private StarxUser user;
    private String occupiedEmail;

    private FakeUsers() {
      super(null);
    }

    @Override
    public boolean existsByUsernameOrUuid(String username, UUID uuid) {
      return this.user != null;
    }

    @Override
    public boolean existsByUuid(UUID uuid) {
      return this.user != null && this.user.uuid().equals(uuid);
    }

    @Override
    public Optional<StarxUser> findFullByUuid(UUID uuid) {
      return this.existsByUuid(uuid) ? Optional.of(this.user) : Optional.empty();
    }

    @Override
    public void create(StarxUser user) {
      this.user = user;
    }

    @Override
    public void delete(UUID uuid) {
      if (this.user != null && this.user.uuid().equals(uuid)) this.user = null;
    }

    @Override
    public Optional<UserDto> findByEmail(String email) {
      if (this.occupiedEmail == null || !this.occupiedEmail.equalsIgnoreCase(email)) {
        return Optional.empty();
      }
      return Optional.of(UserDto.builder()
          .uuid(UUID.randomUUID()).username("ExistingPlayer").email(this.occupiedEmail).build());
    }

  }

  private static IpSessionStore failingIpSessionStore() {
    return new IpSessionStore() {
      @Override public void save(IpSession session) {
        throw new IllegalStateException("login history unavailable");
      }
      @Override public Optional<IpSession> findByUuidAndIp(UUID uuid, String ip) {
        return Optional.empty();
      }
      @Override public boolean hasRecentSession(UUID uuid, String ip, int hours) { return false; }
      @Override public List<IpSession> findRecentSessions(UUID uuid, int hours) { return List.of(); }
      @Override public Optional<IpSession> findLatestByUuid(UUID uuid) { return Optional.empty(); }
      @Override public void deleteByUuid(UUID uuid) { }
    };
  }

  private static final class ExpiringUsers extends JdbcUserRepository {
    private final AtomicReference<Instant> clock;
    private final boolean failDelete;
    private StarxUser user;

    private ExpiringUsers(AtomicReference<Instant> clock) {
      this(clock, false);
    }

    private ExpiringUsers(AtomicReference<Instant> clock, boolean failDelete) {
      super(null);
      this.clock = clock;
      this.failDelete = failDelete;
    }

    @Override
    public boolean existsByUsernameOrUuid(String username, UUID uuid) {
      return this.user != null;
    }

    @Override
    public void create(StarxUser user) {
      this.user = user;
      this.clock.set(this.clock.get().plus(Duration.ofMinutes(6)));
    }

    @Override
    public Optional<StarxUser> findFullByUuid(UUID uuid) {
      return this.user != null && this.user.uuid().equals(uuid)
          ? Optional.of(this.user) : Optional.empty();
    }

    @Override
    public Optional<StarxUser> findFullByUsername(String username) {
      return this.user != null && this.user.username().equalsIgnoreCase(username)
          ? Optional.of(this.user) : Optional.empty();
    }

    @Override
    public void delete(UUID uuid) {
      if (this.failDelete) throw new IllegalStateException("cleanup unavailable");
      if (this.user != null && this.user.uuid().equals(uuid)) this.user = null;
    }
  }
}
