package io.github.addxiaoyi.starx.common.auth.uniauth;

import io.github.addxiaoyi.starx.common.crypto.PasswordHasher;
import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import io.github.addxiaoyi.starx.common.model.StarxUser;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class UniAuthBridge {
  private static final Logger LOGGER = Logger.getLogger(UniAuthBridge.class.getName());
  private static final String LEGACY_SOURCE_SYSTEM = "starvc";

  private final UniAuthConfig config;
  private final UniAuthClient client;
  private final JdbcUserRepository userRepository;

  public UniAuthBridge(
      UniAuthConfig config,
      UniAuthClient client,
      JdbcUserRepository userRepository) {
    this.config = Objects.requireNonNull(config, "config");
    this.client = Objects.requireNonNull(client, "client");
    this.userRepository = Objects.requireNonNull(userRepository, "userRepository");
  }

  public CompletableFuture<BridgeResult> authenticate(
      UUID uuid,
      String username,
      String password) {
    Optional<StarxUser> existing = resolveAccount(uuid, username);
    if (existing.isPresent() && hasLocalPassword(existing.get())) {
      return authenticateLocally(existing.get(), password);
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
      return true;
    }
    String profileUsername = clean(profile.username());
    String profileUuid = clean(profile.uuid());
    if (profileUsername == null && profileUuid == null) {
      return true;
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

  static boolean isOfflineUuidAlias(UUID connectionUuid, String username, StarxUser account) {
    if (connectionUuid == null || username == null || username.isBlank() || account == null
        || !username.equalsIgnoreCase(account.username())) {
      return false;
    }
    UUID offlineUuid = UUID.nameUUIDFromBytes(
        ("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
    return offlineUuid.equals(account.uuid());
  }

  private CompletableFuture<BridgeResult> authenticateLocally(
      StarxUser user,
      String password) {
    if (!PasswordHasher.verify(password, user.passwordHash())) {
      return CompletableFuture.completedFuture(
          new BridgeResult(false, "Invalid password", null));
    }
    return profileForLogin(user.username()).thenApply(profile -> {
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
    try {
      UUID targetUuid = existing.uuid();
      userRepository.markPasswordMigrated(
          targetUuid, PasswordHasher.hash(password), Instant.now());
      synchronizeProfile(targetUuid, existing, profile);
      StarxUser updated = userRepository.findFullByUuid(targetUuid).orElse(existing);
      LOGGER.log(Level.INFO, "User {0} migrated from UniAuth to local auth", username);
      return new BridgeResult(true, "Login successful (migrated from UniAuth)", updated);
    } catch (Exception exception) {
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
          ? clean(profile.email()) : clean(login.email());
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
      return new BridgeResult(true, "Login successful (created from UniAuth)", newUser);
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
    UniAuthConfig.ProfileSyncConfig sync = config.profileSync();
    if (!sync.enabled()) {
      return;
    }

    if (sync.syncEmail()) {
      String remoteEmail = clean(profile.email());
      String localEmail = clean(existing.email());
      if (remoteEmail != null
        && (localEmail == null || sync.overwriteLocalValues())
        && !remoteEmail.equalsIgnoreCase(Objects.requireNonNullElse(localEmail, ""))) {
        if (!userRepository.tryUpdateEmail(uuid, remoteEmail)) {
          LOGGER.log(Level.WARNING,
              "UniAuth email synchronization skipped for {0}: email belongs to another user",
              existing.username());
        }
      }
    }

    if (sync.syncExternalUserId()) {
      String remoteId = clean(profile.externalUserId());
      String localId = clean(existing.externalUserId());
      if (remoteId != null && (localId == null || sync.overwriteLocalValues())) {
        userRepository.updateExternalIdentity(uuid, remoteId, sync.sourceSystem());
      } else if (remoteId != null && remoteId.equals(localId)) {
      userRepository.updateSourceSystem(uuid, sync.sourceSystem());
      }
    }
  }

  private static String clean(String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim();
    return normalized.isBlank() ? null : normalized;
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
    if (username == null || username.isBlank()) {
      return Optional.empty();
    }
    return userRepository.findFullByUsername(username)
        .filter(account -> isOfflineUuidAlias(uuid, username, account));
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
      boolean serviceUnavailable) {
    public BridgeResult(boolean success, String message, StarxUser user) {
      this(success, message, user, false);
    }
  }
}
