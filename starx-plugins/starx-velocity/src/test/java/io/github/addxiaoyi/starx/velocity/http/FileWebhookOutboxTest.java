package io.github.addxiaoyi.starx.velocity.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileWebhookOutboxTest {
  @TempDir Path tempDir;

  @Test
  void pendingDeliverySurvivesRestartAndAckRemovesIt() {
    Path file = tempDir.resolve("webhook-outbox.json");
    FileWebhookOutbox first = new FileWebhookOutbox(file);
    PendingWebhook queued = first.enqueue("https://star-web.top/events", "{\"eventId\":\"1\"}", 100L);

    FileWebhookOutbox restarted = new FileWebhookOutbox(file);
    assertEquals(java.util.List.of(queued), restarted.pending());

    assertTrue(restarted.ack(queued.id()));
    assertTrue(new FileWebhookOutbox(file).pending().isEmpty());
  }

  @Test
  void corruptOutboxFailsLoudlyInsteadOfDroppingEvents() throws Exception {
    Path file = tempDir.resolve("webhook-outbox.json");
    Files.writeString(file, "not-json");

    IllegalStateException error = assertThrows(
        IllegalStateException.class, () -> new FileWebhookOutbox(file));
    assertTrue(error.getMessage().contains(file.toString()));
  }

  @Test
  void recoveryQuarantinesCorruptFileAndReturnsUsableOutbox() throws Exception {
    Path file = tempDir.resolve("webhook-outbox.json");
    Files.writeString(file, "not-json");

    WebhookOutboxRecovery recovery = WebhookOutboxRecovery.open(file, 1234L);

    assertTrue(recovery.recovered());
    assertTrue(recovery.error().isPresent());
    Path quarantine = recovery.quarantine().orElseThrow();
    assertTrue(Files.exists(quarantine));
    assertEquals("not-json", Files.readString(quarantine));
    assertTrue(recovery.outbox().pending().isEmpty());
    recovery.outbox().enqueue("https://star-web.top/events", "{}", 1235L);
    assertEquals(1, new FileWebhookOutbox(file).pending().size());
  }
}
