package io.github.addxiaoyi.starx.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

final class AuthServiceTrustedIdentityTest {

  @Test
  void trustedFloodgateIdentityProvisionsAReusableLocalAccount() throws Exception {
    UUID playerId = UUID.randomUUID();
    ProvisioningRepository users = new ProvisioningRepository();
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthLease lease = AuthLease.create();
    try {
      AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
      assertNotNull(sessions.open(
          playerId, ".BedrockUser", InetAddress.getLoopbackAddress(), lease));

      AuthResult result = auth.autoLoginTrusted(
          lease,
          playerId,
          ".BedrockUser",
          InetAddress.getLoopbackAddress(),
          "floodgate",
          false);

      assertTrue(result.success());
      assertEquals(AuthSession.State.AUTHENTICATED, result.state());
      assertNotNull(users.created);
      assertEquals("floodgate", users.created.sourceSystem());
      assertFalse(users.created.premium());
      assertTrue(users.existsByUuid(playerId));
    } finally {
      sessions.shutdown();
    }
  }

  private static final class ProvisioningRepository extends JdbcUserRepository {
    private StarxUser created;

    private ProvisioningRepository() {
      super(null);
    }

    @Override
    public boolean existsByUuid(UUID uuid) {
      return this.created != null && this.created.uuid().equals(uuid);
    }

    @Override
    public Optional<StarxUser> findFullByUuid(UUID uuid) {
      return existsByUuid(uuid) ? Optional.of(this.created) : Optional.empty();
    }

    @Override
    public Optional<StarxUser> findFullByUsername(String username) {
      return Optional.empty();
    }

    @Override
    public void create(StarxUser user) {
      this.created = user;
    }

    @Override
    public void updateLastLogin(UUID uuid, Instant lastLogin) {
    }

    @Override
    public void updatePremium(UUID uuid, boolean premium) {
    }
  }
}
