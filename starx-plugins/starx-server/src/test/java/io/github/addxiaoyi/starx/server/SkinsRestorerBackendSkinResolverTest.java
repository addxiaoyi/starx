package io.github.addxiaoyi.starx.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
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
  void prefersStoredSkinLookupBeforeNetworkBackedPlayerLookup() {
    UUID uuid = UUID.fromString("4f06bce0-32d7-4d4d-bb17-9f7e92ae8701");
    SkinsRestorerBackendSkinResolver resolver = new SkinsRestorerBackendSkinResolver(
        new StoredSkinApi(uuid));

    BackendSkinProfile profile = resolver.find(uuid, "offline-player").orElseThrow();

    assertEquals("stored-value", profile.value());
    assertEquals("stored-signature", profile.signature());
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
  void rekeysExistingNonCustomIdentifierBeforeWritingWebsiteTexture() {
    UUID uuid = UUID.fromString("4f06bce0-32d7-4d4d-bb17-9f7e92ae8701");
    NonCustomWritableApi api = new NonCustomWritableApi();
    SkinsRestorerBackendSkinResolver resolver = new SkinsRestorerBackendSkinResolver(api);

    assertTrue(resolver.store(uuid, "Alex", "website-value", "website-signature"));

    String expected = "starx-4f06bce032d74d4dbb179f7e92ae8701";
    assertEquals(expected, api.players.skinId.identifier);
    assertEquals("CUSTOM", api.players.skinId.type);
    assertEquals(expected, api.skins.skinId);
  }

  @Test
  void storesWebsiteTextureUsingConstructorOnlyCurrentApi() {
    UUID uuid = UUID.fromString("4f06bce0-32d7-4d4d-bb17-9f7e92ae8701");
    ConstructorWritableApi api = new ConstructorWritableApi();
    SkinsRestorerBackendSkinResolver resolver = new SkinsRestorerBackendSkinResolver(api);

    assertTrue(resolver.store(uuid, "Alex", "website-value", "website-signature"));

    String expected = "starx-4f06bce032d74d4dbb179f7e92ae8701";
    assertEquals(expected, api.players.skinId.identifier());
    assertEquals(expected, api.skins.skinId);
    assertEquals("website-value", api.skins.property.value());
    assertEquals("website-signature", api.skins.property.signature());
  }

  @Test
  void prefersExactStringLookupOverBroaderOverload() {
    UUID uuid = UUID.fromString("4f06bce0-32d7-4d4d-bb17-9f7e92ae8701");
    SkinsRestorerBackendSkinResolver resolver = new SkinsRestorerBackendSkinResolver(
        new NullNameApi());

    BackendSkinProfile profile = resolver.find(uuid, "Alex").orElseThrow();

    assertEquals("null-name-value", profile.value());
    assertEquals("null-name-signature", profile.signature());
  }

  @Test
  void prefersSpecificReferenceLookupOverBroaderOverloadWhenNameIsNull() {
    UUID uuid = UUID.fromString("4f06bce0-32d7-4d4d-bb17-9f7e92ae8701");
    try {
      Method selector = SkinsRestorerBackendSkinResolver.class.getDeclaredMethod(
          "findMethod", Class.class, String.class, Object[].class);
      selector.setAccessible(true);
      Method selected = (Method) selector.invoke(
          null,
          NullNamePlayerStorage.class,
          "getSkinForPlayer",
          (Object) new Object[] {uuid, null});

      assertEquals(String.class, selected.getParameterTypes()[1]);
    } catch (ReflectiveOperationException error) {
      throw new AssertionError("Could not inspect reflected overload selection", error);
    }
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

  @Test
  void degradesWhenOptionalStorageMethodCannotLink() throws Exception {
    try (URLClassLoader loader = linkageFailingLoader()) {
      Class<?> storageType = Class.forName(
          LinkageErrorStorage.class.getName(), true, loader);
      Constructor<?> constructor = storageType.getDeclaredConstructor();
      constructor.trySetAccessible();
      SkinsRestorerBackendSkinResolver resolver = new SkinsRestorerBackendSkinResolver(
          new LinkageErrorApi(constructor.newInstance()));

      Optional<BackendSkinProfile> profile = assertDoesNotThrow(
          () -> resolver.find(UUID.randomUUID(), "Alex"));

      assertTrue(profile.isEmpty());
    }
  }

  @Test
  void prefersExactPropertyOverloadWhenStoringWebsiteTexture() {
    UUID uuid = UUID.fromString("4f06bce0-32d7-4d4d-bb17-9f7e92ae8701");
    OverloadedWritableApi api = new OverloadedWritableApi();
    SkinsRestorerBackendSkinResolver resolver = new SkinsRestorerBackendSkinResolver(api);

    assertTrue(resolver.store(uuid, "Alex", "website-value", "website-signature"));

    assertEquals("website-value", api.skins.property.getValue());
    assertEquals("website-signature", api.skins.property.getSignature());
  }

  @Test
  void continuesAfterSkinIdentifierConstructorFailure() {
    UUID uuid = UUID.fromString("4f06bce0-32d7-4d4d-bb17-9f7e92ae8701");
    ConstructorIdentifierFallbackApi api = new ConstructorIdentifierFallbackApi();
    SkinsRestorerBackendSkinResolver resolver = new SkinsRestorerBackendSkinResolver(api);

    assertTrue(resolver.store(uuid, "Alex", "website-value", "website-signature"));

    assertEquals("starx-4f06bce032d74d4dbb179f7e92ae8701", api.players.saved.identifier());
  }

  @Test
  void continuesAfterSkinPropertyConstructorFailure() {
    UUID uuid = UUID.fromString("4f06bce0-32d7-4d4d-bb17-9f7e92ae8701");
    PropertyConstructorFallbackApi api = new PropertyConstructorFallbackApi();
    SkinsRestorerBackendSkinResolver resolver = new SkinsRestorerBackendSkinResolver(api);

    assertTrue(resolver.store(uuid, "Alex", "website-value", "website-signature"));

    assertEquals("website-value", api.skins.property.value());
    assertEquals("website-signature", api.skins.property.signature());
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

  private record StoredSkinApi(UUID uuid) {
    public StoredSkinPlayerStorage getPlayerStorage() {
      return new StoredSkinPlayerStorage(this.uuid);
    }

    public Object getSkinStorage() {
      return null;
    }
  }

  private record StoredSkinPlayerStorage(UUID expected) {
    public Optional<WritableSkinData> getSkinOfPlayer(UUID uuid) {
      return this.expected.equals(uuid)
          ? Optional.of(new WritableSkinData("stored-value", "stored-signature"))
          : Optional.empty();
    }

    public Optional<WritableSkinData> getSkinForPlayer(UUID uuid, String name) {
      throw new AssertionError("network-backed lookup must not run when stored skin data exists");
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

  private static final class NonCustomWritableApi {
    private final NonCustomWritablePlayerStorage players = new NonCustomWritablePlayerStorage();
    private final CurrentWritableSkinStorage skins = new CurrentWritableSkinStorage();

    public NonCustomWritablePlayerStorage getPlayerStorage() {
      return this.players;
    }

    public CurrentWritableSkinStorage getSkinStorage() {
      return this.skins;
    }
  }

  private static final class NonCustomWritablePlayerStorage {
    private NonCustomSkinIdentifier skinId = new NonCustomSkinIdentifier("alex", "PLAYER");

    public Optional<NonCustomSkinIdentifier> getSkinIdOfPlayer(UUID uuid) {
      return Optional.of(this.skinId);
    }

    public void setSkinIdOfPlayer(UUID uuid, NonCustomSkinIdentifier skinId) {
      this.skinId = skinId;
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

  private static final class NullNameApi {
    public NullNamePlayerStorage getPlayerStorage() {
      return new NullNamePlayerStorage();
    }

    public Object getSkinStorage() {
      return new Object();
    }
  }

  private static final class NullNamePlayerStorage {
    public Optional<WritableSkinData> getSkinForPlayer(UUID uuid, Object name) {
      throw new AssertionError("broad overload must not be selected");
    }

    public Optional<WritableSkinData> getSkinForPlayer(UUID uuid, String name) {
      return Optional.of(new WritableSkinData("null-name-value", "null-name-signature"));
    }
  }

  private static final class ConstructorIdentifierFallbackApi {
    private final ConstructorIdentifierFallbackPlayerStorage players =
        new ConstructorIdentifierFallbackPlayerStorage();
    private final CurrentWritableSkinStorage skins = new CurrentWritableSkinStorage();

    public ConstructorIdentifierFallbackPlayerStorage getPlayerStorage() {
      return this.players;
    }

    public CurrentWritableSkinStorage getSkinStorage() {
      return this.skins;
    }
  }

  private static final class ConstructorIdentifierFallbackPlayerStorage {
    private ConstructorFallbackIdentifier saved;

    public Optional<ConstructorFallbackIdentifier> getSkinIdOfPlayer(UUID uuid) {
      return Optional.empty();
    }

    public void setSkinIdOfPlayer(UUID uuid, ThrowingIdentifier identifier) {
      throw new AssertionError("throwing identifier must not be invoked");
    }

    public void setSkinIdOfPlayer(UUID uuid, ConstructorFallbackIdentifier identifier) {
      this.saved = identifier;
    }
  }

  private static final class PropertyConstructorFallbackApi {
    private final WritablePlayerStorage players = new WritablePlayerStorage();
    private final PropertyConstructorFallbackSkinStorage skins =
        new PropertyConstructorFallbackSkinStorage();

    public WritablePlayerStorage getPlayerStorage() {
      return this.players;
    }

    public PropertyConstructorFallbackSkinStorage getSkinStorage() {
      return this.skins;
    }
  }

  private static final class PropertyConstructorFallbackSkinStorage {
    private PropertyFallbackProperty property;

    public void setCustomSkinData(String identifier, ThrowingProperty property) {
      throw new AssertionError("throwing property must not be selected");
    }

    public void setCustomSkinData(String identifier, PropertyFallbackProperty property) {
      this.property = property;
    }
  }

  private static final class OverloadedWritableApi {
    private final OverloadedWritablePlayerStorage players = new OverloadedWritablePlayerStorage();
    private final OverloadedWritableSkinStorage skins = new OverloadedWritableSkinStorage();

    public OverloadedWritablePlayerStorage getPlayerStorage() {
      return this.players;
    }

    public OverloadedWritableSkinStorage getSkinStorage() {
      return this.skins;
    }
  }

  private static final class OverloadedWritablePlayerStorage {
    private CurrentSkinIdentifier skinId;

    public Optional<CurrentSkinIdentifier> getSkinIdOfPlayer(UUID uuid) {
      return Optional.ofNullable(this.skinId);
    }

    public void setSkinIdOfPlayer(UUID uuid, CurrentSkinIdentifier skinId) {
      this.skinId = skinId;
    }
  }

  private static final class OverloadedWritableSkinStorage {
    private String skinId;
    private CurrentSkinProperty property;

    public void setCustomSkinData(String skinId, Object property) {
      throw new AssertionError("broad property overload must not be selected");
    }

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

  private record ConstructorFallbackIdentifier(String identifier) {
  }

  private static final class ThrowingIdentifier {
    private ThrowingIdentifier(String identifier) {
      throw new IllegalArgumentException("unsupported identifier");
    }
  }

  private record PropertyFallbackProperty(String value, String signature) {
    public String getValue() {
      return this.value;
    }

    public String getSignature() {
      return this.signature;
    }
  }

  private static final class ThrowingProperty {
    private ThrowingProperty(String value, String signature) {
      throw new IllegalArgumentException("unsupported property");
    }
  }

  private static final class ConstructorWritableApi {
    private final ConstructorWritablePlayerStorage players = new ConstructorWritablePlayerStorage();
    private final ConstructorWritableSkinStorage skins = new ConstructorWritableSkinStorage();

    public ConstructorWritablePlayerStorage getPlayerStorage() {
      return this.players;
    }

    public ConstructorWritableSkinStorage getSkinStorage() {
      return this.skins;
    }
  }

  private static final class ConstructorWritablePlayerStorage {
    private ConstructorSkinIdentifier skinId;

    public Optional<ConstructorSkinIdentifier> getSkinIdOfPlayer(UUID uuid) {
      return Optional.ofNullable(this.skinId);
    }

    public void setSkinIdOfPlayer(UUID uuid, ConstructorSkinIdentifier skinId) {
      this.skinId = skinId;
    }
  }

  private static final class ConstructorWritableSkinStorage {
    private String skinId;
    private ConstructorSkinProperty property;

    public void setCustomSkinData(String skinId, ConstructorSkinProperty property) {
      this.skinId = skinId;
      this.property = property;
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

  private record ConstructorSkinIdentifier(String identifier) {
    private ConstructorSkinIdentifier {
      if (identifier == null || identifier.isBlank()) {
        throw new IllegalArgumentException("identifier");
      }
    }
  }

  private record ConstructorSkinProperty(String value, String signature) {
  }

  private static final class NonCustomSkinIdentifier {
    private final String identifier;
    private final String type;

    private NonCustomSkinIdentifier(String identifier, String type) {
      this.identifier = identifier;
      this.type = type;
    }

    public String getIdentifier() {
      return this.identifier;
    }

    public String getSkinType() {
      return this.type;
    }

    public static NonCustomSkinIdentifier ofCustom(String identifier) {
      return new NonCustomSkinIdentifier(identifier, "CUSTOM");
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

  private record LinkageErrorApi(Object playerStorage) {
    public Object getPlayerStorage() {
      return this.playerStorage;
    }

    public Object getSkinStorage() {
      return null;
    }
  }

  private static URLClassLoader linkageFailingLoader() {
    URL location = SkinsRestorerBackendSkinResolverTest.class
        .getProtectionDomain().getCodeSource().getLocation();
    String storageName = LinkageErrorStorage.class.getName();
    String missingTypeName = MissingSkinProperty.class.getName();
    ClassLoader parent = SkinsRestorerBackendSkinResolverTest.class.getClassLoader();
    return new URLClassLoader(new URL[] {location}, parent) {
      @Override
      protected Class<?> loadClass(String name, boolean resolve)
          throws ClassNotFoundException {
        if (name.equals(missingTypeName)) {
          throw new ClassNotFoundException(name);
        }
        if (!name.equals(storageName)) {
          return super.loadClass(name, resolve);
        }
        Class<?> loaded = findLoadedClass(name);
        if (loaded == null) {
          loaded = findClass(name);
        }
        if (resolve) {
          resolveClass(loaded);
        }
        return loaded;
      }
    };
  }

  public static final class LinkageErrorStorage {
    public MissingSkinProperty getSkinOfPlayer(UUID uuid) {
      return null;
    }
  }

  public static final class MissingSkinProperty {
  }

  private static final class CapturingHandler extends Handler {
    private int count;
    @Override public void publish(LogRecord record) { count++; }
    @Override public void flush() { }
    @Override public void close() { }
  }
}
