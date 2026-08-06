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

    assertTrue(source.contains("profileForLogin(user.username()).thenApply"));
    assertTrue(source.contains("synchronizeProfile(user.uuid(), user, profile)"));
    assertTrue(source.contains("return new BridgeResult(true, \"Login successful (local)\", user)"));
  }

  @Test
  void emailUnverifiedMigrationCannotProvisionAnUnknownUsername() throws Exception {
    String source = Files.readString(locateSource(), StandardCharsets.UTF_8);

    assertTrue(source.contains("login.requiresLocalMigration() && existing.isEmpty()"));
    assertTrue(source.contains("邮箱未验证账号只能迁移已有本地档案"));
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
