package io.github.addxiaoyi.starx.velocity.module.proxytools;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

final class ModCompatibilityPolicy {

  private static final Set<String> LOADER_IDS = Set.of(
      "minecraft", "forge", "fml", "fml2", "neoforge", "neo-forge",
      "fabricloader", "fabric-api", "quilt_loader");

  private final Set<String> vanillaServers;
  private final Set<String> allowedClientOnlyMods;
  private final Set<String> deniedMods;
  private final Action unknownAction;

  ModCompatibilityPolicy(
      Set<String> vanillaServers,
      Set<String> allowedClientOnlyMods,
      Set<String> deniedMods,
      Action unknownAction) {
    this.vanillaServers = normalize(vanillaServers);
    this.allowedClientOnlyMods = normalize(allowedClientOnlyMods);
    this.deniedMods = normalize(deniedMods);
    this.unknownAction = Objects.requireNonNull(unknownAction, "unknownAction");
  }

  Decision evaluate(String serverName, ClientModProfile profile) {
    Objects.requireNonNull(profile, "profile");
    String server = normalize(serverName);
    if (!profile.modded() || !this.vanillaServers.contains(server)) {
      return Decision.allow();
    }

    Set<String> explicitDenied = intersection(profile.modIds(), this.deniedMods);
    if (!explicitDenied.isEmpty()) {
      return new Decision(Action.DENY, "包含服务器明确拒绝的模组", explicitDenied);
    }

    Set<String> unknown = new TreeSet<>(profile.modIds());
    unknown.removeAll(LOADER_IDS);
    unknown.removeAll(this.allowedClientOnlyMods);
    if (unknown.isEmpty()) {
      return Decision.allow();
    }
    return new Decision(
        this.unknownAction,
        "纯净服未确认兼容的客户端模组",
        Set.copyOf(unknown));
  }

  static Set<String> defaultAllowedClientOnlyMods() {
    return Set.of(
        "appleskin",
        "betterf3",
        "cloth-config",
        "entityculling",
        "fabric-api",
        "fabricloader",
        "iris",
        "jade",
        "jei",
        "journeymap",
        "litematica",
        "malilib",
        "minihud",
        "modmenu",
        "notenoughanimations",
        "optifine",
        "replaymod",
        "sodium",
        "voxelmap",
        "xaerominimap",
        "xaeroworldmap");
  }

  private static Set<String> intersection(Set<String> left, Set<String> right) {
    Set<String> result = new TreeSet<>(left);
    result.retainAll(right);
    return Set.copyOf(result);
  }

  private static Set<String> normalize(Set<String> values) {
    Set<String> normalized = new LinkedHashSet<>();
    if (values != null) {
      for (String value : values) {
        String clean = normalize(value);
        if (!clean.isEmpty()) {
          normalized.add(clean);
        }
      }
    }
    return Set.copyOf(normalized);
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  enum Action {
    ALLOW,
    WARN,
    DENY;

    static Action parse(String value) {
      try {
        return value == null
            ? WARN
            : Action.valueOf(value.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException error) {
        return WARN;
      }
    }
  }

  record Decision(Action action, String reason, Set<String> flaggedMods) {
    Decision {
      action = Objects.requireNonNull(action, "action");
      reason = Objects.requireNonNullElse(reason, "");
      flaggedMods = Set.copyOf(Objects.requireNonNull(flaggedMods, "flaggedMods"));
    }

    static Decision allow() {
      return new Decision(Action.ALLOW, "", Set.of());
    }
  }
}
