package io.github.addxiaoyi.starx.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BushClientTest {
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) server.stop(0);
  }

  @Test
  void distinguishesBlockedClearAndUnavailableResults() throws Exception {
    assertEquals(BushClient.Status.BLOCKED, client(200, "{\"ip\":\"203.0.113.1\",\"reason\":\"proxy\"}").check("203.0.113.1").status());
    assertEquals(BushClient.Status.CLEAR, client(404, "{}").check("203.0.113.2").status());
    assertEquals(BushClient.Status.UNAVAILABLE, client(503, "{}").check("203.0.113.3").status());
  }

  private BushClient client(int status, String body) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/ip", exchange -> {
      exchange.getRequestBody().readAllBytes();
      byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();
    return new BushClient("http://127.0.0.1:" + server.getAddress().getPort() + "/");
  }
}
