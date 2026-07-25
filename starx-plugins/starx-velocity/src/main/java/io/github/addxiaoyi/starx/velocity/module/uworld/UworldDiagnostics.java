package io.github.addxiaoyi.starx.velocity.module.uworld;

import com.velocitypowered.api.command.Command;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import io.github.addxiaoyi.starx.chunk.VirtualBlock;
import io.github.addxiaoyi.starx.uworld.UworldEnterResult;
import io.github.addxiaoyi.starx.uworld.UworldFlowHandler;
import io.github.addxiaoyi.starx.uworld.UworldFlowOptions;
import io.github.addxiaoyi.starx.uworld.UworldFlowSession;
import io.github.addxiaoyi.starx.uworld.UworldHandle;
import io.github.addxiaoyi.starx.uworld.UworldOutcome;
import io.github.addxiaoyi.starx.uworld.UworldRuntime;
import io.github.addxiaoyi.starx.uworld.UworldSpec;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.config.UworldConfig;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public final class UworldDiagnostics implements VelocityModule {

  public static final String MODULE_ID = "starx.uworld.diagnostics";
  public static final String PERMISSION = "starx.uworld.diagnostics";

  private static final String OWNER = "starx.diagnostics";
  private static final String WORLD_NAME = "diagnostics";
  private static final int FULL_SKY_LIGHT = 15;

  private final UworldRuntime runtime;
  private final UworldConfig config;
  private final CommandAccess commands;
  private final Logger logger;
  private final RegisteredServer fallbackServer;
  private final UworldDiagnosticsState<Player, RegisteredServer, UworldFlowSession> state =
      new UworldDiagnosticsState<>();

  private volatile UworldHandle world;

  public UworldDiagnostics(
      StarxVelocityPlugin plugin,
      UworldRuntime runtime,
      UworldConfig config
  ) {
    this(
        runtime,
        config,
        new VelocityCommandAccess(Objects.requireNonNull(plugin, "plugin")),
        plugin.logger());
  }

  UworldDiagnostics(
      UworldRuntime runtime,
      UworldConfig config,
      CommandAccess commands,
      Logger logger
  ) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.config = Objects.requireNonNull(config, "config");
    this.commands = Objects.requireNonNull(commands, "commands");
    this.logger = Objects.requireNonNull(logger, "logger");
    this.fallbackServer = this.commands.resolve(this.config.auth().targetServer()).orElse(null);
  }

  @Override
  public String name() {
    return MODULE_ID;
  }

  @Override
  public void onEnable() {
    this.commands.register(new UworldCommand());
  }

  @Override
  public void onDisable() {
    IllegalStateException failure = null;
    try {
      this.commands.unregister();
    } catch (RuntimeException error) {
      failure = new IllegalStateException("Unable to unregister Uworld diagnostics", error);
    }
    this.state.clear();
    UworldHandle current = this.world;
    if (current != null) {
      try {
        current.closeAsync(Component.text("Uworld 诊断正在停止"))
            .toCompletableFuture()
            .join();
        if (this.world == current) {
          this.world = null;
        }
      } catch (RuntimeException error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null
            ? error.getCause()
            : error;
        IllegalStateException closeFailure = new IllegalStateException(
            "Unable to close the diagnostics Uworld", cause);
        if (failure == null) {
          failure = closeFailure;
        } else {
          failure.addSuppressed(closeFailure);
        }
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  UworldDiagnosticsState<Player, RegisteredServer, UworldFlowSession> state() {
    return this.state;
  }

  private void execute(CommandSource source, String[] arguments) {
    if (!source.hasPermission(PERMISSION)) {
      source.sendMessage(Component.text(
          "You do not have permission to use Uworld diagnostics.",
          NamedTextColor.RED));
      return;
    }
    if (arguments.length != 1) {
      sendUsage(source);
      return;
    }

    String subcommand = arguments[0].toLowerCase(Locale.ROOT);
    if ("status".equals(subcommand)) {
      this.sendStatus(source);
      return;
    }
    if (!(source instanceof Player player)) {
      source.sendMessage(Component.text(
          "This Uworld command requires a connected player.",
          NamedTextColor.RED));
      return;
    }
    if (!this.config.diagnostics().enabled()) {
      source.sendMessage(Component.text(
          "Uworld diagnostics are disabled in configuration.",
          NamedTextColor.YELLOW));
      return;
    }

    switch (subcommand) {
      case "test" -> this.enter(player);
      case "leave" -> this.leave(player);
      default -> sendUsage(source);
    }
  }

  private void sendStatus(CommandSource source) {
    String runtimeState = this.runtime.isReady() ? "ready" : "stopped";
    source.sendMessage(Component.text(
        "Uworld runtime=" + runtimeState
            + " worlds=" + this.runtime.worldCount()
            + " sessions=" + this.runtime.sessionCount(),
        NamedTextColor.AQUA));
  }

  private void enter(Player player) {
    if (!this.runtime.isReady()) {
      player.sendMessage(Component.text("Uworld 运行时不可用。", NamedTextColor.RED));
      return;
    }
    if (this.runtime.session(player).isPresent()) {
      player.sendMessage(Component.text(
          "You already have an active Uworld session.",
          NamedTextColor.YELLOW));
      return;
    }

    Optional<RegisteredServer> previous = this.commands.currentServer(player);
    this.state.begin(player, previous.orElse(null));

    UworldFlowOptions options = new UworldFlowOptions(
        Duration.ofSeconds(this.config.diagnostics().timeoutSeconds()),
        Duration.ofSeconds(this.config.transferTimeoutSeconds()));
    player.sendMessage(Component.text("正在进入 Uworld 诊断世界……", NamedTextColor.GREEN));
    UworldEnterResult result;
    try {
      result = this.world().enter(player, options, new DiagnosticsHandler(player));
    } catch (RuntimeException error) {
      this.state.remove(player);
      this.logger.log(Level.SEVERE,
          "Unable to create or enter the diagnostics Uworld for " + player.getUsername(),
          error);
      player.sendMessage(Component.text(
          "Unable to start the diagnostics Uworld.",
          NamedTextColor.RED));
      return;
    }
    if (result instanceof UworldEnterResult.Rejected rejected) {
      this.state.remove(player);
      player.sendMessage(Component.text(
          "Unable to enter the diagnostics Uworld: " + rejected.status(),
          NamedTextColor.RED));
      return;
    }
    UworldFlowSession session = ((UworldEnterResult.Accepted) result).session();
    if (!this.state.bind(player, session)) {
      session.cancel(Component.text("诊断状态不可用"));
      this.state.remove(player);
      player.sendMessage(Component.text(
          "Unable to track the diagnostics Uworld session.",
          NamedTextColor.RED));
      return;
    }
  }

  private void leave(Player player) {
    UworldFlowSession session = this.runtime.session(player).orElse(null);
    if (session == null) {
      this.state.remove(player);
      player.sendMessage(Component.text(
          "You do not have an active Uworld session.",
          NamedTextColor.YELLOW));
      return;
    }
    if (!this.state.owns(player, session)) {
      player.sendMessage(Component.text(
          "The active Uworld session is not a diagnostics session.",
          NamedTextColor.YELLOW));
      return;
    }

    RegisteredServer previous = this.state.returnTarget(player, null);
    RegisteredServer expected = previous == null ? this.fallbackServer : previous;
    RegisteredServer target = expected == null
        ? null
        : this.commands.resolve(expected.getServerInfo().getName())
            .filter(registered -> registered == expected)
            .orElse(null);
    if (target == null) {
      Component reason = Component.text(
          "The Uworld return server is unavailable.",
          NamedTextColor.RED);
      player.sendMessage(reason);
      if (!session.fail(reason)) {
        this.state.finish(player, session);
        player.sendMessage(Component.text(
            "The Uworld session is no longer active.",
            NamedTextColor.YELLOW));
      }
      return;
    }
    if (!session.complete(target)) {
      player.sendMessage(Component.text(
          "The Uworld session is no longer able to transfer.",
          NamedTextColor.RED));
      return;
    }
    this.state.finish(player, session);
  }

  private synchronized UworldHandle world() {
    UworldHandle current = this.world;
    if (current != null && current.isOpen()) {
      return current;
    }

    int radius = this.config.diagnostics().platformRadius();
    UworldSpec spec = UworldSpec.defaults(WORLD_NAME);
    this.world = this.runtime.createWorld(OWNER, spec, editor -> {
      VirtualBlock platform = editor.createBlock("minecraft:light_blue_concrete");
      int y = (int) Math.floor(spec.spawnY()) - 1;
      int centerX = (int) Math.floor(spec.spawnX());
      int centerZ = (int) Math.floor(spec.spawnZ());
      for (int x = centerX - radius; x <= centerX + radius; x++) {
        for (int z = centerZ - radius; z <= centerZ + radius; z++) {
          editor.setBlock(x, y, z, platform);
        }
      }
      editor.fillSkyLight(FULL_SKY_LIGHT);
    });
    this.logger.log(Level.INFO, "Generated a {0}x{0} Uworld diagnostics platform",
        radius * 2 + 1);
    return this.world;
  }

  private static void sendUsage(CommandSource source) {
    source.sendMessage(Component.text(
        "Usage: /sxworld <status|test|leave>",
        NamedTextColor.YELLOW));
  }

  private boolean executeChatCommand(Player player, String message) {
    String commandLine = message.strip();
    if (!commandLine.startsWith("/")) {
      return false;
    }
    String[] parts = commandLine.substring(1).split("\\s+");
    if (parts.length == 0
        || !("sxworld".equalsIgnoreCase(parts[0])
            || "uworld".equalsIgnoreCase(parts[0]))) {
      return false;
    }
    this.execute(player, Arrays.copyOfRange(parts, 1, parts.length));
    return true;
  }

  interface CommandAccess {
    void register(SimpleCommand command);

    void unregister();

    Optional<RegisteredServer> resolve(String name);

    Optional<RegisteredServer> currentServer(Player player);
  }

  private final class DiagnosticsHandler implements UworldFlowHandler {
    private final Player player;
    private final AtomicBoolean moved = new AtomicBoolean();

    private DiagnosticsHandler(Player player) {
      this.player = player;
    }

    @Override
    public void onReady(UworldFlowSession session) {
      this.player.sendMessage(Component.text(
          "Diagnostics Uworld ready. Chat and move callbacks are active.",
          NamedTextColor.GREEN));
    }

    @Override
    public void onChat(UworldFlowSession session, String message) {
      if (UworldDiagnostics.this.executeChatCommand(this.player, message)) {
        return;
      }
      this.player.sendMessage(Component.text(
          "Chat callback: " + message,
          NamedTextColor.AQUA));
    }

    @Override
    public void onMove(UworldFlowSession session, double x, double y, double z) {
      if (this.moved.compareAndSet(false, true)) {
        this.player.sendMessage(Component.text(
            "Movement callback received at " + x + ", " + y + ", " + z,
            NamedTextColor.AQUA));
      }
    }

    @Override
    public void onOutcome(UworldFlowSession session, UworldOutcome outcome) {
      UworldDiagnostics.this.state.finish(this.player, session);
      this.player.sendMessage(Component.text(
          "Diagnostics outcome: " + outcome.type(),
          NamedTextColor.YELLOW));
    }
  }

  private final class UworldCommand implements SimpleCommand {
    @Override
    public void execute(Invocation invocation) {
      UworldDiagnostics.this.execute(invocation.source(), invocation.arguments());
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
      return invocation.source().hasPermission(PERMISSION);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
      if (!this.hasPermission(invocation) || invocation.arguments().length > 1) {
        return List.of();
      }
      String prefix = invocation.arguments().length == 0
          ? ""
          : invocation.arguments()[0].toLowerCase(Locale.ROOT);
      return List.of("status", "test", "leave").stream()
          .filter(value -> value.startsWith(prefix))
          .toList();
    }
  }

  private static final class VelocityCommandAccess implements CommandAccess {
    private final StarxVelocityPlugin plugin;
    private CommandMeta meta;

    private VelocityCommandAccess(StarxVelocityPlugin plugin) {
      this.plugin = plugin;
    }

    @Override
    public void register(SimpleCommand command) {
      this.meta = this.plugin.proxy().getCommandManager()
          .metaBuilder("sxworld")
          .plugin(this.plugin)
          .build();
      this.plugin.proxy().getCommandManager().register(this.meta, (Command) command);
    }

    @Override
    public void unregister() {
      CommandMeta current = this.meta;
      if (current != null) {
        this.plugin.proxy().getCommandManager().unregister(current);
        if (this.meta == current) {
          this.meta = null;
        }
      }
    }

    @Override
    public Optional<RegisteredServer> resolve(String name) {
      return this.plugin.proxy().getServer(name);
    }

    @Override
    public Optional<RegisteredServer> currentServer(Player player) {
      return player.getCurrentServer().map(connection -> connection.getServer());
    }
  }
}
