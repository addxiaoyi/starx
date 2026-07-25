package io.github.addxiaoyi.starx.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

final class SkinsRestorerBackendSkinResolverTest {

  @Test
  void readsSignedTextureThroughReflectedApi() {
    UUID uuid = UUID.fromString("4f06bce0-32d7-4d4d-bb17-9f7e92ae8701");
    SkinsRestorerBackendSkinResolver resolver = new SkinsRestorerBackendSkinResolver(
        new FakeApi(uuid));

    BackendSkinProfile profile = resolver.find(uuid, "Alex").orElseThrow();

    assertTrue(resolver.available());
    assertEquals("skinsrestorer", resolver.provider());
    assertEquals("texture-value", profile.value());
    assertEquals("texture-signature", profile.signature());
  }

  @Test
  void logsReflectedApiFailureOnceWithoutBreakingBridge() {
    Logger logger = Logger.getLogger("skin-resolver-test-" + UUID.randomUUID());
    CapturingHandler logs = new CapturingHandler();
    logger.setUseParentHandlers(false);
    logger.addHandler(logs);
    SkinsRestorerBackendSkinResolver resolver = new SkinsRestorerBackendSkinResolver(
        new BrokenApi(), logger);

    assertTrue(resolver.find(UUID.randomUUID(), "Alex").isEmpty());
    assertTrue(resolver.find(UUID.randomUUID(), "Steve").isEmpty());
    assertEquals(1, logs.count);
  }

  private record FakeApi(UUID uuid) {
    public FakePlayerStorage getPlayerStorage() {
      return new FakePlayerStorage(this.uuid);
    }

    public FakeSkinStorage getSkinStorage() {
      return new FakeSkinStorage();
    }
  }

  private record FakePlayerStorage(UUID expected) {
    public Optional<String> getSkinIdOfPlayer(UUID uuid) {
      return this.expected.equals(uuid) ? Optional.of("alex-skin") : Optional.empty();
    }
  }

  private static final class FakeSkinStorage {
    public Optional<FakeSkinData> getSkinDataByIdentifier(String id) {
      return "alex-skin".equals(id)
          ? Optional.of(new FakeSkinData())
          : Optional.empty();
    }
  }

  private static final class FakeSkinData {
    public String getValue() {
      return "texture-value";
    }

    public String getSignature() {
      return "texture-signature";
    }
  }

  private record BrokenApi() {
    public Object getPlayerStorage() { return new Object(); }
    public Object getSkinStorage() { return new Object(); }
  }

  private static final class CapturingHandler extends Handler {
    private int count;
    @Override public void publish(LogRecord record) { count++; }
    @Override public void flush() { }
    @Override public void close() { }
  }
}
