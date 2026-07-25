package io.github.addxiaoyi.starx.velocity.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.addxiaoyi.starx.api.event.EventBus;
import io.github.addxiaoyi.starx.api.event.StarxEvent;
import io.github.addxiaoyi.starx.velocity.config.StarxConfig;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

final class WebhookEventPublisherLifecycleTest {

  @Test
  void closeRemovesEveryWebhookSubscription() {
    RecordingBus bus = new RecordingBus();
    WebhookClient client = new WebhookClient(
        new StarxConfig.WebhookConfig("", ""),
        body -> "",
        (url, body, headers) -> java.util.concurrent.CompletableFuture.completedFuture(null));
    WebhookEventPublisher publisher = new WebhookEventPublisher(bus, client);
    publisher.register();

    publisher.close();

    assertEquals(0, bus.subscribers.size());
  }

  @Test
  void closeBeforeAsyncStartupPreventsLaterRegistration() {
    RecordingBus bus = new RecordingBus();
    WebhookClient client = new WebhookClient(
        new StarxConfig.WebhookConfig("", ""),
        body -> "",
        (url, body, headers) -> java.util.concurrent.CompletableFuture.completedFuture(null));
    WebhookEventPublisher publisher = new WebhookEventPublisher(bus, client);

    publisher.close();
    publisher.register();

    assertEquals(0, bus.subscribers.size());
  }

  private static final class RecordingBus implements EventBus {
    private final Map<String, Consumer<StarxEvent>> subscribers = new HashMap<>();
    @Override public void publish(StarxEvent event) { }
    @Override public void subscribe(String type, Consumer<StarxEvent> subscriber) {
      subscribers.put(type, subscriber);
    }
    @Override public void unsubscribe(String type, Consumer<StarxEvent> subscriber) {
      subscribers.remove(type, subscriber);
    }
  }
}
