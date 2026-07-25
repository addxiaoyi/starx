package io.github.addxiaoyi.starx.velocity.module.security;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.velocity.ProjectPaths;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

final class SmartRateConnectionWiringContractTest {

  @Test
  void smartRateLimitRejectsConnectionsThroughLoginEvent() throws Exception {
    String source = Files.readString(ProjectPaths.velocityProject().resolve(
        "src/main/java/io/github/addxiaoyi/starx/velocity/module/security/SmartRateLimitModule.java"));

    assertTrue(source.contains("onLogin(LoginEvent event)"));
    assertTrue(source.contains("isConnectionRateLimited(ip)"));
    assertTrue(source.contains("ComponentResult.denied"));
    assertTrue(source.contains("private LoginListener loginListener;"));
  }
}
