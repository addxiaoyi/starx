package io.github.addxiaoyi.starx.velocity;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WebhookWiringContractTest {
  @Test
  void webhookClientExistsBeforeAnyModuleReceivesIt() throws Exception {
    String source = Files.readString(Path.of(
        "src/main/java/io/github/addxiaoyi/starx/velocity/StarxVelocityPlugin.java"));
    int clientCreation = source.indexOf("this.webhookClient = new WebhookClient");
    int qqModule = source.indexOf("new QqIntegrationModule(this, this.webhookClient");
    int publisher = source.indexOf("new WebhookEventPublisher(this.eventBus, this.webhookClient)");
    int replay = source.indexOf("this.webhookClient.replayPending()");
    int register = source.indexOf("webhookPublisher.register()");
    int modules = source.indexOf("this.moduleManager.enableAll()", register);

    assertTrue(clientCreation >= 0, "Webhook client creation is missing");
    assertTrue(qqModule > clientCreation, "QQ integration receives an uninitialized webhook client");
    assertTrue(publisher > clientCreation, "Webhook publisher receives an uninitialized webhook client");
    assertTrue(replay > publisher, "Persisted webhook events are not replayed during startup");
    assertTrue(replay > publisher && register > publisher && modules > register,
        "Live webhook subscription must begin before enabled modules can emit events");
    assertTrue(source.contains("this.dataDirectory.resolve(\"webhook-outbox.json\")"),
        "Webhook outbox is not stored in the plugin data directory");
  }
}
