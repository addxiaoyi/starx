package io.github.addxiaoyi.starx.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class StarxAccountClientApprovalTest {
  @Test
  void createsAnIdentityBoundApprovalAndReturnsItsWebsiteUrl() throws Exception {
    AtomicReference<String> requestBody = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/admin/approval/create", exchange -> {
      requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      byte[] body = ("{\"ok\":true,\"message\":\"created\","
          + "\"url\":\"https://star-web.top/minecraft/approve?token=opaque&action=bind_email\","
          + "\"action\":\"BIND_EMAIL\"}").getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(201, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try {
      StarxAccountClient client = new StarxAccountClient(
          "http://127.0.0.1:" + server.getAddress().getPort(), "test-key");

      StarxAccountClient.Reply reply = client.createApproval(
          UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7"),
          "Alex", "bind_email").join();

      assertTrue(reply.ok());
      assertTrue(reply.url().startsWith("https://star-web.top/minecraft/approve?token="));
      assertTrue(requestBody.get().contains("\"action\":\"bind_email\""));
      assertEquals("BIND_EMAIL", reply.action());
    } finally {
      server.stop(0);
    }
  }
}
