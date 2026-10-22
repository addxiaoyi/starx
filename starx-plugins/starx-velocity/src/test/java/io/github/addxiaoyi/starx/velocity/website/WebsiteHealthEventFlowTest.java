package io.github.addxiaoyi.starx.velocity.website;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import io.github.addxiaoyi.starx.velocity.config.StarxConfig;
import io.github.addxiaoyi.starx.velocity.event.VelocityEventBus;
import io.github.addxiaoyi.starx.velocity.http.WebhookClient;
import io.github.addxiaoyi.starx.velocity.http.WebhookEventPublisher;
import io.github.addxiaoyi.starx.velocity.operations.IncidentTimeline;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class WebsiteHealthEventFlowTest {

  @Test
  void sendsOneCorrelatedFailureAndRecoveryToTimelineAndWebhook() throws Exception {
    VelocityEventBus eventBus = new VelocityEventBus();
    IncidentTimeline timeline = new IncidentTimeline(8, 8);
    List<String> successfulWebhookBodies = new CopyOnWriteArrayList<>();
    Map<String, AtomicInteger> attemptsByEvent = new ConcurrentHashMap<>();
    AtomicInteger totalAttempts = new AtomicInteger();
    CountDownLatch webhooksDelivered = new CountDownLatch(2);
    WebhookClient webhook = new WebhookClient(
        new StarxConfig.WebhookConfig("https://hooks.example.test/starx", "secret"),
        body -> "signed",
        (url, body, headers) -> {
          totalAttempts.incrementAndGet();
          var envelope = JsonParser.parseString(body).getAsJsonObject();
          String eventId = envelope.get("eventId").getAsString();
          String severity = envelope.getAsJsonObject("data").get("severity").getAsString();
          int attempt = attemptsByEvent.computeIfAbsent(eventId, ignored -> new AtomicInteger())
              .incrementAndGet();
          if ("WARNING".equals(severity) && attempt < 3) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                new IOException("simulated transient webhook failure"));
          }
          successfulWebhookBodies.add(body);
          webhooksDelivered.countDown();
          return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
    WebhookEventPublisher webhookPublisher = new WebhookEventPublisher(eventBus, webhook);
    eventBus.subscribeAll(timeline::append);
    webhookPublisher.register();

    try {
      WebsiteHealthEventPublisher health = new WebsiteHealthEventPublisher(
          eventBus, "proxy-a", ignored -> { });
      health.publish("runtime", "ACTIVE", "BACKOFF", "transport_error",
          Map.of("queueSize", 0, "rejectedTasks", 0));
      health.publish("runtime", "BACKOFF", "BACKOFF", "transport_error",
          Map.of("queueSize", 0, "rejectedTasks", 1));
      health.publish("runtime", "BACKOFF", "ACTIVE", "",
          Map.of("queueSize", 0, "rejectedTasks", 1));
      health.publish("runtime", "ACTIVE", "STOPPED", "",
          Map.of("queueSize", 0, "rejectedTasks", 1));

      assertTrue(webhooksDelivered.await(3, TimeUnit.SECONDS));
      String correlationId = JsonParser.parseString(successfulWebhookBodies.getFirst())
          .getAsJsonObject().getAsJsonObject("data").get("correlationId").getAsString();
      IncidentTimeline.Trace trace = timeline.get(java.util.UUID.fromString(correlationId))
          .orElseThrow();

      assertEquals(2, trace.events().size());
      assertEquals("website:health:changed", trace.events().getFirst().type());
      assertTrue(trace.events().stream().anyMatch(
          event -> "WARNING".equals(event.payload().get("severity"))));
      assertTrue(trace.events().stream().anyMatch(
          event -> "RECOVERED".equals(event.payload().get("severity"))));
      assertEquals(2, successfulWebhookBodies.size());
      assertEquals(4, totalAttempts.get());
      assertEquals(2, attemptsByEvent.size());
      assertTrue(successfulWebhookBodies.stream().allMatch(body -> body.contains(correlationId)));
    } finally {
      webhookPublisher.close();
      eventBus.close();
    }
  }
}
