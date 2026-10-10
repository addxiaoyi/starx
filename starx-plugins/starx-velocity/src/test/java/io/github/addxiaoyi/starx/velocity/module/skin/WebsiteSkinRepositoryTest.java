package io.github.addxiaoyi.starx.velocity.module.skin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
  void cachesAnUnboundWebsiteProfileLookup() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/unbound.json", exchange -> {
      requests.incrementAndGet();
      exchange.sendResponseHeaders(404, -1);
      exchange.close();
    });
    server.start();

    try {
      WebsiteSkinRepository repository = new WebsiteSkinRepository(
          "http://127.0.0.1:" + server.getAddress().getPort(),
          Logger.getLogger(WebsiteSkinRepositoryTest.class.getName()));

      assertTrue(repository.findProfile("unbound").isEmpty());
      assertTrue(repository.findProfile("UNBOUND").isEmpty());
      assertEquals(1, requests.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void prefersUuidProfileEndpointForLoginLookup() throws Exception {
    AtomicInteger uuidRequests = new AtomicInteger();
    AtomicInteger nameRequests = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    UUID uuid = UUID.fromString("4f06bce0-32d7-4d4d-bb17-9f7e92ae8701");
    server.createContext("/" + uuid + ".json", exchange -> {
      uuidRequests.incrementAndGet();
      byte[] body = ("{\"id\":\"" + uuid.toString().replace("-", "")
          + "\",\"textures\":{\"SKIN\":{\"url\":\"https://textures.minecraft.net/texture/skin\"}}}")
          .getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream output = exchange.getResponseBody()) { output.write(body); }
    });
    server.createContext("/player.json", exchange -> {
      nameRequests.incrementAndGet();
      exchange.sendResponseHeaders(404, -1);
      exchange.close();
    });
    server.start();
    try {
      WebsiteSkinRepository repository = new WebsiteSkinRepository(
          "http://127.0.0.1:" + server.getAddress().getPort(),
          Logger.getLogger(WebsiteSkinRepositoryTest.class.getName()));
      assertTrue(repository.findProfile(uuid, "player").isPresent());
      assertEquals(1, uuidRequests.get());
      assertEquals(0, nameRequests.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void doesNotNegativeCacheUuidProfileMisses() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    UUID uuid = UUID.fromString("4f06bce0-32d7-4d4d-bb17-9f7e92ae8701");
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/" + uuid + ".json", exchange -> {
      int count = requests.incrementAndGet();
      byte[] body = count == 1 ? new byte[0] : ("{\"id\":\"" + uuid.toString().replace("-", "")
          + "\",\"textures\":{\"SKIN\":{\"url\":\"https://textures.minecraft.net/texture/skin\"}}}")
          .getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(count == 1 ? 404 : 200, count == 1 ? -1 : body.length);
      if (count > 1) try (OutputStream output = exchange.getResponseBody()) { output.write(body); }
      else exchange.close();
    });
    server.start();
    try {
      WebsiteSkinRepository repository = new WebsiteSkinRepository(
          "http://127.0.0.1:" + server.getAddress().getPort(),
          Logger.getLogger(WebsiteSkinRepositoryTest.class.getName()));
      assertTrue(repository.findProfile(uuid, "player").isEmpty());
      assertTrue(repository.findProfile(uuid, "player").isPresent());
      assertEquals(2, requests.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void doesNotReuseSkinDtoForAnotherUuidWithTheSameName() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/player.json", exchange -> {
      requests.incrementAndGet();
        byte[] body = "{\"id\":\"website-profile\",\"name\":\"player\",\"textures\":{\"SKIN\":{\"url\":\"https://textures.minecraft.net/texture/skin\"}}}"
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
      assertEquals(1, requests.get());
    } finally {
      server.stop(0);
    }
  }
}
