package net.skinsrestorer.api;

import java.util.Optional;
import java.util.UUID;

public final class SkinsRestorerProvider {
  private static final PlayerStorage PLAYER_STORAGE = new PlayerStorage();
  private static volatile boolean failSkinStorage;

  private SkinsRestorerProvider() {
  }

  public static Api get() {
    return new Api();
  }

  public static void setFailSkinStorage(boolean fail) {
    failSkinStorage = fail;
  }

  public static final class Api {
    public PlayerStorage getPlayerStorage() {
      return PLAYER_STORAGE;
    }

    public Object getSkinStorage() {
      if (failSkinStorage) {
        throw new IllegalStateException("optional skin storage unavailable");
      }
      return null;
    }
  }

  public static final class PlayerStorage {
    public Optional<SkinProperty> getSkinForPlayer(UUID uuid, String name) {
      return Optional.of(new SkinProperty());
    }
  }

  public static final class SkinProperty {
    public String getValue() {
      return "provider-value";
    }

    public String getSignature() {
      return "provider-signature";
    }
  }
}
