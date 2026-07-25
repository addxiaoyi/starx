package io.github.addxiaoyi.starx.velocity.module.welcome;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WelcomeModuleLifecycleContractTest {
  @Test
  void listenerIsOwnedAndUnregistered() throws Exception {
    String source = Files.readString(Path.of(
        "src/main/java/io/github/addxiaoyi/starx/velocity/module/welcome/WelcomeModule.java"));

    assertTrue(source.contains("private WelcomeListener listener;"));
    assertTrue(source.contains("unregisterListener(this.plugin, currentListener)"));
  }
}
