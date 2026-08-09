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
  void storesWebsiteTextureAndAssignsItToThePlayer() {
    UUID uuid = UUID.fromString("4f06bce0-32d7-4d4d-bb17-9f7e92ae8701");
    WritableApi api = new WritableApi();
    SkinsRestorerBackendSkinResolver resolver = new SkinsRestorerBackendSkinResolver(api);

    assertTrue(resolver.store(uuid, "Alex", "website-value", "website-signature"));
    BackendSkinProfile stored = resolver.find(uuid, "Alex").orElseThrow();

    assertEquals("website-value", stored.value());
    assertEquals("website-signature", stored.signature());
    assertEquals("starx-4f06bce032d74d4dbb179f7e92ae8701", api.players.skinId);
  }

  @Test
  void storesWebsiteTextureThroughCurrentSkinsRestorerApi() {
    UUID uuid = UUID.fromString("4f06bce0-32d7-4d4d-bb17-9f7e92ae8701");
    CurrentWritableApi api = new CurrentWritableApi();
    SkinsRestorerBackendSkinResolver resolver = new SkinsRestorerBackendSkinResolver(api);

    assertTrue(resolver.store(uuid, "Alex", "website-value", "website-signature"));
    BackendSkinProfile stored = resolver.find(uuid, "Alex").orElseThrow();

    assertEquals("website-value", stored.value());
    assertEquals("website-signature", stored.signature());
    assertEquals("starx-4f06bce032d74d4dbb179f7e92ae8701", api.players.skinId.identifier);
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

  private static final class WritableApi {
    private final WritablePlayerStorage players = new WritablePlayerStorage();
    private final WritableSkinStorage skins = new WritableSkinStorage();

    public WritablePlayerStorage getPlayerStorage() {
      return this.players;
    }

    public WritableSkinStorage getSkinStorage() {
      return this.skins;
    }
  }

  private static final class WritablePlayerStorage {
    private String skinId;

    public Optional<String> getSkinIdOfPlayer(UUID uuid) {
      return Optional.ofNullable(this.skinId);
    }

    public void setSkinIdOfPlayer(UUID uuid, String skinId) {
      this.skinId = skinId;
    }
  }

  private static final class WritableSkinStorage {
    private String skinId;
    private String value;
    private String signature;

    public void setSkinData(String skinId, String value, String signature) {
      this.skinId = skinId;
      this.value = value;
      this.signature = signature;
    }

    public Optional<WritableSkinData> getSkinDataByIdentifier(String skinId) {
      return this.skinId != null && this.skinId.equals(skinId)
          ? Optional.of(new WritableSkinData(this.value, this.signature))
          : Optional.empty();
    }
  }

  private static final class CurrentWritableApi {
    private final CurrentWritablePlayerStorage players = new CurrentWritablePlayerStorage();
    private final CurrentWritableSkinStorage skins = new CurrentWritableSkinStorage();

    public CurrentWritablePlayerStorage getPlayerStorage() {
      return this.players;
    }

    public CurrentWritableSkinStorage getSkinStorage() {
      return this.skins;
    }
  }

  private static final class CurrentWritablePlayerStorage {
    private CurrentSkinIdentifier skinId;

    public Optional<CurrentSkinIdentifier> getSkinIdOfPlayer(UUID uuid) {
      return Optional.ofNullable(this.skinId);
    }

    public void setSkinIdOfPlayer(UUID uuid, CurrentSkinIdentifier skinId) {
      this.skinId = skinId;
    }
  }

  private static final class CurrentWritableSkinStorage {
    private String skinId;
    private CurrentSkinProperty property;

    public void setCustomSkinData(String skinId, CurrentSkinProperty property) {
      this.skinId = skinId;
      this.property = property;
    }

    public Optional<CurrentSkinProperty> getSkinDataByIdentifier(CurrentSkinIdentifier identifier) {
      return this.skinId != null && this.skinId.equals(identifier.getIdentifier())
          ? Optional.of(this.property)
          : Optional.empty();
    }
  }

  private static final class CurrentSkinIdentifier {
    private final String identifier;

    private CurrentSkinIdentifier(String identifier) {
      this.identifier = identifier;
    }

    public String getIdentifier() {
      return this.identifier;
    }

    public static CurrentSkinIdentifier ofCustom(String identifier) {
      return new CurrentSkinIdentifier(identifier);
    }
  }

  private static final class CurrentSkinProperty {
    private final String value;
    private final String signature;

    private CurrentSkinProperty(String value, String signature) {
      this.value = value;
      this.signature = signature;
    }

    public static CurrentSkinProperty of(String value, String signature) {
      return new CurrentSkinProperty(value, signature);
    }

    public String getValue() {
      return this.value;
    }

    public String getSignature() {
      return this.signature;
    }
  }

  private record WritableSkinData(String value, String signature) {
    public String getValue() { return this.value; }
    public String getSignature() { return this.signature; }
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
