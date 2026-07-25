/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  com.velocitypowered.api.command.Command
 *  com.velocitypowered.api.command.SimpleCommand
 *  com.velocitypowered.api.command.SimpleCommand$Invocation
 *  com.velocitypowered.api.event.ResultedEvent$ComponentResult
 *  com.velocitypowered.api.event.Subscribe
 *  com.velocitypowered.api.event.connection.LoginEvent
 *  com.velocitypowered.api.proxy.Player
 *  com.velocitypowered.api.proxy.ProxyServer
 *  net.kyori.adventure.text.Component
 */
package io.github.addxiaoyi.starx.velocity.module.proxytools;

import com.velocitypowered.api.command.Command;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import io.github.addxiaoyi.starx.api.event.EventBus;
import io.github.addxiaoyi.starx.api.messaging.PluginMessage;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.messaging.VelocityMessageBridge;
import io.github.addxiaoyi.starx.velocity.bridge.VelocityBackendBridge;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.time.Duration;
import io.github.addxiaoyi.starx.common.platform.MaintenanceStateService;
import net.kyori.adventure.text.Component;

public final class MaintenanceModule
implements VelocityModule {
    public static final String MAINTENANCE_CHANGED = "proxy:maintenance:changed";
    private static final String DEFAULT_BYPASS_PERMISSION = "starx.maintenance.bypass";
    private final StarxVelocityPlugin plugin;
    private final EventBus eventBus;
    private final VelocityMessageBridge bridge;
    private final VelocityBackendBridge backendBridge;
    private final Config config;
    private final MaintenanceStateService state;
    private final MaintenanceRuntimeState runtime;
    private CommandMeta commandMeta;
    private LoginListener listener;
    private ScheduledTask reconciliationTask;

    public MaintenanceModule(StarxVelocityPlugin plugin, EventBus eventBus, VelocityMessageBridge bridge, VelocityBackendBridge backendBridge, Config config, MaintenanceStateService state) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.backendBridge = Objects.requireNonNull(backendBridge, "backendBridge");
        this.config = Objects.requireNonNull(config, "config");
        this.state = Objects.requireNonNull(state, "state");
        this.runtime = new MaintenanceRuntimeState(this::syncToBackends);
    }

    @Override
    public String name() {
        return "starx.maintenance";
    }

    @Override
    public void onEnable() {
        ProxyServer proxy = this.plugin.proxy();
        LoginListener currentListener = new LoginListener();
        this.listener = currentListener;
        proxy.getEventManager().register((Object)this.plugin, (Object)currentListener);
        this.commandMeta = proxy.getCommandManager().metaBuilder("sxmaintain").plugin(this.plugin).build();
        proxy.getCommandManager().register(this.commandMeta, (Command)new MaintenanceCommand());
        boolean restored = this.state.load();
        this.runtime.restore(restored);
        if (restored) {
            this.eventBus.publish(MAINTENANCE_CHANGED, Map.of("enabled", true));
        }
        this.reconciliationTask = proxy.getScheduler()
            .buildTask(this.plugin, this.runtime::rebroadcast)
            .repeat(Duration.ofMinutes(1))
            .schedule();
    }

    @Override
    public void onDisable() {
        ScheduledTask currentTask = this.reconciliationTask;
        this.reconciliationTask = null;
        if (currentTask != null) currentTask.cancel();
        CommandMeta current = this.commandMeta;
        this.commandMeta = null;
        if (current != null) this.plugin.proxy().getCommandManager().unregister(current);
        LoginListener currentListener = this.listener;
        this.listener = null;
        if (currentListener != null) this.plugin.proxy().getEventManager().unregisterListener(this.plugin, currentListener);
    }

    public boolean isEnabled() {
        return this.runtime.enabled();
    }

    public void setEnabled(boolean enabled) {
        if (this.runtime.enabled() != enabled) {
            this.state.save(enabled, System.currentTimeMillis());
            if (!this.runtime.change(enabled)) {
                return;
            }
            this.eventBus.publish(MAINTENANCE_CHANGED, Map.of("enabled", enabled));
        }
    }

    private void syncToBackends(boolean enabled) {
        PluginMessage message = new PluginMessage("config_sync", Map.of("maintenance", enabled));
        for (Player player : this.plugin.proxy().getAllPlayers()) {
            this.bridge.sendMessage(player, message);
        }
        this.backendBridge.broadcastMaintenance(enabled);
    }

    void onLogin(LoginEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        if (this.canBypass(player)) {
            return;
        }
        event.setResult(ResultedEvent.ComponentResult.denied((Component)this.config.kickMessage()));
    }

    private boolean canBypass(Player player) {
        return player.hasPermission(this.config.bypassPermission()) || this.config.whitelist().contains(player.getUsername());
    }

    public static interface Config {
        public Component kickMessage();

        public String bypassPermission();

        public Set<String> whitelist();

        public static Config defaultConfig() {
            return new Config(){

                @Override
                public Component kickMessage() {
                    return Component.text((String)"服务器正在维护中，请稍后再试。");
                }

                @Override
                public String bypassPermission() {
                    return MaintenanceModule.DEFAULT_BYPASS_PERMISSION;
                }

                @Override
                public Set<String> whitelist() {
                    return Set.of();
                }
            };
        }
    }

    private final class LoginListener {
        private LoginListener() {
        }

        @Subscribe
        public void onLogin(LoginEvent event) {
            MaintenanceModule.this.onLogin(event);
        }
    }

    private final class MaintenanceCommand
    implements SimpleCommand {
        private MaintenanceCommand() {
        }

        public void execute(SimpleCommand.Invocation invocation) {
            String[] args = (String[])invocation.arguments();
            if (args.length != 1) {
                invocation.source().sendMessage((Component)Component.text((String)"用法：/sxmaintain <on|off>"));
                return;
            }
            switch (args[0].toLowerCase()) {
                case "on": {
                    MaintenanceModule.this.setEnabled(true);
                    break;
                }
                case "off": {
                    MaintenanceModule.this.setEnabled(false);
                    break;
                }
                default: {
                    invocation.source().sendMessage((Component)Component.text((String)"用法：/sxmaintain <on|off>"));
                }
            }
        }

        public boolean hasPermission(SimpleCommand.Invocation invocation) {
            return invocation.source().hasPermission(MaintenanceModule.this.config.bypassPermission());
        }
    }
}
