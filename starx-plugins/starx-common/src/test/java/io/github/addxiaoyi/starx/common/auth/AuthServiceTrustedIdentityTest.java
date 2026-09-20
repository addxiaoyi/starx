package io.github.addxiaoyi.starx.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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

  @Test
  void externalHandshakeCannotProvisionAnAccountWithoutAPlayerIdentity() throws Exception {
    UUID playerId = UUID.randomUUID();
    ProvisioningRepository users = new ProvisioningRepository();
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthLease lease = AuthLease.create();
    try {
      AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
      assertNotNull(sessions.open(
          playerId, "UnverifiedPlayer", InetAddress.getLoopbackAddress(), lease));

      AuthResult result = auth.autoLoginTrusted(
          lease,
          playerId,
          "UnverifiedPlayer",
          InetAddress.getLoopbackAddress(),
          "external-handshake",
          false);

      assertFalse(result.success());
      assertEquals("外部握手未提供可验证的玩家身份", result.message());
      assertFalse(users.existsByUuid(playerId));
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void externalHandshakeCannotBypassPasswordForAnExistingAccount() throws Exception {
    UUID playerId = UUID.randomUUID();
    ProvisioningRepository users = new ProvisioningRepository(
        ProvisioningRepository.existingUser(playerId, "ExistingPlayer"));
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthLease lease = AuthLease.create();
    try {
      AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
      assertNotNull(sessions.open(
          playerId, "ExistingPlayer", InetAddress.getLoopbackAddress(), lease));

      AuthResult result = auth.autoLoginTrusted(
          lease,
          playerId,
          "ExistingPlayer",
          InetAddress.getLoopbackAddress(),
          "external-handshake",
          false);

      assertFalse(result.success());
      assertEquals("外部握手未提供可验证的玩家身份", result.message());
      assertEquals(null, users.updatedUuid);
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void unknownTrustedSourceCannotProvisionAnAccount() throws Exception {
    UUID playerId = UUID.randomUUID();
    ProvisioningRepository users = new ProvisioningRepository();
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthLease lease = AuthLease.create();
    try {
      AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
      assertNotNull(sessions.open(
          playerId, "UnverifiedPlayer", InetAddress.getLoopbackAddress(), lease));

      AuthResult result = auth.autoLoginTrusted(
          lease, playerId, "UnverifiedPlayer", InetAddress.getLoopbackAddress(), "unknown", false);

      assertFalse(result.success());
      assertEquals("可信身份来源无效", result.message());
      assertFalse(users.existsByUuid(playerId));
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void premiumFlagCannotAuthorizeAFloodgateSource() throws Exception {
    UUID playerId = UUID.randomUUID();
    ProvisioningRepository users = new ProvisioningRepository();
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthLease lease = AuthLease.create();
    try {
      AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
      assertNotNull(sessions.open(
          playerId, "UnverifiedPlayer", InetAddress.getLoopbackAddress(), lease));

      AuthResult result = auth.autoLoginTrusted(
          lease, playerId, "UnverifiedPlayer", InetAddress.getLoopbackAddress(), "floodgate", true);

      assertFalse(result.success());
      assertEquals("可信身份来源无效", result.message());
      assertFalse(users.existsByUuid(playerId));
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void websiteBindingSourceCannotBypassTheBindingCheck() throws Exception {
    UUID playerId = UUID.randomUUID();
    ProvisioningRepository users = new ProvisioningRepository(
        ProvisioningRepository.existingUser(playerId, "ExistingPlayer"));
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthLease lease = AuthLease.create();
    try {
      AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
      assertNotNull(sessions.open(
          playerId, "ExistingPlayer", InetAddress.getLoopbackAddress(), lease));

      AuthResult result = auth.autoLoginTrusted(
          lease, playerId, "ExistingPlayer", InetAddress.getLoopbackAddress(),
          "website-binding", false);

      assertFalse(result.success());
      assertEquals(AuthSession.State.GUEST, result.state());
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void websiteBindingLookupUsesTheRequestedSessionUsername() throws Exception {
    UUID playerId = UUID.randomUUID();
    ProvisioningRepository users = new WebsiteBindingRepository(
        ProvisioningRepository.existingUser(playerId, "OldName"));
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthLease lease = AuthLease.create();
    try {
      AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
      assertNotNull(sessions.open(
          playerId, "CurrentName", InetAddress.getLoopbackAddress(), lease));

      assertTrue(auth.hasTrustedWebsiteBinding(playerId, "CurrentName"));
      assertFalse(auth.hasTrustedWebsiteBinding(playerId, "OtherName"));
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void trustedIdentityBindingReusesAnOfflineAccountAcrossMinecraftUuids() throws Exception {
    String username = "BoundPlayer";
    UUID offlineUuid = UUID.nameUUIDFromBytes(
        ("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
    UUID floodgateUuid = UUID.randomUUID();
    ProvisioningRepository users = new ProvisioningRepository(
        ProvisioningRepository.existingUser(offlineUuid, username));
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthLease lease = AuthLease.create();
    try {
      AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
      auth.bindMinecraftIdentityResolver(ignored -> Set.of(offlineUuid, floodgateUuid));
      assertNotNull(sessions.open(
          floodgateUuid, username, InetAddress.getLoopbackAddress(), lease));

      AuthResult result = auth.autoLoginTrusted(
          lease, floodgateUuid, username, InetAddress.getLoopbackAddress(), "floodgate", false);

      assertTrue(result.success());
      assertEquals(offlineUuid, users.updatedUuid);
      assertEquals(AuthSession.State.AUTHENTICATED, result.state());
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void trustedLoginDoesNotStayAuthenticatedWhenIdentityObservationFails() throws Exception {
    UUID playerId = UUID.randomUUID();
    ProvisioningRepository users = new ProvisioningRepository(
        ProvisioningRepository.existingUser(playerId, "ExistingPlayer"));
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthLease lease = AuthLease.create();
    try {
      AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
      auth.bindMinecraftIdentityObserver((uuid, name, source) -> {
        throw new IllegalStateException("identity database unavailable");
      });
      assertNotNull(sessions.open(
          playerId, "ExistingPlayer", InetAddress.getLoopbackAddress(), lease));

      AuthResult result = auth.autoLoginTrusted(
          lease, playerId, "ExistingPlayer", InetAddress.getLoopbackAddress(), "premium", true);

      assertFalse(result.success());
      assertEquals(AuthSession.State.GUEST, result.state());
      assertFalse(auth.isAuthenticated(lease, playerId));
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void trustedLoginMetadataFailureDoesNotObserveMinecraftIdentity() throws Exception {
    UUID playerId = UUID.randomUUID();
    ProvisioningRepository users = new FailingLoginMetadataRepository(
        ProvisioningRepository.existingUser(playerId, "ExistingPlayer"));
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthLease lease = AuthLease.create();
    AtomicInteger observationCalls = new AtomicInteger();
    try {
      AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
      auth.bindMinecraftIdentityObserver((uuid, name, source) -> observationCalls.incrementAndGet());
      assertNotNull(sessions.open(
          playerId, "ExistingPlayer", InetAddress.getLoopbackAddress(), lease));

      AuthResult result = auth.autoLoginTrusted(
          lease, playerId, "ExistingPlayer", InetAddress.getLoopbackAddress(), "premium", true);

      assertFalse(result.success());
      assertEquals(AuthSession.State.GUEST, result.state());
      assertEquals(0, observationCalls.get());
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void trustedLoginIdentityFailureRestoresLoginMetadata() throws Exception {
    UUID playerId = UUID.randomUUID();
    Instant previousLogin = Instant.parse("2026-01-02T00:00:00Z");
    TrackingRepository users = new TrackingRepository(
        new StarxUser(playerId, "ExistingPlayer", null, "hash", null, false,
            Instant.parse("2026-01-01T00:00:00Z"), previousLogin, null, List.of(), "",
            "local", "completed", null, null, null, null, 0L, null, false));
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthLease lease = AuthLease.create();
    try {
      AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
      auth.bindMinecraftIdentityObserver((uuid, name, source) -> {
        throw new IllegalStateException("identity database unavailable");
      });
      assertNotNull(sessions.open(
          playerId, "ExistingPlayer", InetAddress.getLoopbackAddress(), lease));

      AuthResult result = auth.autoLoginTrusted(
          lease, playerId, "ExistingPlayer", InetAddress.getLoopbackAddress(), "premium", true);

      assertFalse(result.success());
      assertEquals(previousLogin, users.lastLoginAt);
      assertFalse(users.premium);
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void trustedLoginPremiumUpdateFailureRestoresEarlierLoginTimestamp() throws Exception {
    UUID playerId = UUID.randomUUID();
    Instant previousLogin = Instant.parse("2026-01-02T00:00:00Z");
    TrackingRepository users = new FailingPremiumRepository(
        new StarxUser(playerId, "ExistingPlayer", null, "hash", null, false,
            Instant.parse("2026-01-01T00:00:00Z"), previousLogin, null, List.of(), "",
            "local", "completed", null, null, null, null, 0L, null, false));
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthLease lease = AuthLease.create();
    try {
      AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
      assertNotNull(sessions.open(
          playerId, "ExistingPlayer", InetAddress.getLoopbackAddress(), lease));

      AuthResult result = auth.autoLoginTrusted(
          lease, playerId, "ExistingPlayer", InetAddress.getLoopbackAddress(), "premium", true);

      assertFalse(result.success());
      assertEquals(previousLogin, users.lastLoginAt);
      assertFalse(users.premium);
    } finally {
      sessions.shutdown();
    }
  }


  private static class ProvisioningRepository extends JdbcUserRepository {
    private StarxUser created;

    protected final StarxUser existing;
    private UUID updatedUuid;

    private ProvisioningRepository() {
      this(null);
    }

    protected ProvisioningRepository(StarxUser existing) {
      super(null);
      this.existing = existing;
    }

    @Override
    public boolean existsByUuid(UUID uuid) {
      return (this.created != null && this.created.uuid().equals(uuid))
          || (this.existing != null && this.existing.uuid().equals(uuid));
    }

    @Override
    public Optional<StarxUser> findFullByUuid(UUID uuid) {
      if (this.created != null && this.created.uuid().equals(uuid)) return Optional.of(this.created);
      return this.existing != null && this.existing.uuid().equals(uuid)
          ? Optional.of(this.existing) : Optional.empty();
    }

    @Override
    public Optional<StarxUser> findFullByUsername(String username) {
      return this.existing != null && this.existing.username().equalsIgnoreCase(username)
          ? Optional.of(this.existing) : Optional.empty();
    }

    @Override
    public void create(StarxUser user) {
      this.created = user;
    }

    @Override
    public void updateLastLogin(UUID uuid, Instant lastLogin) {
      this.updatedUuid = uuid;
    }

    @Override
    public void updatePremium(UUID uuid, boolean premium) {
    }

    @Override
    public boolean hasTrustedWebsiteBinding(UUID uuid, String username) {
      return false;
    }

    private static StarxUser existingUser(UUID uuid, String username) {
      Instant created = Instant.parse("2026-01-01T00:00:00Z");
      return new StarxUser(uuid, username, null, "hash", null, false, created, created,
          null, List.of(), "", "local", "completed", null, null, null, null, 0L, null, false);
    }
  }

  private static final class FailingLoginMetadataRepository extends ProvisioningRepository {
    private FailingLoginMetadataRepository(StarxUser existing) {
      super(existing);
    }

    @Override
    public void updateLastLogin(UUID uuid, Instant lastLogin) {
      throw new IllegalStateException("login metadata unavailable");
    }
  }

  private static class TrackingRepository extends ProvisioningRepository {
    private Instant lastLoginAt;
    private boolean premium;

    private TrackingRepository(StarxUser existing) {
      super(existing);
      this.lastLoginAt = existing.lastLoginAt();
      this.premium = existing.premium();
    }

    @Override
    public void updateLastLogin(UUID uuid, Instant lastLogin) {
      this.lastLoginAt = lastLogin;
    }

    @Override
    public void updatePremium(UUID uuid, boolean premium) {
      this.premium = premium;
    }

    @Override
    public boolean restoreLoginMetadataIfCurrent(
        UUID uuid,
        Instant expectedLastLogin,
        boolean expectedPremium,
        Instant previousLastLogin,
        boolean previousPremium) {
      if (!expectedLastLogin.equals(this.lastLoginAt) || expectedPremium != this.premium) {
        return false;
      }
      this.lastLoginAt = previousLastLogin;
      this.premium = previousPremium;
      return true;
    }
  }

  private static final class FailingPremiumRepository extends TrackingRepository {
    private FailingPremiumRepository(StarxUser existing) {
      super(existing);
    }

    @Override
    public void updatePremium(UUID uuid, boolean premium) {
      throw new IllegalStateException("premium metadata unavailable");
    }
  }

  private static final class WebsiteBindingRepository extends ProvisioningRepository {
    private WebsiteBindingRepository(StarxUser existing) {
      super(existing);
    }

    @Override
    public boolean hasTrustedWebsiteBinding(UUID uuid, String username) {
      return "CurrentName".equals(username);
    }
  }

}
