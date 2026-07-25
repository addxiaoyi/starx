package io.github.addxiaoyi.starx.common.auth;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import io.github.addxiaoyi.starx.common.event.LocalEventBus;
import io.github.addxiaoyi.starx.common.model.StarxUser;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
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

  private static final class FakeUsers extends JdbcUserRepository {
    private StarxUser user;

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
  }
}
