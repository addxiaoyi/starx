package io.github.addxiaoyi.starx.velocity;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;

final class TopLevelResourceLifecycleContractTest {

  @Test
  void eventBusAndWebhookSubscriptionsBelongToPluginLifecycle() throws Exception {
    String source = Files.readString(ProjectPaths.velocityProject().resolve(
        "src/main/java/io/github/addxiaoyi/starx/velocity/StarxVelocityPlugin.java"));

    assertTrue(source.contains("lifecycle.own(\"event bus\", this.eventBus::close)"));
    assertTrue(source.contains("WebhookEventPublisher webhookPublisher"));
    assertTrue(source.contains("lifecycle.own(\"webhook publisher\", webhookPublisher::close)"));
  }
}
