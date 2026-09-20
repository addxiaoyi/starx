package io.github.addxiaoyi.starx.server;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class StarxAccountClientResponseLimitTest {
  @Test
  void rejectsOversizedApiResponses() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/admin/approval/create", exchange -> {
      byte[] body = new byte[300_000];
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try {
      StarxAccountClient client = new StarxAccountClient(
          "http://127.0.0.1:" + server.getAddress().getPort(), "secret");

      assertThrows(CompletionException.class, () -> client.createApproval(
          UUID.randomUUID(), "Alex", "bind_email").join());
    } finally {
      server.stop(0);
    }
  }
}
