package io.github.addxiaoyi.starx.website;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class WebsiteSyncRuntimeTexturePaginationTest {
  @Test
  void sendsOneAtomicSyncIdAcrossAllManifestPages() throws Exception {
    FakeClient client = new FakeClient();
    WebsiteSyncConfig config = new WebsiteSyncConfig(
        true,
        URI.create("https://star-web.top"),
        "proxy-1",
        WebsitePlatform.VELOCITY,
        SecretValue.empty(),
        SecretValue.of("stx_node_test"),
        new WebsiteSyncConfig.Heartbeat(5, 250, 500),
        new WebsiteSyncConfig.Textures(true, "skinsrestorer", 60, 2));
    TextureSource textures = () -> List.of(
        texture("8667ba71-b85a-4004-af54-457a9734eed7", "Steve", "a"),
        texture("8667ba72-b85a-4004-af54-457a9734eed7", "Alex", "b"),
        texture("8667ba73-b85a-4004-af54-457a9734eed7", "Notch", "c"));
    WebsiteSyncRuntime runtime = new WebsiteSyncRuntime(
        config,
        client,
        ignored -> { },
        () -> new NodeSnapshot("0.2.0", null, 0, 100, null, null, false, List.of()),
        textures,
        List.of(NodeCapabilities.NETWORK_STATUS),
        ignored -> { });

    try {
      runtime.start();
      await(() -> client.pages.size() == 2);

      PageCall first = client.pages.get(0);
      PageCall second = client.pages.get(1);
      assertEquals(first.syncId(), second.syncId());
      assertEquals(0, first.page());
      assertEquals(1, second.page());
      assertEquals(2, first.pages());
      assertEquals(2, second.pages());
      assertEquals(2, first.entries());
      assertEquals(1, second.entries());
      assertTrue(runtime.snapshot().lastTextureSyncAt() != null);
    } finally {
      runtime.close();
    }
  }

  private static PlayerTextureRecord texture(
      String uuid,
      String name,
      String hashCharacter
  ) {
    return new PlayerTextureRecord(
        new PlayerTexture(
            uuid,
            name,
            hashCharacter.repeat(64),
            null,
            "classic",
            "skinsrestorer",
            Instant.parse("2026-07-27T00:00:00Z").toString(),
            false),
        Map.of());
  }

  private static void await(Check check) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
    while (System.nanoTime() < deadline) {
      if (check.get()) {
        return;
      }
      Thread.sleep(25);
    }
    throw new AssertionError("condition was not satisfied before timeout");
  }

  @FunctionalInterface
  private interface Check {
    boolean get() throws Exception;
  }

  private record PageCall(String syncId, int page, int pages, int entries) {
  }

  private static final class FakeClient implements WebsiteSyncClient {
    private final List<PageCall> pages = new CopyOnWriteArrayList<>();

    @Override
    public Enrollment enroll(
        SecretValue bootstrapToken,
        String nodeId,
        WebsitePlatform platform,
        List<String> capabilities
    ) {
      throw new AssertionError("enrollment must not be used with an existing node token");
    }

    @Override
    public HeartbeatAck heartbeat(
        SecretValue nodeToken,
        String nodeId,
        List<String> capabilities,
        NodeSnapshot snapshot
    ) {
      return new HeartbeatAck(nodeId, Instant.now().toString());
    }

    @Override
    public ManifestAck submitManifest(
        SecretValue nodeToken,
        Collection<PlayerTexture> entries
    ) {
      throw new AssertionError("runtime must use paginated manifest submission");
    }

    @Override
    public ManifestAck submitManifestPage(
        SecretValue nodeToken,
        String syncId,
        int page,
        int pages,
        Collection<PlayerTexture> entries
    ) {
      this.pages.add(new PageCall(syncId, page, pages, entries.size()));
      return new ManifestAck(entries.size(), List.of());
    }

    @Override
    public void uploadTexture(SecretValue nodeToken, TextureBlob texture) {
      throw new AssertionError("website did not request a texture upload");
    }
  }
}
