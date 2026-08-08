package io.github.addxiaoyi.starx.common.skin;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
  void writesIdentifierToCurrentSkinsRestorerApi() throws Exception {
    SkinsRestorerSkinRepository repository = new SkinsRestorerSkinRepository();
    PlayerStorage storage = new PlayerStorage();
    set(repository, "available", true);
    set(repository, "playerStorage", storage);

    repository.setSkinId(UUID.randomUUID(), "custom:starx-player");

    assertEquals("custom:starx-player", storage.saved.identifier);
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

  public static final class SkinStorage {
    public Optional<Object> getSkinDataByIdentifier(SkinIdentifier identifier) {
      return Optional.empty();
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
}
