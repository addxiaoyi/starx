package io.github.addxiaoyi.starx.velocity.module.skin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import io.github.addxiaoyi.starx.api.dto.SkinDto;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

final class WebsiteSkinRepositoryTest {

  @Test
  void doesNotReuseSkinDtoForAnotherUuidWithTheSameName() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/player.json", exchange -> {
      requests.incrementAndGet();
      byte[] body = "{\"id\":\"website-profile\",\"name\":\"player\",\"textures\":{\"SKIN\":{\"url\":\"https://example.invalid/skin.png\"}}}"
          .getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream output = exchange.getResponseBody()) {
        output.write(body);
      }
    });
    server.start();

    try {
      WebsiteSkinRepository repository = new WebsiteSkinRepository(
          "http://127.0.0.1:" + server.getAddress().getPort(),
          Logger.getLogger(WebsiteSkinRepositoryTest.class.getName()));
      UUID firstUuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
      UUID secondUuid = UUID.fromString("00000000-0000-0000-0000-000000000002");

      SkinDto first = repository.findByPlayer(firstUuid, "player").orElseThrow();
      SkinDto second = repository.findByPlayer(secondUuid, "player").orElseThrow();

      assertEquals(firstUuid, first.ownerUuid());
      assertEquals(secondUuid, second.ownerUuid());
      assertEquals(2, requests.get());
    } finally {
      server.stop(0);
    }
  }
}
