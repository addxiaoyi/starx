package io.github.addxiaoyi.starx.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class SkinsRestorerCurrentApiTest {

  @Test
  void readsCurrentGetSkinForPlayerApiWithoutLegacySkinStorage() {
    UUID uuid = UUID.randomUUID();
    SkinsRestorerBackendSkinResolver resolver = new SkinsRestorerBackendSkinResolver(
        new CurrentApi(uuid));

    BackendSkinProfile profile = resolver.find(uuid, "Alex").orElseThrow();

    assertEquals("skinsrestorer", profile.provider());
    assertEquals("current-value", profile.value());
    assertEquals("current-signature", profile.signature());
  }

  @Test
  void readsUuidOnlyPlayerApiWhenNamedLookupIsUnavailable() {
    UUID uuid = UUID.randomUUID();
    SkinsRestorerBackendSkinResolver resolver = new SkinsRestorerBackendSkinResolver(
        new UuidOnlyApi(uuid));

    BackendSkinProfile profile = resolver.find(uuid, "Alex").orElseThrow();

    assertEquals("uuid-only-value", profile.value());
    assertEquals("uuid-only-signature", profile.signature());
  }

  private record CurrentApi(UUID expected) {
    public CurrentPlayerStorage getPlayerStorage() {
      return new CurrentPlayerStorage(this.expected);
    }
    public Object getSkinStorage() { return null; }
  }

  private record UuidOnlyApi(UUID expected) {
    public UuidOnlyPlayerStorage getPlayerStorage() {
      return new UuidOnlyPlayerStorage(this.expected);
    }
    public Object getSkinStorage() { return null; }
  }

  private record CurrentPlayerStorage(UUID expected) {
    public Optional<CurrentSkinProperty> getSkinForPlayer(UUID uuid, String name) {
      return expected.equals(uuid) && "Alex".equals(name)
          ? Optional.of(new CurrentSkinProperty())
          : Optional.empty();
    }
  }

  private record UuidOnlyPlayerStorage(UUID expected) {
    public Optional<CurrentSkinProperty> getSkinOfPlayer(UUID uuid) {
      return expected.equals(uuid)
          ? Optional.of(new CurrentSkinProperty("uuid-only-value", "uuid-only-signature"))
          : Optional.empty();
    }
  }

  private static final class CurrentSkinProperty {
    private final String value;
    private final String signature;

    private CurrentSkinProperty() {
      this("current-value", "current-signature");
    }

    private CurrentSkinProperty(String value, String signature) {
      this.value = value;
      this.signature = signature;
    }

    public String getValue() { return this.value; }
    public String getSignature() { return this.signature; }
  }
}
