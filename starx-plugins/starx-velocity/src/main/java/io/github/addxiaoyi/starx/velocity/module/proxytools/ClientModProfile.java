package io.github.addxiaoyi.starx.velocity.module.proxytools;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.util.ModInfo;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

public record ClientModProfile(
    Loader loader,
    String brand,
    Map<String, String> mods) {

  public ClientModProfile {
    loader = Objects.requireNonNull(loader, "loader");
    brand = brand == null ? "" : brand.trim();
    mods = Map.copyOf(Objects.requireNonNull(mods, "mods"));
  }

  public static ClientModProfile detect(Player player) {
    Objects.requireNonNull(player, "player");
    return detect(player.getClientBrand(), player.getModInfo());
  }

  static ClientModProfile detect(String brand, Optional<ModInfo> modInfo) {
    String normalizedBrand = normalize(brand);
    Map<String, String> mods = new LinkedHashMap<>();
    String type = "";
    if (modInfo != null && modInfo.isPresent()) {
      ModInfo info = modInfo.get();
      type = normalize(info.getType());
      for (ModInfo.Mod mod : info.getMods()) {
        String id = normalize(mod.getId());
        if (!id.isEmpty()) {
          mods.put(id, Objects.requireNonNullElse(mod.getVersion(), ""));
        }
      }
    }

    Set<String> ids = mods.keySet();
    Loader loader;
    if (containsAny(type, normalizedBrand, ids, "neoforge", "neo-forge", "neoforged")) {
      loader = Loader.NEOFORGE;
    } else if (containsAny(type, normalizedBrand, ids, "fabric", "fabricloader", "fabric-api", "quilt")) {
      loader = Loader.FABRIC;
    } else if (containsAny(type, normalizedBrand, ids, "forge", "fml", "fml2")) {
      loader = Loader.FORGE;
    } else if (!mods.isEmpty() || !isVanillaBrand(normalizedBrand)) {
      loader = Loader.UNKNOWN_MODDED;
    } else {
      loader = Loader.VANILLA;
    }
    return new ClientModProfile(loader, Objects.requireNonNullElse(brand, ""), mods);
  }

  public boolean modded() {
    return this.loader != Loader.VANILLA;
  }

  public Set<String> modIds() {
    return Set.copyOf(new TreeSet<>(this.mods.keySet()));
  }

  private static boolean containsAny(
      String type,
      String brand,
      Set<String> ids,
      String... candidates) {
    for (String candidate : candidates) {
      String normalized = normalize(candidate);
      if (type.contains(normalized) || brand.contains(normalized) || ids.contains(normalized)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isVanillaBrand(String brand) {
    return brand.isBlank()
        || "vanilla".equals(brand)
        || "minecraft".equals(brand)
        || brand.startsWith("vanilla");
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  public enum Loader {
    VANILLA,
    FORGE,
    NEOFORGE,
    FABRIC,
    UNKNOWN_MODDED
  }
}
