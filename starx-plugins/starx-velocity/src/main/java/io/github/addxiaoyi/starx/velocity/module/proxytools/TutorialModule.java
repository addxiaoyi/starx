package io.github.addxiaoyi.starx.velocity.module.proxytools;

import com.velocitypowered.api.command.Command;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.time.Duration;
import net.kyori.adventure.text.Component;
import io.github.addxiaoyi.starx.common.database.JdbcTutorialProgressRepository;

/** Provides a small, resumable first-join tutorial for proxy commands and server navigation. */
public final class TutorialModule implements VelocityModule {
  private final StarxVelocityPlugin plugin;
  private final Config config;
  private final TutorialProgressService progress;
  private final TutorialJoinPolicy joinPolicy;
  private CommandMeta commandMeta;
  private TutorialListener listener;

  public TutorialModule(StarxVelocityPlugin plugin, Config config) {
    this(plugin, config, null);
  }

  public TutorialModule(
      StarxVelocityPlugin plugin,
      Config config,
      JdbcTutorialProgressRepository repository) {
    this(plugin, config, repository, uuid -> uuid, uuid -> Set.of(uuid));
  }

  public TutorialModule(
      StarxVelocityPlugin plugin,
      Config config,
      JdbcTutorialProgressRepository repository,
      Function<UUID, UUID> canonicalUuidResolver,
      Function<UUID, Set<UUID>> knownMinecraftUuidsResolver) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.config = Objects.requireNonNull(config, "config");
    this.progress = new TutorialProgressService(
        config.steps().size(), repository, canonicalUuidResolver, knownMinecraftUuidsResolver);
    this.joinPolicy = new TutorialJoinPolicy(this.progress);
  }

  @Override public String name() { return "starx.tutorial"; }

  @Override public void onEnable() {
    ProxyServer proxy = plugin.proxy();
    CommandMeta current = proxy.getCommandManager().metaBuilder("sxguide").build();
    this.commandMeta = current;
    proxy.getCommandManager().register(
        current,
        (Command) new TutorialCommand());
    TutorialListener currentListener = new TutorialListener();
    this.listener = currentListener;
    proxy.getEventManager().register(this.plugin, currentListener);
  }

  @Override public void onDisable() {
    CommandMeta current = this.commandMeta;
    this.commandMeta = null;
    if (current != null) this.plugin.proxy().getCommandManager().unregister(current);
    TutorialListener currentListener = this.listener;
    this.listener = null;
    if (currentListener != null) {
      this.plugin.proxy().getEventManager().unregisterListener(this.plugin, currentListener);
    }
    this.joinPolicy.clear();
  }

  public TutorialProgressService progress() { return progress; }

  private void prompt(Player player) {
    if (!this.config.enabled()) return;
    if (!this.joinPolicy.shouldPrompt(player)) return;
    this.plugin.proxy().getScheduler().buildTask(this.plugin, () -> {
      if (player.isActive()) new TutorialCommand().show(player);
    }).delay(Duration.ofSeconds(1)).schedule();
  }

  public record Config(boolean enabled, List<String> steps) {
    public Config {
      steps = List.copyOf(steps);
      if (steps.isEmpty()) throw new IllegalArgumentException("tutorial steps must not be empty");
    }
    public static Config defaultConfig() {
      return new Config(true, List.of(
          "使用 /server <名称> 切换子服。",
          "使用 /sxhub 返回大厅。",
          "使用 /starx status 查看网络状态。"));
    }
  }

  private final class TutorialCommand implements SimpleCommand {
    @Override public void execute(Invocation invocation) {
      if (!(invocation.source() instanceof Player player)) {
        invocation.source().sendMessage(Component.text("该命令只能由玩家执行。"));
        return;
      }
      String[] args = invocation.arguments();
      if (args.length == 0 || "status".equalsIgnoreCase(args[0])) {
        show(player);
        return;
      }
      switch (args[0].toLowerCase()) {
        case "start", "reset" -> { progress.reset(player.getUniqueId().toString()); show(player); }
        case "next" -> { progress.advance(player.getUniqueId().toString()); show(player); }
        case "skip" -> { progress.complete(player.getUniqueId().toString()); show(player); }
        default -> player.sendMessage(Component.text("用法：/sxguide [status|start|next|skip]"));
      }
    }

    void show(Player player) {
      String id = player.getUniqueId().toString();
      int step = progress.step(id);
      if (progress.completed(id)) {
        player.sendMessage(Component.text("StarMC 新手引导已完成。"));
        return;
      }
      player.sendMessage(Component.text("新手引导 " + (step + 1) + "/" + config.steps().size() + "：" + config.steps().get(step)));
      player.sendMessage(Component.text("完成后输入 /sxguide next，或使用 /sxguide skip 跳过。"));
    }

    @Override public boolean hasPermission(Invocation invocation) { return true; }
  }

  private final class TutorialListener {
    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
      TutorialModule.this.prompt(event.getPlayer());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
      TutorialModule.this.joinPolicy.release(event.getPlayer());
    }
  }
}
