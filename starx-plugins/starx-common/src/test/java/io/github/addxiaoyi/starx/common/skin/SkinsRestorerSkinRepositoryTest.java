package io.github.addxiaoyi.starx.common.skin;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.addxiaoyi.starx.api.dto.SkinDto;
import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;
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
}
