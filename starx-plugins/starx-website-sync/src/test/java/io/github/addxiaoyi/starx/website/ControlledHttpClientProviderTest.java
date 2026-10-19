package io.github.addxiaoyi.starx.website;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Test;

class ControlledHttpClientProviderTest {
  @Test
  @Timeout(15)
  void rejectsASecondRequestWhenTheConcurrencyBudgetIsOccupied() throws Exception {
    CountDownLatch requestStarted = new CountDownLatch(1);
    CountDownLatch releaseRequest = new CountDownLatch(1);
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> respond(exchange, requestStarted, releaseRequest));
    server.start();

    try {
      ControlledHttpClientProvider provider = new ControlledHttpClientProvider(
          1, Duration.ofSeconds(1), Duration.ofSeconds(2));
      URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
      HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
      try (var executor = Executors.newSingleThreadExecutor()) {
        Future<HttpResponse<String>> first = executor.submit(
            () -> provider.send(request, HttpResponse.BodyHandlers.ofString()));
        assertTrue(requestStarted.await(5, TimeUnit.SECONDS));
        assertThrows(IOException.class,
            () -> provider.send(request, HttpResponse.BodyHandlers.ofString()));
        assertTrue(provider.rejectedRequests() >= 1);
        releaseRequest.countDown();
        first.get(2, TimeUnit.SECONDS);
      }
    } finally {
      releaseRequest.countDown();
      server.stop(0);
    }
  }

  private static void respond(
      HttpExchange exchange,
      CountDownLatch requestStarted,
      CountDownLatch releaseRequest
  ) throws IOException {
    requestStarted.countDown();
    try {
      releaseRequest.await(10, TimeUnit.SECONDS);
      byte[] body = "ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    } finally {
      exchange.close();
    }
  }
}
