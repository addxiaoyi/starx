package io.github.addxiaoyi.starx.common.skin;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.api.dto.SkinDto;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.skinsrestorer.api.SkinsRestorerProvider;
import org.junit.jupiter.api.Test;

class SkinsRestorerSkinRepositoryTest {
  @Test
  void readsIdentifierFromCurrentSkinsRestorerApi() throws Exception {
    SkinsRestorerSkinRepository repository = new SkinsRestorerSkinRepository();
    set(repository, "available", true);
    set(repository, "playerStorage", new PlayerStorage());
    set(repository, "skinStorage", new SkinStorage());

    SkinDto skin = repository.findByPlayer(UUID.randomUUID(), "player").orElseThrow();

    assertEquals("custom:starx-player", skin.skinId());
  }

  @Test
  void readsTextureFromPlayerStorageWhenSkinStorageIsUnavailable() throws Exception {
    SkinsRestorerSkinRepository repository = new SkinsRestorerSkinRepository();
    UUID uuid = UUID.randomUUID();
    set(repository, "available", true);
    set(repository, "playerStorage", new DirectPlayerStorage(uuid));
    set(repository, "skinStorage", null);

    SkinDto skin = repository.findByPlayer(uuid, "player").orElseThrow();

    assertEquals("direct-value", skin.value());
    assertEquals("direct-signature", skin.signature());
  }

  @Test
  void prefersStoredSkinLookupBeforeNetworkBackedPlayerLookup() throws Exception {
    SkinsRestorerSkinRepository repository = new SkinsRestorerSkinRepository();
    UUID uuid = UUID.randomUUID();
    set(repository, "available", true);
    set(repository, "playerStorage", new StoredSkinPlayerStorage(uuid));
    set(repository, "skinStorage", null);

    SkinDto skin = repository.findByPlayer(uuid, "offline-player").orElseThrow();

    assertEquals("stored-value", skin.value());
    assertEquals("stored-signature", skin.signature());
  }

  @Test
  void keepsCurrentApiWhenLegacySkinStorageIsUnavailable() {
    SkinsRestorerSkinRepository repository = new SkinsRestorerSkinRepository();

    SkinDto skin = repository.findByPlayer(UUID.randomUUID(), "player").orElseThrow();

    assertEquals("provider-value", skin.value());
    assertEquals("provider-signature", skin.signature());
  }

  @Test
  void keepsCurrentApiWhenOptionalSkinStorageGetterFails() {
    SkinsRestorerProvider.setFailSkinStorage(true);
    try {
      SkinsRestorerSkinRepository repository = new SkinsRestorerSkinRepository();

      SkinDto skin = repository.findByPlayer(UUID.randomUUID(), "player").orElseThrow();

      assertEquals("provider-value", skin.value());
      assertEquals("provider-signature", skin.signature());
    } finally {
      SkinsRestorerProvider.setFailSkinStorage(false);
    }
  }

  @Test
  void degradesWhenOptionalStorageMethodCannotLink() throws Exception {
    SkinsRestorerSkinRepository repository = new SkinsRestorerSkinRepository();
    set(repository, "available", true);
    try (URLClassLoader loader = linkageFailingLoader()) {
      Class<?> storageType = Class.forName(
          LinkageErrorStorage.class.getName(), true, loader);
      Constructor<?> constructor = storageType.getDeclaredConstructor();
      constructor.trySetAccessible();
      set(repository, "playerStorage", constructor.newInstance());
      set(repository, "skinStorage", null);

      Optional<SkinDto> skin = assertDoesNotThrow(
          () -> repository.findByPlayer(UUID.randomUUID(), "player"));

      assertTrue(skin.isEmpty());
    }
  }

  @Test
  void writesIdentifierToCurrentSkinsRestorerApi() throws Exception {
    SkinsRestorerSkinRepository repository = new SkinsRestorerSkinRepository();
    PlayerStorage storage = new PlayerStorage();
    set(repository, "available", true);
    set(repository, "playerStorage", storage);

    repository.setSkinId(UUID.randomUUID(), "custom:starx-player");

    assertEquals("custom:starx-player", storage.saved.identifier);
  }

