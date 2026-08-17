package io.github.addxiaoyi.starx.common.auth.uniauth;

import io.github.addxiaoyi.starx.common.crypto.PasswordHasher;
import io.github.addxiaoyi.starx.common.auth.EmailAddress;
import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import io.github.addxiaoyi.starx.common.model.StarxUser;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class UniAuthBridge {
  private static final Logger LOGGER = Logger.getLogger(UniAuthBridge.class.getName());
  private static final String LEGACY_SOURCE_SYSTEM = "starvc";

  private final UniAuthConfig config;
  private final UniAuthClient client;
  private final JdbcUserRepository userRepository;
  private volatile Function<UUID, Set<UUID>> minecraftIdentityResolver = uuid -> Set.of(uuid);
  private volatile Function<String, Optional<StarxUser>> usernameResolver;

  public UniAuthBridge(
      UniAuthConfig config,
      UniAuthClient client,
      JdbcUserRepository userRepository) {
    this.config = Objects.requireNonNull(config, "config");
    this.client = Objects.requireNonNull(client, "client");
    this.userRepository = Objects.requireNonNull(userRepository, "userRepository");
    this.usernameResolver = userRepository::findFullByUsername;
  }

  public void bindMinecraftIdentityResolver(Function<UUID, Set<UUID>> resolver) {
    this.minecraftIdentityResolver = Objects.requireNonNull(resolver, "resolver");
  }

  public void bindUsernameResolver(Function<String, Optional<StarxUser>> resolver) {
    this.usernameResolver = Objects.requireNonNull(resolver, "resolver");
  }

  public CompletableFuture<BridgeResult> authenticate(
      UUID uuid,
      String username,
      String password) {
    Optional<StarxUser> existing = resolveAccount(uuid, username);
    if (existing.isPresent() && hasLocalPassword(existing.get())) {
      return authenticateLocally(uuid, username, existing.get(), password);
    }

    return client.login(username, password).thenCompose(login -> {
      if (!login.success()) {
        return CompletableFuture.completedFuture(new BridgeResult(
            false,
            login.message() == null ? "Authentication failed" : login.message(),
            null,
            login.serviceUnavailable()));
      }
      if (login.requiresLocalMigration() && existing.isEmpty()) {
        return CompletableFuture.completedFuture(new BridgeResult(
            false,
            "邮箱未验证账号只能迁移已有本地档案",
            null));
      }
      return profileForLogin(username).thenApply(profile -> {
        StarxUser account = existing.orElse(null);
        UniAuthClient.PlayerProfileResponse identity = profile == null ? login.profile() : profile;
        if (!isProfileIdentityCompatible(uuid, username, account, identity)) {
          return new BridgeResult(false, "UniAuth \u8fd4\u56de\u7684\u8eab\u4efd\u4e0e\u5f53\u524d\u73a9\u5bb6\u4e0d\u4e00\u81f4", null);
        }
        return existing.isPresent()
            ? migrateExisting(existing.get(), username, password, profile)
            : createFromUniAuth(uuid, username, password, login, profile);
      });
    });
  }

  static boolean isProfileIdentityCompatible(
      UUID connectionUuid,
      String username,
      StarxUser existing,
      UniAuthClient.PlayerProfileResponse profile) {
    if (profile == null) {
      return existing != null;
    }
    String profileUsername = clean(profile.username());
    String profileUuid = clean(profile.uuid());
    if (profileUsername == null && profileUuid == null) {
      return existing != null;
    }
    if (profileUsername != null && (username == null
        || !profileUsername.equalsIgnoreCase(username))) {
      return false;
    }
    if (profileUuid == null) {
      return true;
    }
    try {
      UUID remoteUuid = UUID.fromString(profileUuid);
      return remoteUuid.equals(connectionUuid)
          || existing != null && remoteUuid.equals(existing.uuid());
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }

  static boolean hasProfileIdentity(UniAuthClient.PlayerProfileResponse profile) {
    return profile != null
        && (clean(profile.username()) != null || clean(profile.uuid()) != null);
  }

  static boolean isOfflineUuidAlias(UUID connectionUuid, String username, StarxUser account) {
    if (connectionUuid == null || username == null || username.isBlank() || account == null
        || !username.equalsIgnoreCase(account.username())) {
      return false;
    }
    UUID offlineUuid = UUID.nameUUIDFromBytes(
        ("OfflinePlayer:" + account.username()).getBytes(StandardCharsets.UTF_8));
    return offlineUuid.equals(account.uuid()) && offlineUuid.equals(connectionUuid);
  }

  private CompletableFuture<BridgeResult> authenticateLocally(
      UUID connectionUuid,
      String username,
      StarxUser user,
      String password) {
    if (!PasswordHasher.verify(password, user.passwordHash())) {
      return CompletableFuture.completedFuture(
          new BridgeResult(false, "Invalid password", null));
    }
    return profileForLogin(username).thenApply(profile -> {
      if (profile != null && !hasProfileIdentity(profile)) {
        return new BridgeResult(true, "Login successful (local)", user);
      }
      if (!isProfileIdentityCompatible(connectionUuid, username, user, profile)) {
        return new BridgeResult(false, "UniAuth 返回的身份与当前玩家不一致", null);
      }
      try {
        synchronizeProfile(user.uuid(), user, profile);
        StarxUser updated = userRepository.findFullByUuid(user.uuid()).orElse(user);
        return new BridgeResult(true, "Login successful (local)", updated);
      } catch (Exception exception) {
        LOGGER.log(Level.WARNING,
            "UniAuth profile synchronization failed after local login for "
                + user.username(),
            exception);
        return new BridgeResult(true, "Login successful (local)", user);
      }
    });
  }

  private CompletableFuture<UniAuthClient.PlayerProfileResponse> profileForLogin(
      String username) {
    UniAuthConfig.ProfileSyncConfig sync = config.profileSync();
    if (!sync.enabled() || !sync.onLogin()) {
      return CompletableFuture.completedFuture(null);
    }
    return client.fetchProfile(username).handle((profile, failure) -> {
      if (failure != null) {
        LOGGER.log(Level.WARNING,
            "UniAuth profile synchronization failed for {0}: {1}",
            new Object[] {username, safeMessage(failure)});
        return null;
      }
      if (profile == null || !profile.success()) {
        LOGGER.log(Level.FINE,
            "UniAuth profile was unavailable for {0}; login continues without synchronization",
            username);
        return null;
      }
      return profile;
    });
  }

  private BridgeResult migrateExisting(
      StarxUser existing,
      String username,
      String password,
      UniAuthClient.PlayerProfileResponse profile) {
    String migratedPasswordHash = PasswordHasher.hash(password);
    try {
      UUID targetUuid = existing.uuid();
      userRepository.markPasswordMigrated(
          targetUuid, migratedPasswordHash, Instant.now());
      synchronizeProfile(targetUuid, existing, profile);
      StarxUser updated = userRepository.findFullByUuid(targetUuid).orElse(existing);
      LOGGER.log(Level.INFO, "User {0} migrated from UniAuth to local auth", username);
      return new BridgeResult(true, "Login successful (migrated from UniAuth)", updated);
    } catch (Exception exception) {
      try {
        userRepository.restorePasswordMigrationIfCurrent(
            existing.uuid(), migratedPasswordHash, existing.passwordHash(),
            existing.migrationState(), existing.passwordMigratedAt());
      } catch (RuntimeException rollbackFailure) {
        exception.addSuppressed(rollbackFailure);
      }
      LOGGER.log(Level.WARNING,
          "Failed to migrate user " + username + " to local auth", exception);
      return new BridgeResult(
          false,
          "本地账号迁移失败，请稍后重试",
          null,
          false);
    }
  }

  private BridgeResult createFromUniAuth(
      UUID uuid,
      String username,
      String password,
      UniAuthClient.LoginResponse login,
      UniAuthClient.PlayerProfileResponse profile) {
    try {
      UniAuthConfig.ProfileSyncConfig sync = config.profileSync();
      String email = sync.enabled() && sync.syncEmail() && profile != null
          ? normalizeProfileEmail(profile.email()) : normalizeProfileEmail(login.email());
      String externalUserId =
          sync.enabled() && sync.syncExternalUserId() && profile != null
              ? clean(profile.externalUserId()) : clean(login.userId());
      String sourceSystem =
          sync.enabled() && profile != null ? sync.sourceSystem() : LEGACY_SOURCE_SYSTEM;
      Instant now = Instant.now();
      StarxUser newUser = new StarxUser(
          uuid,
          username,
          email,
          PasswordHasher.hash(password),
          null,
          false,
          now,
          null,
          externalUserId,
          List.of(),
          null,
          sourceSystem,
          "completed",
          now,
          null,
          null,
          null,
          0L,
          null,
          false);
      userRepository.create(newUser);
      LOGGER.log(Level.INFO, "User {0} created from UniAuth", username);
      return new BridgeResult(true, "Login successful (created from UniAuth)", newUser, false, true);
    } catch (Exception exception) {
      LOGGER.log(Level.WARNING,
          "Failed to create user " + username + " from UniAuth", exception);
      return new BridgeResult(
          false,
          "本地账号创建失败，请稍后重试",
          null,
          false);
    }
  }

  private void synchronizeProfile(
      UUID uuid,
      StarxUser existing,
      UniAuthClient.PlayerProfileResponse profile) {
    if (profile == null) {
      return;
    }
    if (!hasProfileIdentity(profile)) {
      LOGGER.log(Level.WARNING,
          "Skipping UniAuth profile synchronization for {0}: player identity is missing",
          existing.username());
      return;
    }
    UniAuthConfig.ProfileSyncConfig sync = config.profileSync();
    if (!sync.enabled()) {
      return;
    }

    String remoteEmail = normalizeProfileEmail(profile.email());
    String localEmail = clean(existing.email());
    boolean updateEmail = sync.syncEmail()
        && remoteEmail != null
        && (localEmail == null || sync.overwriteLocalValues())
        && !remoteEmail.equalsIgnoreCase(Objects.requireNonNullElse(localEmail, ""));
    String remoteId = clean(profile.externalUserId());
    String localId = clean(existing.externalUserId());
    boolean updateExternalId = sync.syncExternalUserId()
        && remoteId != null
        && (localId == null || sync.overwriteLocalValues());
    if (updateEmail || updateExternalId) {
      try {
        userRepository.synchronizeProfile(
            uuid,
            updateEmail ? remoteEmail : existing.email(),
            updateExternalId ? remoteId : localId,
            sync.sourceSystem(),
            updateEmail);
      } catch (JdbcUserRepository.ExternalIdentityConflictException conflict) {
        if (updateEmail && !updateExternalId) {
          LOGGER.log(Level.WARNING,
              "UniAuth email synchronization skipped for {0}: email belongs to another user",
              existing.username());
          return;
        }
        throw conflict;
      }
    } else if (remoteId != null && remoteId.equals(localId)) {
      userRepository.updateSourceSystem(uuid, sync.sourceSystem());
    }
  }

  private static String clean(String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim();
    return normalized.isBlank() ? null : normalized;
  }

  static String normalizeProfileEmail(String value) {
    String email = clean(value);
    if (email == null) return null;
    try {
      return EmailAddress.normalize(email);
    } catch (IllegalArgumentException error) {
      return null;
    }
  }

  private static boolean hasLocalPassword(StarxUser user) {
    String passwordHash = user.passwordHash();
    return passwordHash != null && !passwordHash.isBlank();
  }

  private Optional<StarxUser> resolveAccount(UUID uuid, String username) {
    Optional<StarxUser> byUuid = userRepository.findFullByUuid(uuid);
    if (byUuid.isPresent()) {
      return byUuid;
    }
    for (UUID knownUuid : knownMinecraftUuids(uuid)) {
      if (knownUuid.equals(uuid)) {
        continue;
      }
      Optional<StarxUser> byAlias = userRepository.findFullByUuid(knownUuid);
      if (byAlias.isPresent()) {
        return byAlias;
      }
    }
    if (username == null || username.isBlank()) {
      return Optional.empty();
    }
    return usernameResolver.apply(username)
        .filter(account -> isOfflineUuidAlias(uuid, username, account)
            || knownMinecraftUuids(uuid).contains(account.uuid()));
  }

  private Set<UUID> knownMinecraftUuids(UUID requestedUuid) {
    Set<UUID> resolved = this.minecraftIdentityResolver.apply(requestedUuid);
    if (resolved == null || resolved.isEmpty()) {
      return Set.of(requestedUuid);
    }
    LinkedHashSet<UUID> known = new LinkedHashSet<>();
    known.add(requestedUuid);
    for (UUID uuid : resolved) {
      known.add(Objects.requireNonNull(uuid, "resolved uuid"));
    }
    return Set.copyOf(known);
  }

  private static String safeMessage(Throwable throwable) {
    Throwable current = throwable;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    String message = current.getMessage();
    return message == null || message.isBlank()
        ? current.getClass().getSimpleName() : message;
  }

  public record BridgeResult(
      boolean success,
      String message,
      StarxUser user,
      boolean serviceUnavailable,
      boolean provisionedAccount) {
    public BridgeResult(boolean success, String message, StarxUser user) {
      this(success, message, user, false, false);
    }

    public BridgeResult(
        boolean success,
        String message,
        StarxUser user,
        boolean serviceUnavailable) {
      this(success, message, user, serviceUnavailable, false);
    }
  }
}
