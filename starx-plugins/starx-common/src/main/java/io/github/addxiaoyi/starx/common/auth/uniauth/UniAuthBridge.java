package io.github.addxiaoyi.starx.common.auth.uniauth;

import io.github.addxiaoyi.starx.common.crypto.PasswordHasher;
import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import io.github.addxiaoyi.starx.common.model.StarxUser;
import java.time.Instant;
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
    Optional<StarxUser> existing = userRepository.findFullByUsername(username);
    if (existing.isPresent()
        && "completed".equals(existing.get().migrationState())
        && existing.get().passwordHash() != null) {
      return authenticateLocally(existing.get(), password);
    }

    return client.login(username, password).thenCompose(login -> {
      if (!login.success()) {
        return CompletableFuture.completedFuture(new BridgeResult(
            false,
            login.message() == null ? "Authentication failed" : login.message(),
            null));
      }
      return profileForLogin(username).thenApply(profile -> existing.isPresent()
          ? migrateExisting(existing.get(), username, password, profile)
          : createFromUniAuth(uuid, username, password, login, profile));
    });
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
      userRepository.updatePassword(targetUuid, PasswordHasher.hash(password));
      userRepository.updateMigrationState(targetUuid, "completed");
      userRepository.updatePasswordMigratedAt(targetUuid, Instant.now());
      synchronizeProfile(targetUuid, existing, profile);
      StarxUser updated = userRepository.findFullByUuid(targetUuid).orElse(existing);
      LOGGER.log(Level.INFO, "User {0} migrated from UniAuth to local auth", username);
      return new BridgeResult(true, "Login successful (migrated from UniAuth)", updated);
    } catch (Exception exception) {
      LOGGER.log(Level.WARNING,
          "Failed to migrate user " + username + " to local auth", exception);
      return new BridgeResult(
          true,
          "Login successful (from UniAuth, local migration failed)",
          existing);
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
          true,
          "Login successful (from UniAuth, user creation failed)",
          null);
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

  private static String safeMessage(Throwable throwable) {
    Throwable current = throwable;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    String message = current.getMessage();
    return message == null || message.isBlank()
        ? current.getClass().getSimpleName() : message;
  }

  public record BridgeResult(boolean success, String message, StarxUser user) {}
}
