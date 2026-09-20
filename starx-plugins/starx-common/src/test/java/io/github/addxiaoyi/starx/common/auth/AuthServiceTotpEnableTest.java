package io.github.addxiaoyi.starx.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.addxiaoyi.starx.common.crypto.PasswordHasher;
import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import io.github.addxiaoyi.starx.common.event.LocalEventBus;
import io.github.addxiaoyi.starx.common.model.StarxUser;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class AuthServiceTotpEnableTest {

  private static final String PASSWORD = "ValidPassword_123";

  @Test
  void missingUserDuringAtomicEnableDoesNotPublishCredentials() {
    UUID playerId = UUID.randomUUID();
    RejectingRepository users = new RejectingRepository(playerId);
    LocalEventBus events = new LocalEventBus();
    AtomicInteger enabledEvents = new AtomicInteger();
    events.subscribe("player:totp:enabled", event -> enabledEvents.incrementAndGet());
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    try {
      AuthService auth = new AuthService(users, events, sessions);

      AuthResult result = auth.enableTotp(playerId, PASSWORD);

      assertFalse(result.success());
      assertEquals(0, enabledEvents.get());
      assertEquals(1, users.enableCalls.get());
    } finally {
      sessions.shutdown();
    }
  }

  private static final class RejectingRepository extends JdbcUserRepository {
    private final UUID playerId;
    private final String passwordHash = PasswordHasher.hash(PASSWORD);
    private final AtomicInteger enableCalls = new AtomicInteger();

    private RejectingRepository(UUID playerId) {
      super(null);
      this.playerId = playerId;
    }

    @Override
    public Optional<StarxUser> findFullByUuid(UUID uuid) {
      if (!this.playerId.equals(uuid)) {
        return Optional.empty();
      }
      return Optional.of(new StarxUser(
          this.playerId,
          "totp-user",
          null,
          this.passwordHash,
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
    }

    @Override
    public boolean existsByUuid(UUID uuid) {
      return this.playerId.equals(uuid);
    }

    @Override
    public Optional<String> findPasswordHashByUuid(UUID uuid) {
      return this.playerId.equals(uuid)
          ? Optional.of(this.passwordHash)
          : Optional.empty();
    }

    @Override
    public boolean enableTotp(UUID uuid, String secret, String recoveryCodes) {
      this.enableCalls.incrementAndGet();
      return false;
    }

    @Override
    public void updateTrustedDevices(UUID uuid, List<String> devices) {
      // This fixture has no database; trust revocation is verified by dedicated JDBC tests.
    }
  }
}
