package io.github.addxiaoyi.starx.common.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.common.crypto.PasswordHasher;
import io.github.addxiaoyi.starx.common.crypto.TotpGenerator;
import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import io.github.addxiaoyi.starx.common.event.LocalEventBus;
import io.github.addxiaoyi.starx.common.model.StarxUser;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class AuthServiceTotpEnrollmentTest {
  @Test
  void persistsTotpOnlyAfterValidConfirmation() {
    UUID playerId = UUID.randomUUID();
    FakeUsers users = new FakeUsers(playerId, "correct-password");
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    try {
      AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
      TotpEnrollment enrollment = auth.beginTotpEnrollment(playerId, "correct-password");

      assertNull(users.savedSecret);
      assertFalse(auth.confirmTotpEnrollment(playerId, "000000").success());
      assertNull(users.savedSecret);

      String code = TotpGenerator.generate(enrollment.secret(), Instant.now());
      assertTrue(auth.confirmTotpEnrollment(playerId, code).success());
      assertTrue(enrollment.secret().equals(users.savedSecret));
      assertTrue(users.trustCleared);
    } finally {
      sessions.shutdown();
    }
  }

  private static final class FakeUsers extends JdbcUserRepository {
    private final StarxUser user;
    private String savedSecret;
    private boolean trustCleared;

    private FakeUsers(UUID playerId, String password) {
      super(null);
      this.user = new StarxUser(
          playerId, "add", null, PasswordHasher.hash(password), null, false,
          Instant.now(), null, null, List.of(), "", "starx", "completed",
          null, "", "", "", 0L, null, false);
    }

    @Override
    public Optional<StarxUser> findFullByUuid(UUID uuid) {
      return this.user.uuid().equals(uuid) ? Optional.of(this.user) : Optional.empty();
    }

    @Override
    public boolean enableTotp(UUID uuid, String secret, String recoveryCodes) {
      this.savedSecret = secret;
      return true;
    }

    @Override
    public void updateTrustedDevices(UUID uuid, List<String> trustedDevices) {
      this.trustCleared = trustedDevices.isEmpty();
    }
  }
}
