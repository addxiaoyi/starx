package io.github.addxiaoyi.starx.website;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WebsiteSyncRuntimeTest {
  @Test
  void enrollsPersistsCredentialAndPublishesHeartbeat() throws Exception {
    AtomicReference<SecretValue> persisted = new AtomicReference<>();
    FakeClient client = new FakeClient();
    WebsiteSyncRuntime runtime = runtime(
        config(SecretValue.of("stx_boot_test"), SecretValue.empty(), false),
        client,
        persisted::set);
    try {
      runtime.start();
      await(() -> runtime.snapshot().state() == WebsiteSyncRuntime.State.ACTIVE);
      assertEquals(1, client.enrollments.get());
      assertEquals(1, client.heartbeats.get());
      assertTrue(persisted.get().isPresent());
      assertEquals("[REDACTED]", persisted.get().toString());
    } finally {
      runtime.close();
    }
  }

  @Test
  void unauthorizedCredentialStopsFurtherHeartbeats() throws Exception {
    FakeClient client = new FakeClient();
    client.heartbeatFailure = new WebsiteSyncApiException(
        401, "credential_invalid", "denied");
    WebsiteSyncRuntime runtime = runtime(
        config(SecretValue.empty(), SecretValue.of("stx_node_bad"), false),
        client,
        ignored -> { });
    try {
      runtime.start();
      await(() -> runtime.snapshot().state() == WebsiteSyncRuntime.State.AUTH_FAILED);
      int attempts = client.heartbeats.get();
      runtime.forceHeartbeat();
      Thread.sleep(1_200);
      assertEquals(attempts, client.heartbeats.get());
      assertEquals("credential_invalid", runtime.snapshot().lastErrorCode());
    } finally {
      runtime.close();
    }
  }

  @Test
  void malformedOrMismatchedHeartbeatDoesNotInvalidateCredential() throws Exception {
    List<WebsiteSyncApiException> failures = List.of(
        new WebsiteSyncApiException(400, "snapshot_invalid", "bad snapshot"),
        new WebsiteSyncApiException(403, "node_mismatch", "wrong node"));
    for (WebsiteSyncApiException failure : failures) {
      FakeClient client = new FakeClient();
      client.heartbeatFailure = failure;
      WebsiteSyncRuntime runtime = runtime(
          config(SecretValue.empty(), SecretValue.of("stx_node_test"), false),
          client,
          ignored -> { });
      try {
        runtime.start();
        await(() -> runtime.snapshot().state() == WebsiteSyncRuntime.State.BACKOFF);
        assertEquals(failure.errorCode(), runtime.snapshot().lastErrorCode());
        int attempts = client.heartbeats.get();
        runtime.forceHeartbeat();
        await(() -> client.heartbeats.get() > attempts);
        assertTrue(runtime.snapshot().state() != WebsiteSyncRuntime.State.AUTH_FAILED);
      } finally {
        runtime.close();
      }
    }
  }

  @Test
  void onlyOneHeartbeatCanBeInFlight() throws Exception {
    FakeClient client = new FakeClient();
    client.blockHeartbeats = true;
    WebsiteSyncRuntime runtime = runtime(
        config(SecretValue.empty(), SecretValue.of("stx_node_test"), false),
        client,
        ignored -> { });
    try {
      runtime.start();
      assertTrue(client.heartbeatStarted.await(3, TimeUnit.SECONDS));
      Thread.sleep(1_500);
      assertEquals(1, client.heartbeats.get());
      client.releaseHeartbeat.countDown();
      await(() -> runtime.snapshot().state() == WebsiteSyncRuntime.State.ACTIVE);
    } finally {
      client.releaseHeartbeat.countDown();
      runtime.close();
    }
  }

  private static WebsiteSyncRuntime runtime(
      WebsiteSyncConfig config,
      FakeClient client,
      WebsiteSyncCredentialStore credentials
  ) {
    return new WebsiteSyncRuntime(
        config,
        client,
        credentials,
        () -> new NodeSnapshot("0.2.0", null, 0, 100, null, null, false, List.of()),
        TextureSource.empty(),
        List.of(NodeCapabilities.NETWORK_STATUS),
        ignored -> { });
  }

  private static WebsiteSyncConfig config(
      SecretValue bootstrap,
      SecretValue node,
      boolean textures
  ) {
    return new WebsiteSyncConfig(
        true,
        URI.create("https://star-web.top"),
        "proxy-1",
        WebsitePlatform.VELOCITY,
        bootstrap,
        node,
        new WebsiteSyncConfig.Heartbeat(5, 250, 500),
        new WebsiteSyncConfig.Textures(textures, "skinsrestorer", 60, 500));
  }

  private static void await(Check check) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
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

  private static final class FakeClient implements WebsiteSyncClient {
    private final AtomicInteger enrollments = new AtomicInteger();
    private final AtomicInteger heartbeats = new AtomicInteger();
    private final CountDownLatch heartbeatStarted = new CountDownLatch(1);
    private final CountDownLatch releaseHeartbeat = new CountDownLatch(1);
    private volatile boolean blockHeartbeats;
    private volatile WebsiteSyncApiException heartbeatFailure;

    @Override
    public Enrollment enroll(
        SecretValue bootstrapToken,
        String nodeId,
        WebsitePlatform platform,
        List<String> capabilities
    ) {
      this.enrollments.incrementAndGet();
      return new Enrollment(
          SecretValue.of("stx_node_enrolled"), nodeId, List.of("heartbeat:write"));
    }

    @Override
    public HeartbeatAck heartbeat(
        SecretValue nodeToken,
        String nodeId,
        List<String> capabilities,
        NodeSnapshot snapshot
    ) throws WebsiteSyncApiException {
      this.heartbeats.incrementAndGet();
      this.heartbeatStarted.countDown();
      if (this.blockHeartbeats) {
        try {
          this.releaseHeartbeat.await(4, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
        }
      }
      if (this.heartbeatFailure != null) {
        throw this.heartbeatFailure;
      }
      return new HeartbeatAck(nodeId, Instant.now().toString());
    }

    @Override
    public ManifestAck submitManifest(
        SecretValue nodeToken,
        Collection<PlayerTexture> entries
    ) {
      return new ManifestAck(entries.size(), List.of());
    }

    @Override
    public void uploadTexture(SecretValue nodeToken, TextureBlob texture) {
    }
  }
}
