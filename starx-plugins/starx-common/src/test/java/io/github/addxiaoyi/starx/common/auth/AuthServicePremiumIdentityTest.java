package io.github.addxiaoyi.starx.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import io.github.addxiaoyi.starx.common.event.LocalEventBus;
import io.github.addxiaoyi.starx.common.model.StarxUser;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class AuthServicePremiumIdentityTest {

  @Test
  void premiumLoginReusesExistingAccountWhenUuidChanged() throws Exception {
    String username = "PremiumUser";
    UUID offlineUuid = offlineUuid(username);
    UUID premiumUuid = UUID.fromString("e628a809-cffd-4487-b651-a15cae9b0ab7");
    TestUsers users = new TestUsers(existingUser(offlineUuid, username));
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthLease lease = AuthLease.create();
    try {
      AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
      assertTrue(auth.openConnection(lease, premiumUuid, username, InetAddress.getLoopbackAddress()));

      AuthResult result = auth.autoLoginTrusted(
          lease,
          premiumUuid,
          username,
          InetAddress.getLoopbackAddress(),
          "premium",
          true);

      assertTrue(result.success());
      assertEquals(0, users.createCalls);
      assertEquals(offlineUuid, users.updatedUuid);
      assertEquals(offlineUuid, users.premiumUpdatedUuid);
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void nonPremiumTrustedLoginCannotClaimAnExistingUsername() throws Exception {
    String username = "PremiumUser";
    UUID offlineUuid = offlineUuid(username);
    UUID otherUuid = UUID.randomUUID();
    TestUsers users = new TestUsers(existingUser(offlineUuid, username));
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthLease lease = AuthLease.create();
    try {
      AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
      assertTrue(auth.openConnection(lease, otherUuid, username, InetAddress.getLoopbackAddress()));

      AuthResult result = auth.autoLoginTrusted(
          lease,
          otherUuid,
          username,
          InetAddress.getLoopbackAddress(),
          "external-handshake",
          false);

      assertFalse(result.success());
      assertEquals(AuthSession.State.GUEST, result.state());
      assertEquals(0, users.createCalls);
    } finally {
      sessions.shutdown();
    }
  }

  private static StarxUser existingUser(UUID uuid, String username) {
    Instant created = Instant.parse("2026-01-01T00:00:00Z");
    return new StarxUser(
        uuid,
        username,
        null,
        "hash",
        null,
        false,
        created,
        created,
        null,
        List.of(),
        "",
        "authx",
        "completed",
        null,
        null,
        null,
        null,
        0L,
        null,
        false);
  }

  private static UUID offlineUuid(String username) {
    return UUID.nameUUIDFromBytes(
        ("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
  }

  private static final class TestUsers extends JdbcUserRepository {
    private final StarxUser existing;
    private int createCalls;
    private UUID updatedUuid;
    private UUID premiumUpdatedUuid;

    private TestUsers(StarxUser existing) {
      super(null);
      this.existing = existing;
    }

    @Override
    public boolean existsByUuid(UUID uuid) {
      return this.existing.uuid().equals(uuid);
    }

    @Override
    public Optional<StarxUser> findFullByUuid(UUID uuid) {
      return this.existsByUuid(uuid) ? Optional.of(this.existing) : Optional.empty();
    }

    @Override
    public Optional<StarxUser> findFullByUsername(String username) {
      return this.existing.username().equalsIgnoreCase(username)
          ? Optional.of(this.existing)
          : Optional.empty();
    }

    @Override
    public void create(StarxUser user) {
      this.createCalls++;
    }

    @Override
    public void updateLastLogin(UUID uuid, Instant lastLogin) {
      this.updatedUuid = uuid;
    }

    @Override
    public void updatePremium(UUID uuid, boolean premium) {
      if (premium) {
        this.premiumUpdatedUuid = uuid;
      }
    }
  }
}
