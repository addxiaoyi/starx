package io.github.addxiaoyi.starx.velocity.module.security;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import io.github.addxiaoyi.starx.common.security.BushClient;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;

public final class BlossomGuardModule implements VelocityModule {
  private static final Component BLOCKED = Component.text("连接已被安全策略拒绝，请联系管理员");
  private final StarxVelocityPlugin plugin;
  private final BushClient bushClient;
  private final AtomicBoolean unavailableWarning = new AtomicBoolean();

  public BlossomGuardModule(StarxVelocityPlugin plugin) {
    this(plugin, new BushClient());
  }

  public BlossomGuardModule(StarxVelocityPlugin plugin, BushClient bushClient) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.bushClient = Objects.requireNonNull(bushClient, "bushClient");
  }

  @Override
  public String name() {
    return "starx.security.blossom";
  }

  @Override
  public void onEnable() {
    this.plugin.proxy().getEventManager().register(this.plugin, this);
  }

  @Override
  public void onDisable() {
    this.plugin.proxy().getEventManager().unregisterListener(this.plugin, this);
  }

  @Subscribe(order = PostOrder.EARLY)
  public EventTask onLogin(LoginEvent event) {
    Player player = event.getPlayer();
    InetSocketAddress address = player.getRemoteAddress();
    if (address == null || address.getAddress() == null) return null;
    String ip = address.getAddress().getHostAddress();

    return EventTask.async(() -> this.applyResult(event, this.bushClient.check(ip)));
  }

  private void applyResult(LoginEvent event, BushClient.Check check) {
    if (check.status() == BushClient.Status.BLOCKED) {
      event.setResult(ResultedEvent.ComponentResult.denied(BLOCKED));
      return;
    }
    if (check.status() == BushClient.Status.UNAVAILABLE
        && this.unavailableWarning.compareAndSet(false, true)) {
      this.plugin.logger().warning("Blossom risk service is unavailable; connections are allowed by fail-open policy");
    }
  }

  BushClient getBushClient() {
    return this.bushClient;
  }
}
