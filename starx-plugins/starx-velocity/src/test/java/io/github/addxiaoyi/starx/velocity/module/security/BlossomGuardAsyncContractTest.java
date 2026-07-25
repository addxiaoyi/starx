package io.github.addxiaoyi.starx.velocity.module.security;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BlossomGuardAsyncContractTest {
  @Test
  void externalRiskLookupRunsAsAnEventTask() throws Exception {
    String source = Files.readString(Path.of(
        "src/main/java/io/github/addxiaoyi/starx/velocity/module/security/BlossomGuardModule.java"));

    assertTrue(source.contains("EventTask onLogin(LoginEvent event)"));
    assertTrue(source.contains("EventTask.async"));
  }
}