  @Test
  void writesTextureToCurrentSkinsRestorerApi() throws Exception {
    SkinsRestorerSkinRepository repository = new SkinsRestorerSkinRepository();
    PlayerStorage playerStorage = new PlayerStorage();
    SkinStorage skinStorage = new SkinStorage();
    set(repository, "available", true);
    set(repository, "playerStorage", playerStorage);
    set(repository, "skinStorage", skinStorage);

    repository.setSkinData(UUID.randomUUID(), "texture-value", "texture-signature");

    assertEquals("custom:starx-player", skinStorage.savedIdentifier);
    assertEquals("texture-value", skinStorage.savedProperty.getValue());
    assertEquals("texture-signature", skinStorage.savedProperty.getSignature());
    assertEquals("custom:starx-player", playerStorage.saved.identifier);
  }

  @Test
  void writesTextureThroughLegacyThreeStringApi() throws Exception {
    SkinsRestorerSkinRepository repository = new SkinsRestorerSkinRepository();
    LegacyPlayerStorage playerStorage = new LegacyPlayerStorage();
    LegacySkinStorage skinStorage = new LegacySkinStorage();
    UUID uuid = UUID.randomUUID();
    set(repository, "available", true);
    set(repository, "playerStorage", playerStorage);
    set(repository, "skinStorage", skinStorage);

    repository.setSkinData(uuid, "legacy-value", "legacy-signature");

    assertEquals("starx-" + uuid.toString().replace("-", ""), skinStorage.savedIdentifier);
    assertEquals("legacy-value", skinStorage.savedValue);
    assertEquals("legacy-signature", skinStorage.savedSignature);
    assertEquals(skinStorage.savedIdentifier, playerStorage.saved);
  }

  @Test
  void writesTextureUsingConstructorOnlyCurrentApi() throws Exception {
    SkinsRestorerSkinRepository repository = new SkinsRestorerSkinRepository();
    ConstructorPlayerStorage playerStorage = new ConstructorPlayerStorage();
    ConstructorSkinStorage skinStorage = new ConstructorSkinStorage();
    UUID uuid = UUID.randomUUID();
    set(repository, "available", true);
    set(repository, "playerStorage", playerStorage);
    set(repository, "skinStorage", skinStorage);

    repository.setSkinData(uuid, "constructor-value", "constructor-signature");

    String expected = "starx-" + uuid.toString().replace("-", "");
    assertEquals(expected, skinStorage.savedIdentifier);
    assertEquals("constructor-value", skinStorage.savedProperty.value());
    assertEquals("constructor-signature", skinStorage.savedProperty.signature());
    assertEquals(expected, playerStorage.saved.identifier());
  }

  @Test
  void skipsPrimitiveOverloadWhenWritingNullSignature() throws Exception {
    SkinsRestorerSkinRepository repository = new SkinsRestorerSkinRepository();
    OverloadedPlayerStorage playerStorage = new OverloadedPlayerStorage();
    OverloadedSkinStorage skinStorage = new OverloadedSkinStorage();
    UUID uuid = UUID.randomUUID();
    set(repository, "available", true);
    set(repository, "playerStorage", playerStorage);
    set(repository, "skinStorage", skinStorage);

    repository.setSkinData(uuid, "value", null);

    String expected = "starx-" + uuid.toString().replace("-", "");
    assertEquals(expected, skinStorage.savedIdentifier);
    assertEquals("value", skinStorage.savedValue);
    assertNull(skinStorage.savedPrimitiveSignature);
    assertEquals(expected, playerStorage.saved);
  }

  @Test
  void prefersSpecificReferenceOverloadWhenWritingNullSignature() throws Exception {
    SkinsRestorerSkinRepository repository = new SkinsRestorerSkinRepository();
    LegacyPlayerStorage playerStorage = new LegacyPlayerStorage();
    ReferenceOverloadedSkinStorage skinStorage = new ReferenceOverloadedSkinStorage();
    UUID uuid = UUID.randomUUID();
    set(repository, "available", true);
    set(repository, "playerStorage", playerStorage);
    set(repository, "skinStorage", skinStorage);

    repository.setSkinData(uuid, "value", null);

    String expected = "starx-" + uuid.toString().replace("-", "");
    assertEquals(expected, skinStorage.savedIdentifier);
    assertEquals("value", skinStorage.savedValue);
    assertNull(skinStorage.savedObjectSignature);
  }

