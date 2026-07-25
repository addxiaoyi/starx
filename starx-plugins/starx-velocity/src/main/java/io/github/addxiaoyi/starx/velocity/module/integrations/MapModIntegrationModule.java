package io.github.addxiaoyi.starx.velocity.module.integrations;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.Player;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.config.StarxConfig;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import io.github.addxiaoyi.starx.velocity.module.proxytools.ClientModProfile;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public final class MapModIntegrationModule implements VelocityModule {

  private final StarxVelocityPlugin plugin;
  private final Config config;
  private final ConcurrentHashMap<UUID, Set<String>> detected = new ConcurrentHashMap<>();
  private Listener listener;

  public MapModIntegrationModule(StarxVelocityPlugin plugin, Config config) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.config = Objects.requireNonNull(config, "config");
  }

  @Override
  public String name() {
    return "starx.integrations.mapmod";
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
        "Map compatibility catalog active for JourneyMap, Xaero, VoxelMap, BlueMap, Dynmap, "
            + "squaremap, Pl3xMap, OpenPAC and FTB Chunks; plugin channels remain transparent");
  }

  @Override
  public void onDisable() {
    Listener current = this.listener;
    this.listener = null;
    if (current != null) {
      this.plugin.proxy().getEventManager().unregisterListener(this.plugin, current);
    }
    this.detected.clear();
  }

  void onPostLogin(PostLoginEvent event) {
    Player player = event.getPlayer();
    Set<String> maps = MapIntegrationCatalog.detectClientMaps(ClientModProfile.detect(player));
    if (maps.isEmpty()) {
      return;
    }
    this.detected.put(player.getUniqueId(), maps);
    if (this.config.debug()) {
      this.plugin.logger().info(
          "Detected client map mods for " + player.getUsername() + ": " + maps);
    }
    if (this.config.notifyPlayer()) {
      player.sendMessage(Component.text(
          "已识别客户端地图模组：" + String.join(", ", maps)
              + "。StarX 不拦截其正常后端通道。",
          NamedTextColor.GRAY));
    }
  }

  void onDisconnect(DisconnectEvent event) {
    this.detected.remove(event.getPlayer().getUniqueId());
  }

  Set<String> detected(UUID playerId) {
    return this.detected.getOrDefault(playerId, Set.of());
  }

  public static final class Config {
    private final boolean enabled;
    private final boolean debug;
    private final boolean notifyPlayer;

    private Config(boolean enabled, boolean debug, boolean notifyPlayer) {
      this.enabled = enabled;
      this.debug = debug;
      this.notifyPlayer = notifyPlayer;
    }

    public static Config from(StarxConfig.ModuleConfig module) {
      if (module == null) {
        return defaultConfig();
      }
      return new Config(
          module.enabled(),
          module.booleanOption("debug", false),
          module.booleanOption("notify-player", false));
    }

    public static Config defaultConfig() {
      return new Config(false, false, false);
    }

    public boolean enabled() {
      return this.enabled;
    }

    public boolean debug() {
      return this.debug;
    }

    public boolean notifyPlayer() {
      return this.notifyPlayer;
    }
  }

  private final class Listener {
    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
      MapModIntegrationModule.this.onPostLogin(event);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
      MapModIntegrationModule.this.onDisconnect(event);
    }
  }
}
