package io.github.addxiaoyi.starx.velocity.http.admin;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CrossDeviceApprovalWiringContractTest {
  @Test
  void runtimeRegistersOneSharedApprovalServiceWithConfiguredWebsiteOrigin() throws IOException {
    String api = Files.readString(source("http/HttpApiServer.java"));
    String plugin = Files.readString(source("StarxVelocityPlugin.java"));

    assertFalse(api.contains("new CrossDeviceApprovalService()"));
    assertTrue(plugin.contains("new CrossDeviceApprovalService("));
    assertTrue(plugin.contains("bindingChallenges,"));
    assertTrue(plugin.contains("accountIdentities::accountId"));
    assertTrue(plugin.contains("accountIdentities::minecraftUuid"));
    assertTrue(plugin.contains("accountIdentities::username"));
    assertTrue(api.contains("new CrossDeviceApprovalHandler("));
    assertTrue(api.contains("this.crossDeviceApprovals,"));
    assertTrue(api.contains("WebsiteOriginResolver.fromWebhook(this.config.webhook())"));
    assertTrue(api.contains(".register(this, sensitiveAuth)"));
  }

  private static Path source(String file) {
    Path current = Path.of("").toAbsolutePath();
    for (int i = 0; i < 8 && current != null; i++, current = current.getParent()) {
      Path candidate = current.resolve(
          "starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/" + file);
      if (Files.isRegularFile(candidate)) return candidate;
    }
    throw new IllegalStateException("Velocity source is unavailable: " + file);
  }
}
