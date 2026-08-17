package io.github.addxiaoyi.starx.common.auth.uniauth;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class UniAuthBridgeProfileSyncContractTest {
  @Test
  void successfulLocalLoginRefreshesProfileWithoutChangingAuthenticationOutcome()
      throws Exception {
    String source = Files.readString(locateSource(), StandardCharsets.UTF_8);

    assertTrue(source.contains("profileForLogin(username).thenApply"));
    assertTrue(source.contains("synchronizeProfile(user.uuid(), user, profile)"));
    assertTrue(source.contains("return new BridgeResult(true, \"Login successful (local)\", user)"));
  }

  @Test
  void localProfileSyncValidatesTheProfileAgainstThePersistedAccount() throws Exception {
    String source = Files.readString(locateSource(), StandardCharsets.UTF_8);
    int start = source.indexOf("private CompletableFuture<BridgeResult> authenticateLocally");
    int end = source.indexOf("private CompletableFuture<UniAuthClient.PlayerProfileResponse>", start);

    assertTrue(start >= 0 && end > start);
    assertTrue(source.substring(start, end).contains("isProfileIdentityCompatible"));
  }

  @Test
  void localProfileSyncUsesTheCurrentConnectionUuidForAliasValidation() throws Exception {
    String source = Files.readString(locateSource(), StandardCharsets.UTF_8);
    int authenticate = source.indexOf("authenticateLocally(existing.get(), password)");

    assertTrue(authenticate < 0);
    assertTrue(source.contains("authenticateLocally(uuid, username, existing.get(), password)"));
  }

  @Test
  void emailUnverifiedMigrationCannotProvisionAnUnknownUsername() throws Exception {
    String source = Files.readString(locateSource(), StandardCharsets.UTF_8);

    assertTrue(source.contains("login.requiresLocalMigration() && existing.isEmpty()"));
    assertTrue(source.contains("邮箱未验证账号只能迁移已有本地档案"));
  }

  @Test
  void failedProfileSyncRestoresOnlyItsOwnPasswordMigration() throws Exception {
    String source = Files.readString(locateSource(), StandardCharsets.UTF_8);
    int start = source.indexOf("private BridgeResult migrateExisting");
    int end = source.indexOf("private BridgeResult createFromUniAuth", start);

    assertTrue(start >= 0 && end > start);
    assertTrue(source.substring(start, end).contains("restorePasswordMigrationIfCurrent"));
  }

  private static Path locateSource() {
    Path current = Path.of("").toAbsolutePath();
    for (int depth = 0; depth < 8 && current != null; depth++) {
      Path candidate = current.resolve(
          "starx-plugins/starx-common/src/main/java/"
              + "io/github/addxiaoyi/starx/common/auth/uniauth/UniAuthBridge.java");
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("UniAuthBridge.java source is unavailable");
  }
}
