package io.github.addxiaoyi.starx.velocity.http;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class JavaHttpTransportTest {
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) server.stop(0);
  }

  @Test
  void completesForSuccessfulDelivery() throws Exception {
    String url = endpoint(204);

    assertDoesNotThrow(() -> new JavaHttpTransport().post(url, "{}", Map.of()).join());
  }

  @Test
  void failsForRejectedDelivery() throws Exception {
    String url = endpoint(503);

    assertThrows(
        CompletionException.class,
        () -> new JavaHttpTransport().post(url, "{}", Map.of()).join());
  }

  private String endpoint(int status) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/hook", exchange -> {
      exchange.getRequestBody().readAllBytes();
      exchange.sendResponseHeaders(status, -1);
      exchange.close();
    });
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
  }
}
