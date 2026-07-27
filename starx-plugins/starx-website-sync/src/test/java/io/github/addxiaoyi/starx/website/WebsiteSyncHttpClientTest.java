package io.github.addxiaoyi.starx.website;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebsiteSyncHttpClientTest {
  private HttpServer server;
  private URI baseUrl;
  private final List<Request> requests = new CopyOnWriteArrayList<>();

  @BeforeEach
  void startServer() throws IOException {
    this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    this.server.createContext("/", this::handle);
    this.server.start();
    this.baseUrl = URI.create("http://127.0.0.1:" + this.server.getAddress().getPort());
  }

  @AfterEach
  void stopServer() {
    this.server.stop(0);
  }

  @Test
  void sendsEnrollmentHeartbeatManifestAndTextureProtocol() throws Exception {
    WebsiteSyncHttpClient client = client();
    Enrollment enrollment = client.enroll(
        SecretValue.of("stx_boot_test"),
        "proxy-1",
        WebsitePlatform.VELOCITY,
        List.of(NodeCapabilities.NETWORK_STATUS, NodeCapabilities.SERVER_STATUS));
    assertEquals("proxy-1", enrollment.nodeId());
    assertEquals("[REDACTED]", enrollment.nodeToken().toString());

    client.heartbeat(
        enrollment.nodeToken(),
        "proxy-1",
        List.of(NodeCapabilities.NETWORK_STATUS),
        new NodeSnapshot(
            "0.2.0",
            null,
            3,
            100,
            null,
            null,
            false,
            List.of(new ServerSnapshot(
                "lobby-1",
                "大厅",
                "paper",
                null,
                WebsiteNodeStatus.ONLINE,
                3,
                50,
                19.9,
                12.5,
                false,
                List.of(NodeCapabilities.SERVER_STATUS)))));

    String hash = "a".repeat(64);
    PlayerTexture texture = new PlayerTexture(
        "8667ba71-b85a-4004-af54-457a9734eed7",
        "Steve",
        hash,
        null,
        "classic",
        "skinsrestorer",
        Instant.parse("2026-07-27T00:00:00Z").toString(),
        false);
    ManifestAck manifest = client.submitManifest(enrollment.nodeToken(), List.of(texture));
    assertEquals(List.of(new MissingTexture(hash, TextureKind.SKIN)), manifest.missingHashes());

    byte[] png = pngHeader(64, 64);
    String actualHash = java.util.HexFormat.of().formatHex(
        java.security.MessageDigest.getInstance("SHA-256").digest(png));
    client.uploadTexture(
        enrollment.nodeToken(), new TextureBlob(TextureKind.SKIN, png, actualHash));

    assertEquals(4, this.requests.size());
    assertTrue(this.requests.get(0).body().contains("\"bootstrapToken\":\"stx_boot_test\""));
    assertFalse(this.requests.get(1).body().contains("\"tps\":null"));
    assertTrue(this.requests.get(1).body().contains("\"status\":\"online\""));
    assertEquals("Bearer stx_node_test", this.requests.get(1).authorization());
    assertTrue(this.requests.get(2).body().contains("\"syncId\":"));
    assertTrue(this.requests.get(2).body().contains("\"page\":0"));
    assertTrue(this.requests.get(2).body().contains("\"pages\":1"));
    assertTrue(this.requests.get(3).path().endsWith(actualHash));
  }

  @Test
  void serializesExplicitManifestPageMetadata() throws Exception {
    WebsiteSyncHttpClient client = client();

    client.submitManifestPage(
        SecretValue.of("stx_node_test"),
        "sync-20260727-a",
        1,
        3,
        List.of());

    assertEquals(1, this.requests.size());
    assertTrue(this.requests.get(0).body().contains("\"syncId\":\"sync-20260727-a\""));
    assertTrue(this.requests.get(0).body().contains("\"page\":1"));
    assertTrue(this.requests.get(0).body().contains("\"pages\":3"));
    assertThrows(
        IllegalArgumentException.class,
        () -> client.submitManifestPage(
            SecretValue.of("stx_node_test"), "bad", 0, 1, List.of()));
  }

  @Test
  void mapsUnauthorizedWithoutExposingToken() {
    WebsiteSyncHttpClient client = client();
    WebsiteSyncApiException error = assertThrows(
        WebsiteSyncApiException.class,
        () -> client.heartbeat(
            SecretValue.of("stx_node_rejected"),
            "unauthorized",
            List.of(),
            new NodeSnapshot("0.2.0", null, 0, 10, null, null, false, List.of())));
    assertTrue(error.unauthorized());
    assertEquals("credential_invalid", error.errorCode());
    assertFalse(error.getMessage().contains("stx_node_rejected"));
  }

  private WebsiteSyncHttpClient client() {
    return new WebsiteSyncHttpClient(
        this.baseUrl,
        Duration.ofSeconds(2),
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
        new Gson());
  }

  private void handle(HttpExchange exchange) throws IOException {
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    String authorization = exchange.getRequestHeaders().getFirst("Authorization");
    this.requests.add(new Request(exchange.getRequestURI().getPath(), authorization, body));
    String path = exchange.getRequestURI().getPath();
    int status = 200;
    String response;
    if (path.endsWith("/enroll")) {
      response = """
          {"ok":true,"credential":{"token":"stx_node_test","nodeId":"proxy-1","scopes":["heartbeat:write"]}}
          """;
    } else if (path.endsWith("/heartbeat") && body.contains("\"nodeId\":\"unauthorized\"")) {
      status = 401;
      response = "{" + "\"ok\":false,\"code\":\"credential_invalid\",\"message\":\"denied\"}";
    } else if (path.endsWith("/heartbeat")) {
      response = "{" + "\"ok\":true,\"nodeId\":\"proxy-1\",\"receivedAt\":\"2026-07-27T00:00:00.000Z\"}";
    } else if (path.endsWith("/skins/manifest")) {
      response = "{" + "\"ok\":true,\"accepted\":1,\"missingHashes\":[{\"hash\":\""
          + "a".repeat(64) + "\",\"kind\":\"skin\"}]}";
    } else {
      response = "{\"ok\":true,\"texture\":{}}";
    }
    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private static byte[] pngHeader(int width, int height) {
    byte[] bytes = new byte[24];
    byte[] signature = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    System.arraycopy(signature, 0, bytes, 0, signature.length);
    writeInt(bytes, 16, width);
    writeInt(bytes, 20, height);
    return bytes;
  }

  private static void writeInt(byte[] bytes, int offset, int value) {
    bytes[offset] = (byte) (value >>> 24);
    bytes[offset + 1] = (byte) (value >>> 16);
    bytes[offset + 2] = (byte) (value >>> 8);
    bytes[offset + 3] = (byte) value;
  }

  private record Request(String path, String authorization, String body) {
  }
}
