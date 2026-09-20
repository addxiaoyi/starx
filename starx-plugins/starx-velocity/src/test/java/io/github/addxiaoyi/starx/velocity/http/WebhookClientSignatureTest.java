package io.github.addxiaoyi.starx.velocity.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.api.dto.WebhookPayload;
import io.github.addxiaoyi.starx.common.crypto.HmacRequestSigner;
import io.github.addxiaoyi.starx.velocity.config.StarxConfig;
import io.github.addxiaoyi.starx.velocity.security.HmacWebhookSigner;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.ArrayList;
import java.util.List;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

class WebhookClientSignatureTest {
  @TempDir Path tempDir;
  @Test
  void signsMethodTargetTimestampAndExactBody() {
    RecordingTransport transport = new RecordingTransport();
    Clock clock = Clock.fixed(Instant.parse("2026-07-23T12:00:00Z"), ZoneOffset.UTC);
    String url = "https://star-web.top/api/v1/plugin/events?node=max";
    WebhookClient client = new WebhookClient(
        new StarxConfig.WebhookConfig(url, "secret"),
        new HmacWebhookSigner("secret"), transport, clock);
    WebhookPayload payload = new WebhookPayload(
        UUID.fromString("00000000-0000-0000-0000-000000000001"),
        "player:login", clock.instant(), Map.of("player", "Alex"));

    client.send(payload).join();

    String timestamp = transport.headers.get(WebhookClient.TIMESTAMP_HEADER);
    String signature = transport.headers.get(WebhookClient.SIGNATURE_HEADER);
    assertEquals(String.valueOf(clock.millis()), timestamp);
    assertTrue(HmacRequestSigner.verify(
        "secret", "POST", "/api/v1/plugin/events?node=max", timestamp,
        transport.body, signature));
  }

  @Test
  void signsGenericWebhookPosts() {
    RecordingTransport transport = new RecordingTransport();
    Clock clock = Clock.fixed(Instant.parse("2026-07-23T12:00:00Z"), ZoneOffset.UTC);
    String url = "https://star-web.top/api/v1/plugin/callback?channel=qq";
    WebhookClient client = new WebhookClient(
        new StarxConfig.WebhookConfig(url, "secret"),
        new HmacWebhookSigner("secret"), transport, clock);

    client.post(url, Map.of("message", "hello")).join();

    String timestamp = transport.headers.get(WebhookClient.TIMESTAMP_HEADER);
    assertTrue(HmacRequestSigner.verify(
        "secret", "POST", "/api/v1/plugin/callback?channel=qq", timestamp,
        transport.body, transport.headers.get(WebhookClient.SIGNATURE_HEADER)));
  }

  @Test
  void retriesTransientFailuresWithFreshSignatures() {
    RetryingTransport transport = new RetryingTransport(2, null);
    IncrementingClock clock = new IncrementingClock(1_784_808_000_000L);
    String url = "https://star-web.top/api/v1/plugin/callback";
    WebhookClient client = new WebhookClient(
        new StarxConfig.WebhookConfig(url, "secret"),
        new HmacWebhookSigner("secret"), transport, clock, 3, 0);

    client.post(url, Map.of("message", "hello")).join();

    assertEquals(3, transport.headers.size());
    assertEquals(3, transport.headers.stream()
        .map(headers -> headers.get(WebhookClient.SIGNATURE_HEADER))
        .distinct().count());
  }

  @Test
  void doesNotRetryPermanentHttpFailure() {
    RetryingTransport transport = new RetryingTransport(
        0, new WebhookDeliveryException(400));
    WebhookClient client = new WebhookClient(
        new StarxConfig.WebhookConfig("https://star-web.top/hook", "secret"),
        new HmacWebhookSigner("secret"), transport,
        Clock.fixed(Instant.parse("2026-07-23T12:00:00Z"), ZoneOffset.UTC), 3, 0);

    try {
      client.post("https://star-web.top/hook", Map.of()).join();
    } catch (RuntimeException ignored) {
      // The assertion is the number of attempted deliveries.
    }

    assertEquals(1, transport.headers.size());
  }

