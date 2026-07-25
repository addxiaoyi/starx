/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  com.velocitypowered.api.event.Subscribe
 *  com.velocitypowered.api.event.player.PlayerChatEvent
 *  com.velocitypowered.api.proxy.Player
 *  net.kyori.adventure.text.BuildableComponent
 *  net.kyori.adventure.text.Component
 *  net.kyori.adventure.text.TextComponent$Builder
 *  net.kyori.adventure.text.format.NamedTextColor
 *  net.kyori.adventure.text.format.TextColor
 */
package io.github.addxiaoyi.starx.velocity.module.integrations;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.http.WebhookClient;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import java.util.Map;
import java.util.Objects;
import net.kyori.adventure.text.BuildableComponent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class QqIntegrationModule
implements VelocityModule {
    private static final Logger log = LoggerFactory.getLogger(QqIntegrationModule.class);
    private final StarxVelocityPlugin plugin;
    private final WebhookClient webhookClient;
    private final Config config;
    private ChatListener listener;

    public QqIntegrationModule(StarxVelocityPlugin plugin, WebhookClient webhookClient, Config config) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
        this.webhookClient = config.enabled() ? Objects.requireNonNull(webhookClient, "webhookClient") : webhookClient;
    }

    @Override
    public String name() {
        return "starx.integrations.qq";
    }

    @Override
    public void onEnable() {
        ChatListener currentListener = new ChatListener();
        this.listener = currentListener;
        this.plugin.proxy().getEventManager().register(this.plugin, currentListener);
    }

    @Override
    public void onDisable() {
        ChatListener currentListener = this.listener;
        this.listener = null;
        if (currentListener != null) {
            this.plugin.proxy().getEventManager().unregisterListener(this.plugin, currentListener);
        }
    }

    void onPlayerChat(PlayerChatEvent event) {
        if (!this.config.enabled()) {
            return;
        }
        Objects.requireNonNull(this.webhookClient, "webhookClient");
        Player player = event.getPlayer();
        String formatted = this.config.forwardFormat().replace("{player}", player.getUsername()).replace("{message}", event.getMessage());
        Map<String, Object> body = Map.of("player", player.getUsername(), "message", event.getMessage(), "group_id", this.config.qqGroupId(), "formatted", formatted);
        this.webhookClient.post(this.config.webhookUrl(), body)
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        log.warn("QQ chat forwarding failed for player {}", player.getUsername(), error);
                    }
                });
    }

    public void broadcastQqMessage(String qqSender, String message) {
        if (qqSender == null || qqSender.isBlank() || message == null || message.isBlank()) {
            return;
        }
        BuildableComponent component = ((TextComponent.Builder)((TextComponent.Builder)((TextComponent.Builder)((TextComponent.Builder)Component.text().append((Component)Component.text((String)"[QQ] ", (TextColor)NamedTextColor.AQUA))).append((Component)Component.text((String)qqSender, (TextColor)NamedTextColor.YELLOW))).append((Component)Component.text((String)": ", (TextColor)NamedTextColor.WHITE))).append((Component)Component.text((String)message, (TextColor)NamedTextColor.WHITE))).build();
        for (Player player : this.plugin.proxy().getAllPlayers()) {
            player.sendMessage((Component)component);
        }
    }

    public static interface Config {
        public boolean enabled();

        public String webhookUrl();

        public String qqGroupId();

        public String forwardFormat();

        public static Config defaultConfig() {
            return new Config(){

                @Override
                public boolean enabled() {
                    return false;
                }

                @Override
                public String webhookUrl() {
                    return "";
                }

                @Override
                public String qqGroupId() {
                    return "";
                }

                @Override
                public String forwardFormat() {
                    return "[QQ] {player}: {message}";
                }
            };
        }
    }

    private final class ChatListener {
        private ChatListener() {
        }

        @Subscribe
        public void onPlayerChat(PlayerChatEvent event) {
            QqIntegrationModule.this.onPlayerChat(event);
        }
    }
}
