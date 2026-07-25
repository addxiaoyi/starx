package io.github.addxiaoyi.starx.velocity.module.proxytools;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.config.StarxConfig;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public final class ForgeCompatModule implements VelocityModule {

  private final StarxVelocityPlugin plugin;
  private final Config config;
  private final ModCompatibilityPolicy policy;
  private final ConcurrentHashMap<UUID, ClientModProfile> profiles = new ConcurrentHashMap<>();
  private Listener listener;

  public ForgeCompatModule(StarxVelocityPlugin plugin, Config config) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.config = Objects.requireNonNull(config, "config");
    this.policy = new ModCompatibilityPolicy(
        config.vanillaServers(),
        config.allowedClientOnlyMods(),
        config.deniedMods(),
        config.unknownAction());
  }

  @Override
  public String name() {
    return "starx.forge";
  }

  @Override
  public void onEnable() {
    if (!this.config.enabled()) {
      return;
    }
    Listener current = new Listener();
    this.listener = current;
    this.plugin.proxy().getEventManager().register(this.plugin, current);
    this.plugin.logger().info(
        "Mod compatibility active: Forge/NeoForge/Fabric detection, vanilla guard="
            + this.config.guardEnabled() + ", unknown-action=" + this.config.unknownAction());
  }

  @Override
  public void onDisable() {
    Listener current = this.listener;
    this.listener = null;
    if (current != null) {
      this.plugin.proxy().getEventManager().unregisterListener(this.plugin, current);
    }
    this.profiles.clear();
  }

  void onPostLogin(PostLoginEvent event) {
    Player player = event.getPlayer();
    ClientModProfile profile = ClientModProfile.detect(player);
    this.profiles.put(player.getUniqueId(), profile);
    if (this.config.debug()) {
      this.plugin.logger().info(
          "Client platform " + player.getUsername() + ": loader=" + profile.loader()
              + ", brand=" + profile.brand() + ", mods=" + profile.modIds());
    }
  }

  void onServerPreConnect(ServerPreConnectEvent event) {
    if (!this.config.guardEnabled() || !event.getResult().isAllowed()) {
      return;
    }
    RegisteredServer server = event.getResult().getServer().orElse(event.getOriginalServer());
    ClientModProfile profile = this.profiles.computeIfAbsent(
        event.getPlayer().getUniqueId(), ignored -> ClientModProfile.detect(event.getPlayer()));
    ModCompatibilityPolicy.Decision decision =
        this.policy.evaluate(server.getServerInfo().getName(), profile);
    if (decision.action() == ModCompatibilityPolicy.Action.ALLOW) {
      return;
    }

    String detail = decision.flaggedMods().isEmpty()
        ? decision.reason()
        : decision.reason() + ": " + String.join(", ", decision.flaggedMods());
    if (decision.action() == ModCompatibilityPolicy.Action.WARN) {
      this.plugin.logger().warning(
          "Allowing modded client on configured vanilla server with warning: player="
              + event.getPlayer().getUsername() + ", server=" + server.getServerInfo().getName()
              + ", loader=" + profile.loader() + ", " + detail);
      return;
    }

    event.setResult(ServerPreConnectEvent.ServerResult.denied());
    event.getPlayer().sendMessage(
        Component.text("该纯净服务器未确认兼容你的部分模组。", NamedTextColor.RED)
            .append(Component.newline())
            .append(Component.text(
                "请使用允许的客户端辅助模组，或联系管理员检查：" + detail,
                NamedTextColor.GRAY)));
  }

  void onDisconnect(DisconnectEvent event) {
    this.profiles.remove(event.getPlayer().getUniqueId());
  }

  public static final class Config {
    private final boolean enabled;
    private final boolean debug;
    private final boolean guardEnabled;
    private final Set<String> vanillaServers;
    private final Set<String> allowedClientOnlyMods;
    private final Set<String> deniedMods;
    private final ModCompatibilityPolicy.Action unknownAction;

    private Config(
        boolean enabled,
        boolean debug,
        boolean guardEnabled,
        Set<String> vanillaServers,
        Set<String> allowedClientOnlyMods,
        Set<String> deniedMods,
        ModCompatibilityPolicy.Action unknownAction) {
      this.enabled = enabled;
      this.debug = debug;
      this.guardEnabled = guardEnabled;
      this.vanillaServers = Set.copyOf(vanillaServers);
      this.allowedClientOnlyMods = Set.copyOf(allowedClientOnlyMods);
      this.deniedMods = Set.copyOf(deniedMods);
      this.unknownAction = Objects.requireNonNull(unknownAction, "unknownAction");
    }

    public static Config from(StarxConfig.ModuleConfig module) {
      if (module == null) {
        return defaultConfig();
      }
      Set<String> allowed = new LinkedHashSet<>(ModCompatibilityPolicy.defaultAllowedClientOnlyMods());
      allowed.addAll(module.stringSet("allowed-client-only-mods", Set.of()));
      return new Config(
          module.enabled(),
          module.booleanOption("debug", false),
          module.booleanOption("guard-enabled", false),
          module.stringSet("vanilla-servers", Set.of()),
          allowed,
          module.stringSet("denied-mods", Set.of()),
          ModCompatibilityPolicy.Action.parse(module.stringOption("unknown-action", "WARN")));
    }

    public static Config defaultConfig() {
      return new Config(
          true,
          false,
          false,
          Set.of(),
          ModCompatibilityPolicy.defaultAllowedClientOnlyMods(),
          Set.of(),
          ModCompatibilityPolicy.Action.WARN);
    }

    public boolean enabled() {
      return this.enabled;
    }

    public boolean debug() {
      return this.debug;
    }

    public boolean guardEnabled() {
      return this.guardEnabled;
    }

    public Set<String> vanillaServers() {
      return this.vanillaServers;
    }

    public Set<String> allowedClientOnlyMods() {
      return this.allowedClientOnlyMods;
    }

    public Set<String> deniedMods() {
      return this.deniedMods;
    }

    public ModCompatibilityPolicy.Action unknownAction() {
      return this.unknownAction;
    }
  }

  private final class Listener {
    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
      ForgeCompatModule.this.onPostLogin(event);
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
      ForgeCompatModule.this.onServerPreConnect(event);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
      ForgeCompatModule.this.onDisconnect(event);
    }
  }
}
