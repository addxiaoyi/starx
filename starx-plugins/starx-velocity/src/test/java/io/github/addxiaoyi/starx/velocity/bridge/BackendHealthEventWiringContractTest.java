package io.github.addxiaoyi.starx.velocity.bridge;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class BackendHealthEventWiringContractTest {

  @Test
  void publishesHealthTransitionsAndForwardsThemToWebhookDelivery() throws IOException {
    String bridge = Files.readString(source("bridge/VelocityBackendBridge.java"));
    String publisher = Files.readString(source("http/WebhookEventPublisher.java"));

    assertTrue(bridge.contains("this.publishHealthTransition(name, previous, current, node)"));
    assertTrue(bridge.contains("this.publishHealthTransition(registeredName, previous, current, node)"));
    assertTrue(bridge.contains("this.eventBus.publish(event)"));
    assertTrue(publisher.contains("\"backend:health:changed\""));
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
