/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  com.velocitypowered.api.event.Subscribe
 *  com.velocitypowered.api.event.player.PlayerChatEvent
 *  com.velocitypowered.api.proxy.Player
 *  com.velocitypowered.api.proxy.ServerConnection
 */
package io.github.addxiaoyi.starx.velocity.module.proxytools;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import io.github.addxiaoyi.starx.api.messaging.PluginMessage;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.messaging.VelocityMessageBridge;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ChatModule
implements VelocityModule {
    public static final String CHAT_COMMAND = "chat_broadcast";
    private final StarxVelocityPlugin plugin;
    private final VelocityMessageBridge bridge;
    private final Config config;
    private ChatListener listener;

    public ChatModule(StarxVelocityPlugin plugin, VelocityMessageBridge bridge, Config config) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public String name() {
        return "starx.chat";
    }

    @Override
    public void onEnable() {
        ChatListener currentListener = new ChatListener();
        this.listener = currentListener;
        this.plugin.proxy().getEventManager().register((Object)this.plugin, (Object)currentListener);
    }

    @Override
    public void onDisable() {
        ChatListener currentListener = this.listener;
        this.listener = null;
        if (currentListener != null) this.plugin.proxy().getEventManager().unregisterListener(this.plugin, currentListener);
    }

    void onPlayerChat(PlayerChatEvent event) {
        if (!this.config.enabled()) {
            return;
        }
        Player sender = event.getPlayer();
        PluginMessage message = new PluginMessage(CHAT_COMMAND, Map.of("sender", sender.getUsername(), "message", event.getMessage()));
        Optional senderConnection = sender.getCurrentServer();
        for (Player target : this.plugin.proxy().getAllPlayers()) {
            if (target.equals((Object)sender)) continue;
            Optional targetConnection = target.getCurrentServer();
            if (senderConnection.isPresent() && targetConnection.isPresent() && ((ServerConnection)senderConnection.get()).getServer().equals((Object)((ServerConnection)targetConnection.get()).getServer())) continue;
            this.bridge.sendMessage(target, message);
        }
    }

    public static interface Config {
        public boolean enabled();

        public static Config defaultConfig() {
            return () -> true;
        }
    }

    private final class ChatListener {
        private ChatListener() {
        }

        @Subscribe
        public void onPlayerChat(PlayerChatEvent event) {
            ChatModule.this.onPlayerChat(event);
        }
    }
}
