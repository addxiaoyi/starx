/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  com.velocitypowered.api.command.Command
 *  com.velocitypowered.api.command.SimpleCommand
 *  com.velocitypowered.api.command.SimpleCommand$Invocation
 *  com.velocitypowered.api.event.Subscribe
 *  com.velocitypowered.api.event.connection.DisconnectEvent
 *  com.velocitypowered.api.event.connection.PostLoginEvent
 *  com.velocitypowered.api.proxy.Player
 *  com.velocitypowered.api.proxy.ProxyServer
 *  net.kyori.adventure.text.Component
 *  net.kyori.adventure.text.format.NamedTextColor
 *  net.kyori.adventure.text.format.TextColor
 *  net.kyori.adventure.text.format.TextDecoration
 */
package io.github.addxiaoyi.starx.velocity.module.proxytools;

import com.velocitypowered.api.command.Command;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

public final class OnlineSyncModule
implements VelocityModule {
    private final StarxVelocityPlugin plugin;
    private final Config config;
    private final Map<UUID, String> onlinePlayers = new ConcurrentHashMap<UUID, String>();
    private CommandMeta commandMeta;
    private OnlineListener listener;

    public OnlineSyncModule(StarxVelocityPlugin plugin, Config config) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public String name() {
        return "starx.online";
    }

    @Override
    public void onEnable() {
        if (!this.config.enabled()) {
            return;
        }
        ProxyServer proxy = this.plugin.proxy();
        OnlineListener currentListener = new OnlineListener();
        this.listener = currentListener;
        proxy.getEventManager().register((Object)this.plugin, (Object)currentListener);
        this.commandMeta = proxy.getCommandManager().metaBuilder("sxonline").plugin(this.plugin).build();
        proxy.getCommandManager().register(this.commandMeta, (Command)new ListCommand());
    }

    @Override
    public void onDisable() {
        CommandMeta current = this.commandMeta;
        this.commandMeta = null;
        if (current != null) this.plugin.proxy().getCommandManager().unregister(current);
        OnlineListener currentListener = this.listener;
        this.listener = null;
        if (currentListener != null) this.plugin.proxy().getEventManager().unregisterListener(this.plugin, currentListener);
        this.onlinePlayers.clear();
    }

    public int getOnlineCount() {
        return this.onlinePlayers.size();
    }

    void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        this.onlinePlayers.put(player.getUniqueId(), player.getUsername());
    }

    void onDisconnect(DisconnectEvent event) {
        this.onlinePlayers.remove(event.getPlayer().getUniqueId());
    }

    public static interface Config {
        public boolean enabled();

        public static Config defaultConfig() {
            return () -> true;
        }
    }

    private final class OnlineListener {
        private OnlineListener() {
        }

        @Subscribe
        public void onPostLogin(PostLoginEvent event) {
            OnlineSyncModule.this.onPostLogin(event);
        }

        @Subscribe
        public void onDisconnect(DisconnectEvent event) {
            OnlineSyncModule.this.onDisconnect(event);
        }
    }

    private final class ListCommand
    implements SimpleCommand {
        private ListCommand() {
        }

        public void execute(SimpleCommand.Invocation invocation) {
            ProxyServer proxy = OnlineSyncModule.this.plugin.proxy();
            int total = proxy.getPlayerCount();
            invocation.source().sendMessage(Component.text((String)("==== Online Players (" + total + ") ===="), (TextColor)NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true));
            for (Player player : proxy.getAllPlayers()) {
                String serverName = player.getCurrentServer().map(conn -> conn.getServer().getServerInfo().getName()).orElse("connecting");
                invocation.source().sendMessage(Component.text((String)("  " + player.getUsername() + " "), (TextColor)NamedTextColor.WHITE).append((Component)Component.text((String)("[" + serverName + "]"), (TextColor)NamedTextColor.GRAY)));
            }
        }
    }
}
