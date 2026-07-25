package io.github.addxiaoyi.starx.velocity.bridge;

import com.velocitypowered.api.command.Command;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.ChannelMessageSource;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import io.github.addxiaoyi.starx.api.bridge.BridgeMessage;
import io.github.addxiaoyi.starx.api.bridge.BridgeProtocol;
import io.github.addxiaoyi.starx.api.bridge.PlatformKind;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public final class VelocityBackendBridge implements VelocityModule {
  public static final String MODULE_ID = "starx.backend-bridge";
  public static final String PERMISSION = "starx.command.backend";

  private static final String COMMAND = "sxnodes";
  private static final Duration STALE_AFTER = Duration.ofMinutes(5);

  private final StarxVelocityPlugin plugin;
  private final BackendNodeRegistry registry;
  private final Clock clock;
  private final ChannelIdentifier channel;
  private final BackendCommandMailbox commandMailbox;
  private final Listener listener = new Listener();
  private CommandMeta commandMeta;
  private ScheduledTask refreshTask;
  private volatile Consumer<BridgeMessage> skinResponseConsumer = message -> { };
  private volatile BiConsumer<Player, RegisteredServer> backendReadyConsumer =
      (player, server) -> { };

  public enum DispatchResult {
    SENT,
    QUEUED_HTTP,
    MAILBOX_FULL,
    NO_SERVER,
    CARRIER_UNAVAILABLE;

    public boolean accepted() {
      return this == SENT || this == QUEUED_HTTP;
    }
  }

  public VelocityBackendBridge(StarxVelocityPlugin plugin) {
    this(plugin, new BackendNodeRegistry(), Clock.systemUTC());
  }

  VelocityBackendBridge(
      StarxVelocityPlugin plugin,
      BackendNodeRegistry registry,
      Clock clock
  ) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.channel = MinecraftChannelIdentifier.from(BridgeProtocol.CHANNEL);
    this.commandMailbox = new BackendCommandMailbox(64);
  }

  @Override
  public String name() {
    return MODULE_ID;
  }

  @Override
  public void onEnable() {
    this.plugin.proxy().getChannelRegistrar().register(this.channel);
    this.plugin.proxy().getEventManager().register(this.plugin, this.listener);
    this.commandMeta = this.plugin.proxy().getCommandManager()
        .metaBuilder(COMMAND)
        .plugin(this.plugin)
        .build();
    this.plugin.proxy().getCommandManager().register(
        this.commandMeta,
        (Command) new BackendCommand());
    this.refreshTask = this.plugin.proxy().getScheduler()
        .buildTask(this.plugin, this::refreshAllStatuses)
        .repeat(Duration.ofMinutes(1))
        .schedule();
  }

  @Override
  public void onDisable() {
    ScheduledTask currentRefresh = this.refreshTask;
    this.refreshTask = null;
    if (currentRefresh != null) {
      currentRefresh.cancel();
    }
    this.plugin.proxy().getEventManager().unregisterListener(this.plugin, this.listener);
    this.plugin.proxy().getChannelRegistrar().unregister(this.channel);
    CommandMeta current = this.commandMeta;
    this.commandMeta = null;
    if (current != null) {
      this.plugin.proxy().getCommandManager().unregister(current);
    }
    this.registry.clear();
    this.commandMailbox.clear();
    this.skinResponseConsumer = message -> { };
    this.backendReadyConsumer = (player, server) -> { };
  }

  public BackendNodeRegistry registry() {
    return this.registry;
  }

  public void onSkinResponse(Consumer<BridgeMessage> consumer) {
    this.skinResponseConsumer = Objects.requireNonNull(consumer, "consumer");
  }

  public void onBackendReady(BiConsumer<Player, RegisteredServer> consumer) {
    this.backendReadyConsumer = Objects.requireNonNull(consumer, "consumer");
  }

  public DispatchResult requestSkin(Player player) {
    return dispatchSkinRequest(
        player, this.channel, UUID.randomUUID().toString(), this.commandMailbox);
  }

  public DispatchResult requestSkin(Player player, RegisteredServer server) {
    return dispatchSkinRequest(
        player,
        server,
        this.channel,
        UUID.randomUUID().toString(),
        this.commandMailbox);
  }

  /** Queues a skin lookup even when no player is online to carry plugin messages. */
  public DispatchResult requestSkin(UUID playerUuid, String playerName) {
    Objects.requireNonNull(playerUuid, "playerUuid");
    if (playerName == null || playerName.isBlank()) {
      throw new IllegalArgumentException("playerName must not be blank");
    }
    DispatchResult fallback = DispatchResult.NO_SERVER;
    for (RegisteredServer server : this.plugin.proxy().getAllServers()) {
      DispatchResult result = dispatchSkinRequest(
          playerUuid,
          playerName,
          server,
          this.channel,
          UUID.randomUUID().toString(),
          this.commandMailbox);
      if (result.accepted()) {
        return result;
      }
      if (result != DispatchResult.NO_SERVER) {
        fallback = result;
      }
    }
    return fallback;
  }

  static DispatchResult dispatchSkinRequest(
      Player player,
      ChannelIdentifier channel,
      String correlationId
  ) {
    return dispatchSkinRequest(player, channel, correlationId, null);
  }

  static DispatchResult dispatchSkinRequest(
      Player player,
      ChannelIdentifier channel,
      String correlationId,
      BackendCommandMailbox mailbox
  ) {
    Objects.requireNonNull(player, "player");
    Optional<ServerConnection> current = player.getCurrentServer();
    if (current.isEmpty()) {
      return DispatchResult.NO_SERVER;
    }
    BridgeMessage request = BridgeMessage.skinRequest(
        "proxy", correlationId, player.getUniqueId().toString(), player.getUsername());
    ServerConnection connection = current.get();
    boolean sent = connection.sendPluginMessage(channel, BridgeProtocol.encode(request));
    if (sent) {
      return DispatchResult.SENT;
    }
    if (mailbox == null) {
      return DispatchResult.CARRIER_UNAVAILABLE;
    }
    String serverName = connection.getServerInfo().getName();
    return mailbox.offer(serverName, request)
        ? DispatchResult.QUEUED_HTTP
        : DispatchResult.MAILBOX_FULL;
  }

  static DispatchResult dispatchSkinRequest(
      UUID playerUuid,
      String playerName,
      RegisteredServer server,
      ChannelIdentifier channel,
      String correlationId,
      BackendCommandMailbox mailbox
  ) {
    Objects.requireNonNull(playerUuid, "playerUuid");
    Objects.requireNonNull(server, "server");
    if (playerName == null || playerName.isBlank()) {
      throw new IllegalArgumentException("playerName must not be blank");
    }
    BridgeMessage request = BridgeMessage.skinRequest(
        "proxy", correlationId, playerUuid.toString(), playerName.trim());
    boolean sent = server.sendPluginMessage(channel, BridgeProtocol.encode(request));
    if (sent) {
      return DispatchResult.SENT;
    }
    String serverName = server.getServerInfo().getName();
    return mailbox.offer(serverName, request)
        ? DispatchResult.QUEUED_HTTP
        : DispatchResult.MAILBOX_FULL;
  }

  static DispatchResult dispatchSkinRequest(
      Player player,
      RegisteredServer server,
      ChannelIdentifier channel,
      String correlationId,
      BackendCommandMailbox mailbox
  ) {
    Objects.requireNonNull(player, "player");
    Objects.requireNonNull(server, "server");
    BridgeMessage request = BridgeMessage.skinRequest(
        "proxy", correlationId, player.getUniqueId().toString(), player.getUsername());
    boolean sent = server.sendPluginMessage(channel, BridgeProtocol.encode(request));
    if (sent) {
      return DispatchResult.SENT;
    }
    if (mailbox == null) {
      return DispatchResult.CARRIER_UNAVAILABLE;
    }
    String serverName = server.getServerInfo().getName();
    return mailbox.offer(serverName, request)
        ? DispatchResult.QUEUED_HTTP
        : DispatchResult.MAILBOX_FULL;
  }

  public BackendCommandMailbox commandMailbox() {
    return this.commandMailbox;
  }

  public Map<String, DispatchResult> broadcastMaintenance(boolean enabled) {
    Map<String, DispatchResult> results = new LinkedHashMap<>();
    for (RegisteredServer server : this.plugin.proxy().getAllServers()) {
      String name = server.getServerInfo().getName();
      results.put(name, dispatchMaintenance(
          server,
          this.channel,
          UUID.randomUUID().toString(),
          enabled,
          this.commandMailbox));
    }
    return Map.copyOf(results);
  }

  static DispatchResult dispatchMaintenance(
      RegisteredServer server,
      ChannelIdentifier channel,
      String correlationId,
      boolean enabled,
      BackendCommandMailbox mailbox
  ) {
    Objects.requireNonNull(server, "server");
    Objects.requireNonNull(channel, "channel");
    Objects.requireNonNull(mailbox, "mailbox");
    BridgeMessage command = BridgeMessage.maintenanceConfig(
        "proxy", correlationId, enabled);
    if (server.sendPluginMessage(channel, BridgeProtocol.encode(command))) {
      return DispatchResult.SENT;
    }
    String serverName = server.getServerInfo().getName();
    return mailbox.offer(serverName, command)
        ? DispatchResult.QUEUED_HTTP
        : DispatchResult.MAILBOX_FULL;
  }

  public void acceptHttpMessage(BridgeMessage message) {
    Objects.requireNonNull(message, "message");
    if (!BridgeProtocol.SKIN_RESPONSE.equals(message.type())) {
      throw new IllegalArgumentException(
          "Unsupported HTTP backend bridge response: " + message.type());
    }
    this.skinResponseConsumer.accept(message);
  }

  static int refreshStatuses(
      Collection<RegisteredServer> servers,
      ChannelIdentifier channel,
      Supplier<String> correlationIds
  ) {
    int sent = 0;
    for (RegisteredServer server : servers) {
      BridgeMessage request = BridgeMessage.statusRequest("proxy", correlationIds.get());
      if (server.sendPluginMessage(channel, BridgeProtocol.encode(request))) {
        sent++;
      }
    }
    return sent;
  }

  private void refreshAllStatuses() {
    Collection<RegisteredServer> servers = this.plugin.proxy().getAllServers();
    servers.forEach(server -> {
      String name = server.getServerInfo().getName();
      this.registry.observeServer(name);
      this.registry.find(name).ifPresent(node -> {
        if (node.isStale(this.clock.instant(), STALE_AFTER)) {
          this.registry.markHeartbeatMissed(name);
        }
      });
    });
    refreshStatuses(servers, this.channel, () -> UUID.randomUUID().toString());
  }

  static boolean isTrustedSource(ChannelMessageSource source) {
    return source instanceof ServerConnection;
  }

  static Optional<BridgeMessage> followUpFor(
      BridgeMessage message,
      String correlationId
  ) {
    Objects.requireNonNull(message, "message");
    if (!BridgeProtocol.BACKEND_HELLO.equals(message.type())) {
      return Optional.empty();
    }
    if (correlationId == null || correlationId.isBlank()) {
      throw new IllegalArgumentException("correlationId must not be blank");
    }
    return Optional.of(BridgeMessage.statusRequest("proxy", correlationId));
  }

  static BridgeMessage markTransport(BridgeMessage message, String transport) {
    Objects.requireNonNull(message, "message");
    if (transport == null || transport.isBlank()) {
      throw new IllegalArgumentException("transport must not be blank");
    }
    Map<String, String> attributes = new LinkedHashMap<>(message.attributes());
    attributes.put("transport", transport.trim());
    return new BridgeMessage(
        message.type(),
        message.nodeId(),
        message.platform(),
        message.correlationId(),
        attributes);
  }

  private void onServerConnected(ServerConnectedEvent event) {
    RegisteredServer server = event.getServer();
    this.registry.observeServer(server.getServerInfo().getName());
    BridgeMessage hello = BridgeMessage.hello("proxy", PlatformKind.VELOCITY);
    BridgeMessage status = BridgeMessage.statusRequest(
        "proxy",
        UUID.randomUUID().toString());
    boolean helloSent = server.sendPluginMessage(this.channel, BridgeProtocol.encode(hello));
    boolean statusSent = server.sendPluginMessage(this.channel, BridgeProtocol.encode(status));
    if (!helloSent || !statusSent) {
      this.plugin.logger().log(
          Level.FINE,
          "Backend bridge carrier was unavailable for {0}",
          server.getServerInfo().getName());
    }
  }

  private void onPluginMessage(PluginMessageEvent event) {
    if (!event.getIdentifier().equals(this.channel)) {
      return;
    }
    event.setResult(PluginMessageEvent.ForwardResult.handled());
    if (!(event.getSource() instanceof ServerConnection connection)) {
      this.plugin.logger().log(Level.WARNING,
          "Rejected player-originated StarX backend bridge packet");
      return;
    }

    String registeredName = connection.getServerInfo().getName();
    try {
      BridgeMessage message = BridgeProtocol.decode(event.getData());
      if (BridgeProtocol.BACKEND_HELLO.equals(message.type())) {
        this.backendReadyConsumer.accept(connection.getPlayer(), connection.getServer());
      }
      if (BridgeProtocol.SKIN_RESPONSE.equals(message.type())) {
        this.skinResponseConsumer.accept(message);
        return;
      }
      BridgeMessage carried = markTransport(message, "player-carrier");
      this.registry.update(registeredName, carried, this.clock.instant());
      followUpFor(carried, UUID.randomUUID().toString()).ifPresent(followUp -> {
        boolean sent = connection.sendPluginMessage(
            this.channel,
            BridgeProtocol.encode(followUp));
        if (!sent) {
          this.plugin.logger().log(
              Level.FINE,
              "Backend status follow-up had no carrier for {0}",
              registeredName);
        }
      });
    } catch (IllegalArgumentException error) {
      this.plugin.logger().log(
          Level.WARNING,
          "Rejected StarX backend bridge packet from {0}: {1}",
          new Object[] {registeredName, error.getMessage()});
    }
  }

  private final class Listener {
    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
      VelocityBackendBridge.this.onServerConnected(event);
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
      VelocityBackendBridge.this.onPluginMessage(event);
    }
  }

  private final class BackendCommand implements SimpleCommand {
    @Override
    public void execute(Invocation invocation) {
      String[] args = invocation.arguments();
      if (args.length == 0 || "status".equalsIgnoreCase(args[0])) {
        if (args.length >= 2) {
          this.sendDetails(invocation, args[1]);
        } else {
          this.sendOverview(invocation);
        }
        return;
      }
      invocation.source().sendMessage(Component.text(
          "Usage: /" + invocation.alias() + " status [server]",
          NamedTextColor.RED));
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
      return invocation.source().hasPermission(PERMISSION);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
      String[] args = invocation.arguments();
      if (args.length <= 1) {
        return List.of("status");
      }
      if (args.length == 2 && "status".equalsIgnoreCase(args[0])) {
        String prefix = args[1].toLowerCase(Locale.ROOT);
        return registry.serverNames().stream()
            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
            .sorted()
            .toList();
      }
      return List.of();
    }

    private void sendOverview(Invocation invocation) {
      List<String> servers = registry.serverNames();
      invocation.source().sendMessage(Component.text(
          "StarX backend nodes (" + servers.size() + ")",
          NamedTextColor.GOLD));
      Instant now = clock.instant();
      for (String name : servers) {
        BackendNode node = registry.find(name).orElse(null);
        if (node == null) {
          invocation.source().sendMessage(Component.text(
              " - " + name + ": UNSEEN (waiting for a player carrier)",
              NamedTextColor.GRAY));
          continue;
        }
        boolean stale = node.isStale(now, STALE_AFTER);
        NamedTextColor color = stale ? NamedTextColor.YELLOW : NamedTextColor.GREEN;
        invocation.source().sendMessage(Component.text(
            " - " + name + ": " + (stale ? "STALE" : "LINKED")
                + " " + node.platform()
                + " players=" + formatCount(node.onlinePlayers(), node.maxPlayers()),
            color));
      }
    }

    private void sendDetails(Invocation invocation, String serverName) {
      BackendNode node = registry.find(serverName).orElse(null);
      if (node == null) {
        invocation.source().sendMessage(Component.text(
            "No StarX bridge report for registered server " + serverName,
            NamedTextColor.RED));
        return;
      }
      invocation.source().sendMessage(Component.text(
          "StarX backend " + node.registeredServer(), NamedTextColor.GOLD));
      invocation.source().sendMessage(Component.text(
          "Declared node: " + node.declaredNodeId(), NamedTextColor.GRAY));
      invocation.source().sendMessage(Component.text(
          "Platform: " + node.platform(), NamedTextColor.GRAY));
      invocation.source().sendMessage(Component.text(
          "Players: " + formatCount(node.onlinePlayers(), node.maxPlayers()),
          NamedTextColor.GRAY));
      invocation.source().sendMessage(Component.text(
          "Capabilities: " + String.join(", ", new java.util.TreeSet<>(node.capabilities())),
          NamedTextColor.GRAY));
      invocation.source().sendMessage(Component.text(
          "Last seen: " + node.lastSeen(), NamedTextColor.GRAY));
    }

    private String formatCount(int online, int max) {
      String onlineText = online < 0 ? "?" : Integer.toString(online);
      String maxText = max < 0 ? "?" : Integer.toString(max);
      return onlineText + "/" + maxText;
    }
  }
}
