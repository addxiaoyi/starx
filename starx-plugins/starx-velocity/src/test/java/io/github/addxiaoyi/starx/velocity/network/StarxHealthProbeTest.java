package io.github.addxiaoyi.starx.velocity.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class StarxHealthProbeTest {

  @Test
  void acceptsOnlyAHealthyStarxPayload() throws Exception {
    try (TestServer server = server(200, "{\"status\":\"ok\",\"uptimeMillis\":1}")) {
      StarxHealthProbe.Result result = StarxHealthProbe.probe(
          "127.0.0.1", server.port(), Duration.ofSeconds(2));

      assertTrue(result.healthy());
      assertEquals(StarxHealthProbe.Status.HEALTHY, result.status());
      assertEquals(200, result.httpStatus());
      assertTrue(result.endpoint().endsWith("/v1/health"));
    }
  }

  @Test
  void rejectsRedirectWithoutFollowingIt() throws Exception {
    try (TestServer server = server(
        302,
        "",
        Map.of("Location", "http://127.0.0.1:1/v1/health"))) {
      StarxHealthProbe.Result result = StarxHealthProbe.probe(
          "127.0.0.1", server.port(), Duration.ofSeconds(2));

      assertFalse(result.healthy());
      assertEquals(StarxHealthProbe.Status.HTTP_STATUS_REJECTED, result.status());
      assertEquals(302, result.httpStatus());
    }
  }

  @Test
  void rejectsInvalidJsonAndNonOkStatus() throws Exception {
    try (TestServer invalidJson = server(200, "not-json")) {
      StarxHealthProbe.Result result = StarxHealthProbe.probe(
          "127.0.0.1", invalidJson.port(), Duration.ofSeconds(2));
      assertEquals(StarxHealthProbe.Status.INVALID_JSON, result.status());
    }

    try (TestServer wrongStatus = server(200, "{\"status\":\"degraded\"}")) {
      StarxHealthProbe.Result result = StarxHealthProbe.probe(
          "127.0.0.1", wrongStatus.port(), Duration.ofSeconds(2));
      assertEquals(StarxHealthProbe.Status.INVALID_STATUS, result.status());
    }
  }

  @Test
  void rejectsResponsesAboveTheBoundedLimit() throws Exception {
    String oversized = "{\"status\":\"ok\",\"padding\":\""
        + "x".repeat(17_000)
        + "\"}";
    try (TestServer server = server(200, oversized)) {
      StarxHealthProbe.Result result = StarxHealthProbe.probe(
          "127.0.0.1", server.port(), Duration.ofSeconds(2));

      assertEquals(StarxHealthProbe.Status.RESPONSE_TOO_LARGE, result.status());
    }
  }

  @Test
  void normalizesWildcardAndBracketsIpv6Hosts() {
    assertEquals(
        "http://127.0.0.1:8788/v1/health",
        StarxHealthProbe.endpoint("0.0.0.0", 8788));
    assertEquals(
        "http://[2001:db8::1]:8788/v1/health",
        StarxHealthProbe.endpoint("2001:db8::1", 8788));
  }

  private static TestServer server(int status, String body) throws IOException {
    return server(status, body, Map.of());
  }

  private static TestServer server(
      int status,
      String body,
      Map<String, String> headers) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/health", exchange -> respond(exchange, status, body, headers));
    server.start();
    return new TestServer(server);
  }

  private static void respond(
      HttpExchange exchange,
      int status,
      String body,
      Map<String, String> headers) throws IOException {
    headers.forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    try (exchange; var output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }

  private record TestServer(HttpServer server) implements AutoCloseable {
    int port() {
      return this.server.getAddress().getPort();
    }

    @Override
    public void close() {
      this.server.stop(0);
    }
  }
}