  @Test
  void failedEventIsReplayedFromDiskAndAcknowledged() {
    String url = "https://star-web.top/api/v1/plugin/events";
    StarxConfig.WebhookConfig config = new StarxConfig.WebhookConfig(url, "secret");
    FileWebhookOutbox outbox = new FileWebhookOutbox(tempDir.resolve("outbox.json"));
    RetryingTransport failing = new RetryingTransport(
        0, new WebhookDeliveryException(503));
    WebhookClient first = new WebhookClient(
        config, new HmacWebhookSigner("secret"), failing, Clock.systemUTC(), 1, 0, outbox);
    WebhookPayload payload = new WebhookPayload(
        UUID.fromString("00000000-0000-0000-0000-000000000002"),
        "player:login:success", Instant.parse("2026-07-23T12:00:00Z"), Map.of());

    assertThrows(RuntimeException.class, () -> first.send(payload).join());
    assertEquals(1, new FileWebhookOutbox(tempDir.resolve("outbox.json")).pending().size());

    RecordingTransport recovered = new RecordingTransport();
    WebhookClient restarted = new WebhookClient(
        config, new HmacWebhookSigner("secret"), recovered, Clock.systemUTC(), 1, 0,
        new FileWebhookOutbox(tempDir.resolve("outbox.json")));
    restarted.replayPending().join();

    assertTrue(new FileWebhookOutbox(tempDir.resolve("outbox.json")).pending().isEmpty());
    assertTrue(recovered.body.contains("00000000-0000-0000-0000-000000000002"));
  }

  @Test
  void replayContinuesPastPermanentlyRejectedEvent() {
    String url = "https://star-web.top/api/v1/plugin/events";
    FileWebhookOutbox outbox = new FileWebhookOutbox(tempDir.resolve("ordered-outbox.json"));
    PendingWebhook rejected = outbox.enqueue(url, "{\"eventId\":\"reject\"}", 1L);
    PendingWebhook accepted = outbox.enqueue(url, "{\"eventId\":\"accept\"}", 2L);
    SelectiveTransport transport = new SelectiveTransport();
    WebhookClient client = new WebhookClient(
        new StarxConfig.WebhookConfig(url, "secret"), new HmacWebhookSigner("secret"),
        transport, Clock.systemUTC(), 1, 0, outbox);

    assertThrows(RuntimeException.class, () -> client.replayPending().join());

    assertEquals(List.of(rejected), outbox.pending());
    assertTrue(transport.bodies.contains(accepted.body()));
  }

  private static final class RecordingTransport implements WebhookHttpTransport {
    private String body;
    private Map<String, String> headers;

    @Override
    public CompletableFuture<Void> post(
        String url, String body, Map<String, String> headers) {
      this.body = body;
      this.headers = headers;
      return CompletableFuture.completedFuture(null);
    }
  }

  private static final class RetryingTransport implements WebhookHttpTransport {
    private final int transientFailures;
    private final RuntimeException permanentFailure;
    private final List<Map<String, String>> headers = new ArrayList<>();

    private RetryingTransport(int transientFailures, RuntimeException permanentFailure) {
      this.transientFailures = transientFailures;
      this.permanentFailure = permanentFailure;
    }

    @Override
    public CompletableFuture<Void> post(
        String url, String body, Map<String, String> requestHeaders) {
      headers.add(requestHeaders);
      if (permanentFailure != null) return CompletableFuture.failedFuture(permanentFailure);
      if (headers.size() <= transientFailures) {
        return CompletableFuture.failedFuture(new IOException("temporary"));
      }
      return CompletableFuture.completedFuture(null);
    }
  }

  private static final class IncrementingClock extends Clock {
    private long millis;

    private IncrementingClock(long millis) {
      this.millis = millis;
    }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return Instant.ofEpochMilli(millis()); }
    @Override public long millis() { return millis++; }
  }

  private static final class SelectiveTransport implements WebhookHttpTransport {
    private final List<String> bodies = new ArrayList<>();

    @Override
    public CompletableFuture<Void> post(String url, String body, Map<String, String> headers) {
      bodies.add(body);
      if (body.contains("reject")) {
        return CompletableFuture.failedFuture(new WebhookDeliveryException(400));
      }
      return CompletableFuture.completedFuture(null);
    }
  }
}
