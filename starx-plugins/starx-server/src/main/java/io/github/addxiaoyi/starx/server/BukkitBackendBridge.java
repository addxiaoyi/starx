package io.github.addxiaoyi.starx.server;

import io.github.addxiaoyi.starx.api.bridge.BridgeMessage;
import io.github.addxiaoyi.starx.api.bridge.BridgeProtocol;
import java.util.Objects;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;

final class BukkitBackendBridge implements Listener, PluginMessageListener {
  private final StarxServerPlugin plugin;
  private final BackendBridgeSession session;

  BukkitBackendBridge(StarxServerPlugin plugin, BackendBridgeSession session) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.session = Objects.requireNonNull(session, "session");
  }

  void enable() {
    this.plugin.getServer().getMessenger().registerIncomingPluginChannel(
        this.plugin,
        BridgeProtocol.CHANNEL,
        this);
    this.plugin.getServer().getMessenger().registerOutgoingPluginChannel(
        this.plugin,
        BridgeProtocol.CHANNEL);
    this.plugin.getServer().getPluginManager().registerEvents(this, this.plugin);
  }

  void disable() {
    this.plugin.getServer().getMessenger().unregisterIncomingPluginChannel(
        this.plugin,
        BridgeProtocol.CHANNEL,
        this);
    this.plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(
        this.plugin,
        BridgeProtocol.CHANNEL);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onPlayerJoin(PlayerJoinEvent event) {
    if (event.getPlayer().getListeningPluginChannels().contains(BridgeProtocol.CHANNEL)) {
      this.send(event.getPlayer(), this.session.hello());
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onPlayerChannelRegistered(PlayerRegisterChannelEvent event) {
    if (isBridgeChannel(event.getChannel())) {
      this.send(event.getPlayer(), this.session.hello());
    }
  }

  @Override
  public void onPluginMessageReceived(String channel, Player player, byte[] payload) {
    if (!BridgeProtocol.CHANNEL.equals(channel)) {
      return;
    }
    try {
      BridgeMessage incoming = BridgeProtocol.decode(payload);
      this.session.receive(incoming).ifPresent(response -> this.send(player, response));
    } catch (IllegalArgumentException error) {
      this.plugin.getLogger().log(
          Level.WARNING,
          "Rejected invalid StarX bridge packet carried by {0}: {1}",
          new Object[] {player.getName(), error.getMessage()});
    }
  }

  private void send(Player player, BridgeMessage message) {
    player.sendPluginMessage(
        this.plugin,
        BridgeProtocol.CHANNEL,
        BridgeProtocol.encode(message));
  }

  static boolean isBridgeChannel(String channel) {
    return BridgeProtocol.CHANNEL.equals(channel);
  }
}