  @Test
  void continuesAfterSkinIdentifierConstructorFailure() throws Exception {
    SkinsRestorerSkinRepository repository = new SkinsRestorerSkinRepository();
    ConstructorFallbackPlayerStorage playerStorage = new ConstructorFallbackPlayerStorage();
    UUID uuid = UUID.randomUUID();
    set(repository, "available", true);
    set(repository, "playerStorage", playerStorage);

    repository.setSkinId(uuid, "skin");

    assertEquals("skin", playerStorage.saved.identifier());
  }

  @Test
  void continuesAfterSkinPropertyConstructorFailure() throws Exception {
    SkinsRestorerSkinRepository repository = new SkinsRestorerSkinRepository();
    LegacyPlayerStorage playerStorage = new LegacyPlayerStorage();
    ConstructorFallbackSkinStorage skinStorage = new ConstructorFallbackSkinStorage();
    UUID uuid = UUID.randomUUID();
    set(repository, "available", true);
    set(repository, "playerStorage", playerStorage);
    set(repository, "skinStorage", skinStorage);

    repository.setSkinData(uuid, "value", "signature");

    assertEquals("value", skinStorage.savedProperty.value());
    assertEquals("signature", skinStorage.savedProperty.signature());
  }

  @Test
  void ignoresTextureWriteWhenSkinStorageIsUnavailable() throws Exception {
    SkinsRestorerSkinRepository repository = new SkinsRestorerSkinRepository();
    LegacyPlayerStorage playerStorage = new LegacyPlayerStorage();
    UUID uuid = UUID.randomUUID();
    set(repository, "available", true);
    set(repository, "playerStorage", playerStorage);
    set(repository, "skinStorage", null);

    assertDoesNotThrow(() -> repository.setSkinData(uuid, "value", "signature"));
    assertNull(playerStorage.saved);
  }

  @Test
  void logsAReflectedWriteFailureOnlyOnce() throws Exception {
    Logger logger = Logger.getLogger("skin-repository-test-" + UUID.randomUUID());
    CapturingHandler logs = new CapturingHandler();
    logger.setUseParentHandlers(false);
    logger.addHandler(logs);
    try {
      SkinsRestorerSkinRepository repository = new SkinsRestorerSkinRepository(logger);
      set(repository, "available", true);
      set(repository, "playerStorage", new LegacyPlayerStorage());
      set(repository, "skinStorage", new BrokenSkinStorage());

      repository.setSkinData(UUID.randomUUID(), "value", "signature");
      repository.setSkinData(UUID.randomUUID(), "value", "signature");

      assertEquals(1, logs.count);
    } finally {
      logger.removeHandler(logs);
    }
  }

  @Test
  void degradesWhenPlayerStorageIsUnavailable() throws Exception {
    SkinsRestorerSkinRepository repository = new SkinsRestorerSkinRepository();
    set(repository, "available", true);
    set(repository, "playerStorage", null);
    set(repository, "skinStorage", new SkinStorage());

    assertTrue(repository.findByPlayer(UUID.randomUUID(), "player").isEmpty());
    assertDoesNotThrow(() -> repository.setSkinId(UUID.randomUUID(), "skin"));
    assertDoesNotThrow(() -> repository.setSkinData(UUID.randomUUID(), "value", "signature"));
    assertDoesNotThrow(() -> repository.clearSkin(UUID.randomUUID()));
  }

  @Test
  void rekeysExistingNonCustomIdentifierBeforeWritingTexture() throws Exception {
    SkinsRestorerSkinRepository repository = new SkinsRestorerSkinRepository();
    NonCustomPlayerStorage playerStorage = new NonCustomPlayerStorage();
    SkinStorage skinStorage = new SkinStorage();
    UUID uuid = UUID.randomUUID();
    set(repository, "available", true);
    set(repository, "playerStorage", playerStorage);
    set(repository, "skinStorage", skinStorage);

    repository.setSkinData(uuid, "texture-value", "texture-signature");

    String expected = "starx-" + uuid.toString().replace("-", "");
    assertEquals(expected, skinStorage.savedIdentifier);
    assertEquals(expected, playerStorage.saved.identifier);
    assertEquals("CUSTOM", playerStorage.saved.type);
  }

