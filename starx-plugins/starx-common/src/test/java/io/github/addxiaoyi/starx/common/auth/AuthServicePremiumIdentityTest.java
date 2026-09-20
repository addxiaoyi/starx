package io.github.addxiaoyi.starx.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import io.github.addxiaoyi.starx.common.event.LocalEventBus;
import io.github.addxiaoyi.starx.common.identity.IdentitySource;
import io.github.addxiaoyi.starx.common.model.StarxUser;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
      AtomicReference<UUID> observedUuid = new AtomicReference<>();
      AtomicReference<String> observedName = new AtomicReference<>();
      AtomicReference<IdentitySource> observedSource = new AtomicReference<>();
      auth.bindMinecraftIdentityObserver((uuid, name, source) -> {
        observedUuid.set(uuid);
        observedName.set(name);
        observedSource.set(source);
      });
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
      assertEquals(premiumUuid, observedUuid.get());
      assertEquals(username, observedName.get());
      assertEquals(IdentitySource.MOJANG, observedSource.get());
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void trustedIdentityCanBeObservedBeforeAuthenticationChallenge() {
    UUID premiumUuid = UUID.randomUUID();
    AtomicReference<UUID> observedUuid = new AtomicReference<>();
    AtomicReference<String> observedName = new AtomicReference<>();
    AtomicReference<IdentitySource> observedSource = new AtomicReference<>();
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    try {
      AuthService auth = new AuthService(
          new TestUsers(null), new LocalEventBus(), sessions);
      auth.bindMinecraftIdentityObserver((uuid, name, source) -> {
        observedUuid.set(uuid);
        observedName.set(name);
        observedSource.set(source);
      });

      auth.observeTrustedMinecraftIdentity(
          premiumUuid, "CurrentName", IdentitySource.MOJANG);

      assertEquals(premiumUuid, observedUuid.get());
      assertEquals("CurrentName", observedName.get());
      assertEquals(IdentitySource.MOJANG, observedSource.get());
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void premiumLoginAcceptsStoredOfflineNameWhenCapitalizationChanged() throws Exception {
    String storedName = "PremiumUser";
    UUID offlineUuid = offlineUuid(storedName);
    UUID premiumUuid = UUID.fromString("f739b91a-6f95-4b1f-a1f7-4d15f20c6e44");
    TestUsers users = new TestUsers(existingUser(offlineUuid, storedName));
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthLease lease = AuthLease.create();
    try {
      AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
      assertTrue(auth.openConnection(lease, premiumUuid, "premiumuser", InetAddress.getLoopbackAddress()));

      AuthResult result = auth.autoLoginTrusted(
          lease,
          premiumUuid,
          "premiumuser",
          InetAddress.getLoopbackAddress(),
          "premium",
          true);

      assertTrue(result.success());
      assertEquals(offlineUuid, users.updatedUuid);
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void missingUsernameDoesNotCrashConnectedUserLookup() throws Exception {
    UUID connectionUuid = UUID.fromString("0d1e2f30-4152-4a5b-8c6d-7e8f9012a345");
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthLease lease = AuthLease.create();
    try {
      AuthService auth = new AuthService(new TestUsers(null), new LocalEventBus(), sessions);
      assertTrue(auth.openConnection(
          lease, connectionUuid, "UnknownUser", InetAddress.getLoopbackAddress()));

      assertTrue(auth.findConnectedUser(connectionUuid).isEmpty());
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

  @Test
  void trustedProvisionedAccountCleanupIsRetriedAfterIdentityFailure() throws Exception {
    UUID playerId = UUID.randomUUID();
    TestUsers users = new TestUsers(null);
    users.failDelete = true;
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthLease lease = AuthLease.create();
    try {
      AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
      auth.bindMinecraftIdentityObserver((uuid, name, source) -> {
        throw new IllegalStateException("identity database unavailable");
      });
      assertTrue(auth.openConnection(lease, playerId, "TrustedNewUser", InetAddress.getLoopbackAddress()));

      AuthResult result = auth.autoLoginTrusted(
          lease, playerId, "TrustedNewUser", InetAddress.getLoopbackAddress(), "premium", true);

      assertFalse(result.success());
      assertTrue(users.findFullByUuid(playerId).isPresent());
      users.failDelete = false;
      AuthLease replacement = AuthLease.create();
      assertTrue(auth.openConnection(
          replacement, playerId, "TrustedNewUser", InetAddress.getLoopbackAddress()));
      assertTrue(users.findFullByUuid(playerId).isEmpty());
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void trustedProvisionedAccountIsRemovedWhenAuthenticatedRouteIsAborted() throws Exception {
    UUID playerId = UUID.randomUUID();
    TestUsers users = new TestUsers(null);
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthLease lease = AuthLease.create();
    try {
      AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
      assertTrue(auth.openConnection(lease, playerId, "RouteAbortUser", InetAddress.getLoopbackAddress()));

      assertTrue(auth.autoLoginTrusted(
          lease, playerId, "RouteAbortUser", InetAddress.getLoopbackAddress(), "premium", true)
          .success());
      assertTrue(users.findFullByUuid(playerId).isPresent());

      auth.logout(playerId);

      assertTrue(users.findFullByUuid(playerId).isEmpty());
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void trustedProvisionedAccountSurvivesLogoutAfterRouteCommit() throws Exception {
    UUID playerId = UUID.randomUUID();
    TestUsers users = new TestUsers(null);
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthLease lease = AuthLease.create();
    try {
      AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
      assertTrue(auth.openConnection(lease, playerId, "RouteCommitUser", InetAddress.getLoopbackAddress()));
      assertTrue(auth.autoLoginTrusted(
          lease, playerId, "RouteCommitUser", InetAddress.getLoopbackAddress(), "premium", true)
          .success());

      auth.completeAuthenticatedProvisioning(playerId, lease);
      auth.logout(playerId);

      assertTrue(users.findFullByUuid(playerId).isPresent());
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void premiumTrustedLoginCannotClaimAnUnrelatedAccountWithTheSameUsername() throws Exception {
    String username = "PremiumUser";
    UUID unrelatedUuid = UUID.randomUUID();
    UUID premiumUuid = UUID.randomUUID();
    TestUsers users = new TestUsers(existingUser(unrelatedUuid, username));
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

      assertFalse(result.success());
      assertEquals(0, users.createCalls);
      assertEquals(null, users.updatedUuid);
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void floodgateTrustedLoginCannotClaimAnUpgradedPremiumAccountByName() throws Exception {
    String username = "PremiumUser";
    UUID offlineUuid = offlineUuid(username);
    UUID floodgateUuid = UUID.randomUUID();
    TestUsers users = new TestUsers(existingUser(offlineUuid, username, true));
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthLease lease = AuthLease.create();
    try {
      AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
      assertTrue(auth.openConnection(
          lease, floodgateUuid, username, InetAddress.getLoopbackAddress()));

      AuthResult result = auth.autoLoginTrusted(
          lease,
          floodgateUuid,
          username,
          InetAddress.getLoopbackAddress(),
          "floodgate",
          false);

      assertFalse(result.success());
      assertEquals(0, users.createCalls);
      assertEquals(null, users.updatedUuid);
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void connectedUserLookupCannotResolveAnUnrelatedUuidByUsername() throws Exception {
    String username = "PremiumUser";
    UUID connectionUuid = UUID.randomUUID();
    TestUsers users = new TestUsers(existingUser(offlineUuid(username), username));
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthLease lease = AuthLease.create();
    try {
      AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
      assertTrue(auth.openConnection(lease, connectionUuid, username, InetAddress.getLoopbackAddress()));

      assertFalse(auth.findConnectedUser(connectionUuid).isPresent());
      assertFalse(auth.isUserRegistered(connectionUuid, username));
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void ipBypassUsesTheStoredAccountUuidWhenPremiumUuidChanges() throws Exception {
    String username = "PremiumUser";
    UUID offlineUuid = offlineUuid(username);
    UUID premiumUuid = UUID.randomUUID();
    TestUsers users = new TestUsers(existingUser(offlineUuid, username));
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthLease lease = AuthLease.create();
    InetAddress address = InetAddress.getLoopbackAddress();
    try {
      AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
      auth.bindMinecraftIdentityResolver(ignored -> Set.of(offlineUuid, premiumUuid));
      assertTrue(auth.openConnection(lease, premiumUuid, username, address, "device-a"));
      auth.recordSuccessfulLogin(offlineUuid, address.getHostAddress(), "local", "device-a");

      assertTrue(auth.shouldBypassAuth(
          premiumUuid, address.getHostAddress(), "device-a", false, false, false));
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void resolvesTheStoredAccountForAChangedConnectionUuid() throws Exception {
    String username = "PremiumUser";
    UUID offlineUuid = offlineUuid(username);
    UUID premiumUuid = UUID.randomUUID();
    TestUsers users = new TestUsers(existingUser(offlineUuid, username));
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthLease lease = AuthLease.create();
    try {
      AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
      auth.bindMinecraftIdentityResolver(ignored -> Set.of(offlineUuid, premiumUuid));
      assertTrue(auth.openConnection(lease, premiumUuid, username, InetAddress.getLoopbackAddress()));

      StarxUser resolved = auth.findConnectedUser(premiumUuid).orElseThrow();

      assertEquals(offlineUuid, resolved.uuid());
    } finally {
      sessions.shutdown();
    }
  }

  private static StarxUser existingUser(UUID uuid, String username) {
    return existingUser(uuid, username, false);
  }

  private static StarxUser existingUser(UUID uuid, String username, boolean premium) {
    Instant created = Instant.parse("2026-01-01T00:00:00Z");
    return new StarxUser(
        uuid,
        username,
        null,
        "hash",
        null,
        premium,
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
    private StarxUser created;
    private int createCalls;
    private UUID updatedUuid;
    private UUID premiumUpdatedUuid;
    private boolean failDelete;

    private TestUsers(StarxUser existing) {
      super(null);
      this.existing = existing;
    }

    @Override
    public boolean existsByUuid(UUID uuid) {
      return (this.existing != null && this.existing.uuid().equals(uuid))
          || (this.created != null && this.created.uuid().equals(uuid));
    }

    @Override
    public Optional<StarxUser> findFullByUuid(UUID uuid) {
      if (this.created != null && this.created.uuid().equals(uuid)) {
        return Optional.of(this.created);
      }
      return this.existing != null && this.existing.uuid().equals(uuid)
          ? Optional.of(this.existing) : Optional.empty();
    }

    @Override
    public Optional<StarxUser> findFullByUsername(String username) {
      if (this.created != null && this.created.username().equalsIgnoreCase(username)) {
        return Optional.of(this.created);
      }
      return this.existing != null && this.existing.username().equalsIgnoreCase(username)
          ? Optional.of(this.existing) : Optional.empty();
    }

    @Override
    public void create(StarxUser user) {
      this.createCalls++;
      this.created = user;
    }

    @Override
    public void delete(UUID uuid) {
      if (this.failDelete) {
        throw new IllegalStateException("delete unavailable");
      }
      if (this.created != null && this.created.uuid().equals(uuid)) {
        this.created = null;
      }
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
