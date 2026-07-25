/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.common.auth.uniauth;

import io.github.addxiaoyi.starx.common.auth.uniauth.UniAuthClient;
import io.github.addxiaoyi.starx.common.auth.uniauth.UniAuthConfig;
import io.github.addxiaoyi.starx.common.crypto.PasswordHasher;
import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import io.github.addxiaoyi.starx.common.model.StarxUser;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class UniAuthBridge {
    private static final Logger logger = Logger.getLogger(UniAuthBridge.class.getName());
    private static final String SOURCE_SYSTEM_STARVC = "starvc";
    private final UniAuthConfig config;
    private final UniAuthClient client;
    private final JdbcUserRepository userRepository;

    public UniAuthBridge(UniAuthConfig config, UniAuthClient client, JdbcUserRepository userRepository) {
        this.config = Objects.requireNonNull(config, "config");
        this.client = Objects.requireNonNull(client, "client");
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository");
    }

    public CompletableFuture<BridgeResult> authenticate(UUID uuid, String username, String password) {
        Optional<StarxUser> userOpt = this.userRepository.findFullByUsername(username);
        if (userOpt.isPresent()) {
            StarxUser user = userOpt.get();
            if ("completed".equals(user.migrationState()) && user.passwordHash() != null) {
                return this.authenticateLocally(user, password);
            }
            return this.authenticateWithUniAuthAndMigrate(uuid, username, password, user);
        }
        return this.authenticateWithUniAuthAndCreate(uuid, username, password);
    }

    private CompletableFuture<BridgeResult> authenticateLocally(StarxUser user, String password) {
        if (PasswordHasher.verify(password, user.passwordHash())) {
            return CompletableFuture.completedFuture(new BridgeResult(true, "Login successful (local)", user));
        }
        return CompletableFuture.completedFuture(new BridgeResult(false, "Invalid password", null));
    }

    private CompletableFuture<BridgeResult> authenticateWithUniAuthAndMigrate(UUID uuid, String username, String password, StarxUser existingUser) {
        return this.client.login(username, password).thenApply(response -> {
            if (response.success()) {
                try {
                    String hashedPassword = PasswordHasher.hash(password);
                    UUID targetUuid = existingUser.uuid();
                    this.userRepository.updatePassword(targetUuid, hashedPassword);
                    this.userRepository.updateMigrationState(targetUuid, "completed");
                    this.userRepository.updatePasswordMigratedAt(targetUuid, Instant.now());
                    Optional<StarxUser> updatedUserOpt = this.userRepository.findFullByUsername(username);
                    StarxUser updatedUser = updatedUserOpt.orElse(null);
                    logger.log(Level.INFO, "User {0} migrated from StarVC to local auth", username);
                    return new BridgeResult(true, "Login successful (migrated from StarVC)", updatedUser);
                }
                catch (Exception e) {
                    logger.log(Level.WARNING, "Failed to migrate user " + username + " to local auth", e);
                    return new BridgeResult(true, "Login successful (from StarVC, migration failed)", existingUser);
                }
            }
            return new BridgeResult(false, response.message() != null ? response.message() : "Authentication failed", null);
        });
    }

    private CompletableFuture<BridgeResult> authenticateWithUniAuthAndCreate(UUID uuid, String username, String password) {
        return this.client.login(username, password).thenApply(response -> {
            if (response.success()) {
                try {
                    String hashedPassword = PasswordHasher.hash(password);
                    String email = response.email();
                    StarxUser newUser = new StarxUser(uuid, username, email, hashedPassword, null, false, Instant.now(), null, null, null, null, SOURCE_SYSTEM_STARVC, "completed", Instant.now(), null, null, null, 0L, null, false);
                    this.userRepository.create(newUser);
                    logger.log(Level.INFO, "User {0} created from StarVC", username);
                    return new BridgeResult(true, "Login successful (created from StarVC)", newUser);
                }
                catch (Exception e) {
                    logger.log(Level.WARNING, "Failed to create user " + username + " from StarVC", e);
                    return new BridgeResult(true, "Login successful (from StarVC, user creation failed)", null);
                }
            }
            return new BridgeResult(false, response.message() != null ? response.message() : "Authentication failed", null);
        });
    }

    public record BridgeResult(boolean success, String message, StarxUser user) {
    }
}
