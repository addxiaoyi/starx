package io.github.addxiaoyi.starx.velocity.module;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ListenerLifecycleContractTest {
  @Test
  void sessionAndQqListenersAreUnregistered() throws Exception {
    assertOwnedListener(
        "src/main/java/io/github/addxiaoyi/starx/velocity/module/session/PlayerSessionModule.java",
        "private Listener listener;");
    assertOwnedListener(
        "src/main/java/io/github/addxiaoyi/starx/velocity/module/integrations/QqIntegrationModule.java",
        "private ChatListener listener;");
  }

  @Test
  void modulesNeverUnregisterEveryListenerOwnedByThePlugin() throws Exception {
    Path modules = Path.of("src/main/java/io/github/addxiaoyi/starx/velocity/module");
    try (var files = Files.walk(modules)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
        String source = Files.readString(file);
        assertFalse(source.contains("unregisterListeners("), file.toString());
      }
    }
  }

  @Test
  void everyComponentThatRegistersVelocityListenersAlsoReleasesThem() throws Exception {
    Path velocity = Path.of("src/main/java/io/github/addxiaoyi/starx/velocity");
    try (var files = Files.walk(velocity)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
        String source = Files.readString(file);
        if (source.contains("getEventManager().register")) {
          assertTrue(source.contains("unregisterListener"), file.toString());
        }
      }
    }
  }

  @Test
  void everyModuleThatSubscribesToTheEventBusAlsoUnsubscribes() throws Exception {
    Path modules = Path.of("src/main/java/io/github/addxiaoyi/starx/velocity/module");
    try (var files = Files.walk(modules)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
        String source = Files.readString(file);
        if (source.contains("eventBus.subscribe(")) {
          assertTrue(source.contains("eventBus.unsubscribe("), file.toString());
        }
      }
    }
  }

  @Test
  void smartRateLimitOwnsAndUnregistersItsListener() throws Exception {
    assertOwnedListener(
        "src/main/java/io/github/addxiaoyi/starx/velocity/module/security/SmartRateLimitModule.java",
        "private PingListener listener;");
  }

  @Test
  void securityModulesOwnAndUnregisterTheirListeners() throws Exception {
    assertOwnedListener(
        "src/main/java/io/github/addxiaoyi/starx/velocity/module/security/AnticheatModule.java",
        "private LoginListener loginListener;");
    assertOwnedListener(
        "src/main/java/io/github/addxiaoyi/starx/velocity/module/security/BotFilterModule.java",
        "private PingListener pingListener;");
    assertOwnedListener(
        "src/main/java/io/github/addxiaoyi/starx/velocity/module/security/CrashFixModule.java",
        "private PluginMessageListener listener;");
    assertOwnedListener(
        "src/main/java/io/github/addxiaoyi/starx/velocity/module/security/RiskModule.java",
        "private LoginListener listener;");
  }

  private static void assertOwnedListener(String path, String field) throws Exception {
    String source = Files.readString(Path.of(path));
    assertTrue(source.contains(field), path);
    assertTrue(source.contains("unregisterListener"), path);
  }
}
