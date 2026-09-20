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
import java.util.Set;
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

  @Test
  void failedConfirmationPreservesExistingTrustForRetry() {
    UUID playerId = UUID.randomUUID();
    FakeUsers users = new FakeUsers(playerId, "correct-password");
    users.enableTotpResult = false;
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    try {
      AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
      TotpEnrollment enrollment = auth.beginTotpEnrollment(playerId, "correct-password");

      AuthResult result = auth.confirmTotpEnrollment(
          playerId, TotpGenerator.generate(enrollment.secret(), Instant.now()));

      assertFalse(result.success());
      assertFalse(users.trustCleared);
      assertNull(users.savedSecret);
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void enrollmentCanStartAndConfirmThroughDifferentMinecraftIdentityAliases() {
    UUID legacyUuid = UUID.randomUUID();
    UUID premiumUuid = UUID.randomUUID();
    FakeUsers users = new FakeUsers(legacyUuid, "correct-password");
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    try {
      AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
      auth.bindMinecraftIdentityResolver(ignored -> Set.of(legacyUuid, premiumUuid));

      TotpEnrollment enrollment = auth.beginTotpEnrollment(premiumUuid, "correct-password");
      AuthResult result = auth.confirmTotpEnrollment(
          legacyUuid, TotpGenerator.generate(enrollment.secret(), Instant.now()));

      assertTrue(result.success());
      assertTrue(enrollment.secret().equals(users.savedSecret));
    } finally {
      sessions.shutdown();
    }
  }

  private static final class FakeUsers extends JdbcUserRepository {
    private final StarxUser user;
    private String savedSecret;
    private boolean trustCleared;
    private boolean enableTotpResult = true;

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
      if (this.enableTotpResult) this.savedSecret = secret;
      return this.enableTotpResult;
    }

    @Override
    public void updateTrustedDevices(UUID uuid, List<String> trustedDevices) {
      this.trustCleared = trustedDevices.isEmpty();
    }
  }
}
