package io.github.addxiaoyi.starx.website;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class WebsiteSyncHttpPressureTest {

  @Test
  void emitsOnlySaturationAndRecoveryEdgesForRejectedHttpConcurrency() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    CountDownLatch firstRequestStarted = new CountDownLatch(1);
    CountDownLatch releaseFirstRequest = new CountDownLatch(1);
    AtomicInteger handledRequests = new AtomicInteger();
    server.createContext("/api/v1/plugin/heartbeat", exchange -> {
      handledRequests.incrementAndGet();
      firstRequestStarted.countDown();
      try {
        releaseFirstRequest.await(3, TimeUnit.SECONDS);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
      }
      byte[] body = "{\"nodeId\":\"proxy-1\",\"receivedAt\":\"2026-10-21T00:00:00Z\"}"
          .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();

    ControlledHttpClientProvider provider = new ControlledHttpClientProvider(
        1, Duration.ofSeconds(1), Duration.ofSeconds(4));
    WebsiteSyncHttpClient client = new WebsiteSyncHttpClient(
        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
        Duration.ofSeconds(4),
        null,
        provider,
        new Gson(),
        new SyncMetrics(),
        new CircuitBreaker(10, Duration.ofSeconds(30)),
        true,
        true);
    List<WebsiteSyncHttpClient.TransportPressureChange> changes =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    client.setTransportPressureListener(changes::add);
    var executor = Executors.newSingleThreadExecutor();
    try {
      var first = executor.submit(() -> client.heartbeat(
          SecretValue.of("local-test-token"),
          "proxy-1",
          List.of(NodeCapabilities.NETWORK_STATUS),
          new NodeSnapshot("test", null, 0, 10, null, null, false, List.of())));
      assertTrue(firstRequestStarted.await(2, TimeUnit.SECONDS));

      assertThrows(WebsiteSyncApiException.class, () -> client.heartbeat(
          SecretValue.of("local-test-token"),
          "proxy-1",
          List.of(NodeCapabilities.NETWORK_STATUS),
          new NodeSnapshot("test", null, 0, 10, null, null, false, List.of())));

      releaseFirstRequest.countDown();
      first.get(3, TimeUnit.SECONDS);

      assertEquals(1, handledRequests.get());
      assertEquals(2, changes.size());
      assertTrue(changes.get(0).saturated());
      assertEquals(1, changes.get(0).metrics().get("activeRequests"));
      assertTrue(!changes.get(1).saturated());
      assertEquals(0, changes.get(1).metrics().get("activeRequests"));
    } finally {
      releaseFirstRequest.countDown();
      executor.shutdownNow();
      server.stop(0);
    }
  }
}