  private static void set(Object target, String name, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static URLClassLoader linkageFailingLoader() {
    URL location = SkinsRestorerSkinRepositoryTest.class
        .getProtectionDomain().getCodeSource().getLocation();
    String storageName = LinkageErrorStorage.class.getName();
    String missingTypeName = MissingSkinProperty.class.getName();
    ClassLoader parent = SkinsRestorerSkinRepositoryTest.class.getClassLoader();
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

  public static final class PlayerStorage {
    private SkinIdentifier saved;

    public Optional<SkinIdentifier> getSkinIdOfPlayer(UUID uuid) {
      return Optional.of(new SkinIdentifier("custom:starx-player"));
    }

    public void setSkinIdOfPlayer(UUID uuid, SkinIdentifier identifier) {
      this.saved = identifier;
    }
  }

  public static final class DirectPlayerStorage {
    private final UUID expected;

    DirectPlayerStorage(UUID expected) {
      this.expected = expected;
    }

    public Optional<SkinProperty> getSkinForPlayer(UUID uuid, String name) {
      return this.expected.equals(uuid)
          ? Optional.of(new SkinProperty("direct-value", "direct-signature"))
          : Optional.empty();
    }
  }

  public static final class StoredSkinPlayerStorage {
    private final UUID expected;

    StoredSkinPlayerStorage(UUID expected) {
      this.expected = expected;
    }

    public Optional<SkinProperty> getSkinOfPlayer(UUID uuid) {
      return this.expected.equals(uuid)
          ? Optional.of(new SkinProperty("stored-value", "stored-signature"))
          : Optional.empty();
    }

    public Optional<SkinProperty> getSkinForPlayer(UUID uuid, String name) {
      throw new AssertionError("network-backed lookup must not run when stored skin data exists");
    }
  }

  public static final class LegacyPlayerStorage {
    private String saved;

    public Optional<String> getSkinIdOfPlayer(UUID uuid) {
      return Optional.empty();
    }

    public void setSkinIdOfPlayer(UUID uuid, String identifier) {
      this.saved = identifier;
    }
  }

  public static final class NonCustomPlayerStorage {
    private NonCustomSkinIdentifier saved = new NonCustomSkinIdentifier("alex", "PLAYER");

    public Optional<NonCustomSkinIdentifier> getSkinIdOfPlayer(UUID uuid) {
      return Optional.of(this.saved);
    }

    public void setSkinIdOfPlayer(UUID uuid, NonCustomSkinIdentifier identifier) {
      this.saved = identifier;
    }
  }

  public static final class SkinStorage {
    private String savedIdentifier;
    private SkinProperty savedProperty;

    public Optional<Object> getSkinDataByIdentifier(SkinIdentifier identifier) {
      return Optional.empty();
    }

    public void setCustomSkinData(String identifier, SkinProperty property) {
      this.savedIdentifier = identifier;
      this.savedProperty = property;
    }
  }

  public static final class LegacySkinStorage {
    private String savedIdentifier;
    private String savedValue;
    private String savedSignature;

    public void setSkinData(String identifier, String value, String signature) {
      this.savedIdentifier = identifier;
      this.savedValue = value;
      this.savedSignature = signature;
    }
  }

  public static final class ConstructorPlayerStorage {
    private ConstructorSkinIdentifier saved;

    public Optional<ConstructorSkinIdentifier> getSkinIdOfPlayer(UUID uuid) {
      return Optional.empty();
    }

    public void setSkinIdOfPlayer(UUID uuid, ConstructorSkinIdentifier identifier) {
      this.saved = identifier;
    }
  }

  public static final class ConstructorSkinStorage {
    private String savedIdentifier;
    private ConstructorSkinProperty savedProperty;

    public void setCustomSkinData(String identifier, ConstructorSkinProperty property) {
      this.savedIdentifier = identifier;
      this.savedProperty = property;
    }
  }

  public static final class ConstructorFallbackPlayerStorage {
    private ConstructorFallbackSkinIdentifier saved;

    public Optional<ConstructorFallbackSkinIdentifier> getSkinIdOfPlayer(UUID uuid) {
      return Optional.empty();
    }

    public void setSkinIdOfPlayer(UUID uuid, ThrowingSkinIdentifier identifier) {
      throw new AssertionError("throwing identifier must not be invoked");
    }

    public void setSkinIdOfPlayer(UUID uuid, ConstructorFallbackSkinIdentifier identifier) {
      this.saved = identifier;
    }
  }

  public static final class ConstructorFallbackSkinStorage {
    private ConstructorFallbackSkinProperty savedProperty;

    public void setCustomSkinData(String identifier, ThrowingSkinProperty property) {
      throw new AssertionError("throwing property must not be invoked");
    }

    public void setCustomSkinData(String identifier, ConstructorFallbackSkinProperty property) {
      this.savedProperty = property;
    }
  }

  public static final class OverloadedPlayerStorage {
    private String saved;

    public Optional<String> getSkinIdOfPlayer(UUID uuid) {
      return Optional.empty();
    }

    public void setSkinIdOfPlayer(UUID uuid, String identifier) {
      this.saved = identifier;
    }
  }

  public static final class OverloadedSkinStorage {
    private String savedIdentifier;
    private String savedValue;
    private String savedPrimitiveSignature;

    public void setSkinData(String identifier, String value, int signature) {
      this.savedPrimitiveSignature = String.valueOf(signature);
    }

    public void setSkinData(String identifier, String value, String signature) {
      this.savedIdentifier = identifier;
      this.savedValue = value;
    }
  }

  public static final class ReferenceOverloadedSkinStorage {
    private String savedIdentifier;
    private String savedValue;
    private Object savedObjectSignature;

    public void setSkinData(String identifier, String value, Object signature) {
      this.savedObjectSignature = signature;
      throw new AssertionError("broader reference overload must not be selected");
    }

    public void setSkinData(String identifier, String value, String signature) {
      this.savedIdentifier = identifier;
      this.savedValue = value;
    }
  }

  public static final class BrokenSkinStorage {
  }

  public static final class SkinIdentifier {
    private final String identifier;

    SkinIdentifier(String identifier) {
      this.identifier = identifier;
    }

    public String getIdentifier() {
      return this.identifier;
    }

    public static SkinIdentifier ofCustom(String identifier) {
      return new SkinIdentifier(identifier);
    }
  }

  public static final class NonCustomSkinIdentifier {
    private final String identifier;
    private final String type;

    NonCustomSkinIdentifier(String identifier, String type) {
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

  public static final class SkinProperty {
    private final String value;
    private final String signature;

    private SkinProperty(String value, String signature) {
      this.value = value;
      this.signature = signature;
    }

    public static SkinProperty of(String value, String signature) {
      return new SkinProperty(value, signature);
    }

    public String getValue() {
      return this.value;
    }

    public String getSignature() {
      return this.signature;
    }
  }

  public static final class ConstructorSkinIdentifier {
    private final String identifier;

    private ConstructorSkinIdentifier(String identifier) {
      this.identifier = identifier;
    }

    public String identifier() {
      return this.identifier;
    }
  }

  public static final class ConstructorFallbackSkinIdentifier {
    private final String identifier;

    private ConstructorFallbackSkinIdentifier(String identifier) {
      this.identifier = identifier;
    }

    public String identifier() {
      return this.identifier;
    }
  }

  public static final class ThrowingSkinIdentifier {
    private ThrowingSkinIdentifier(String identifier) {
      throw new IllegalArgumentException("unsupported identifier");
    }
  }

  public record ConstructorFallbackSkinProperty(String value, String signature) {
  }

  public static final class ThrowingSkinProperty {
    private ThrowingSkinProperty(String value, String signature) {
      throw new IllegalArgumentException("unsupported property");
    }
  }

  public record ConstructorSkinProperty(String value, String signature) {
  }

  private static final class CapturingHandler extends Handler {
    private int count;

    @Override
    public void publish(LogRecord record) {
      this.count++;
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }
  }
}
