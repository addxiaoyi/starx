package io.github.addxiaoyi.starx.velocity.module.proxytools;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.server.ServerPing;
import com.velocitypowered.api.util.Favicon;
import io.github.addxiaoyi.starx.api.event.EventBus;
import io.github.addxiaoyi.starx.api.event.StarxEvent;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.config.StarxConfig;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

public final class MotdModule implements VelocityModule {
    private static final long MAX_FAVICON_BYTES = 8 * 1_024 * 1_024;
    private final StarxVelocityPlugin plugin;
    private final EventBus eventBus;
    private final Config config;
    private volatile boolean maintenanceActive;
    private volatile Favicon favicon;
    private PingListener listener;
    private final Consumer<StarxEvent> maintenanceSubscriber = this::onMaintenanceChanged;

    public MotdModule(StarxVelocityPlugin plugin, EventBus eventBus, Config config) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public String name() {
        return "starx.motd";
    }

    @Override
    public void onEnable() {
        this.favicon = loadFavicon(this.plugin.dataDirectory(), this.config.faviconPath(),
            this.plugin.logger()::warning);
        PingListener currentListener = new PingListener();
        this.listener = currentListener;
        this.plugin.proxy().getEventManager().register(this.plugin, currentListener);
        this.eventBus.subscribe("proxy:maintenance:changed", this.maintenanceSubscriber);
    }

    @Override
    public void onDisable() {
        this.eventBus.unsubscribe("proxy:maintenance:changed", this.maintenanceSubscriber);
        PingListener currentListener = this.listener;
        this.listener = null;
        if (currentListener != null) {
            this.plugin.proxy().getEventManager().unregisterListener(this.plugin, currentListener);
        }
    }

    public boolean isMaintenanceActive() {
        return this.maintenanceActive;
    }

    void onMaintenanceChanged(StarxEvent event) {
        Boolean enabled = (Boolean) event.get("enabled");
        this.maintenanceActive = enabled != null && enabled;
    }

    void onProxyPing(ProxyPingEvent event) {
        Component motd = this.maintenanceActive ? this.config.maintenanceMotd() : this.config.normalMotd();
        event.setPing(applyPing(event.getPing(), motd, this.plugin.proxy().getPlayerCount(),
            this.config.maximumPlayers(), this.favicon));
    }

    static ServerPing applyPing(ServerPing incoming, Component motd, int online, int configuredMaximum,
        Favicon favicon) {
        if (online < 0) {
            throw new IllegalArgumentException("Online player count must not be negative");
        }
        PingPolicy policy = new PingPolicy(configuredMaximum);
        ServerPing.Builder builder = incoming.asBuilder()
            .description(Objects.requireNonNull(motd, "motd"))
            .onlinePlayers(online)
            .maximumPlayers(policy.maximumPlayers(online));
        if (favicon != null) {
            builder.favicon(favicon);
        }
        return builder.build();
    }

    static Favicon loadFavicon(Path dataDirectory, String configuredPath, Consumer<String> warningSink) {
        if (configuredPath == null || configuredPath.isBlank()) {
            return null;
        }
        try {
            Path data = dataDirectory.toRealPath();
            Path candidate = data.resolve(configuredPath).normalize();
            if (!candidate.startsWith(data)
                || Files.isSymbolicLink(candidate)
                || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                || Files.size(candidate) > MAX_FAVICON_BYTES) {
                warningSink.accept("StarX favicon ignored: configured file is unsafe or invalid");
                return null;
            }
            Path realCandidate = candidate.toRealPath();
            if (!realCandidate.startsWith(data)) {
                warningSink.accept("StarX favicon ignored: configured file escapes the data directory");
                return null;
            }
            BufferedImage image = ImageIO.read(realCandidate.toFile());
            if (image == null) {
                warningSink.accept("StarX favicon ignored: configured file is not a readable image");
                return null;
            }
            return Favicon.create(image);
        } catch (IOException | RuntimeException error) {
            warningSink.accept("StarX favicon ignored: " + error.getMessage());
            return null;
        }
    }

    public interface Config {
        Component normalMotd();
        Component maintenanceMotd();

        default int maximumPlayers() {
            return 100;
        }

        default String faviconPath() {
            return "";
        }

        static Config from(StarxConfig.ModuleConfig module) {
            Objects.requireNonNull(module, "module");
            int maximum = module.intOption("maximum-players", 100);
            if (maximum <= 0) {
                throw new IllegalArgumentException("modules.starx.motd.maximum-players must be positive");
            }
            MiniMessage miniMessage = MiniMessage.miniMessage();
            return new Config() {
                @Override
                public Component normalMotd() {
                    return miniMessage.deserialize(module.stringOption("normal", "欢迎来到 StarX！"));
                }

                @Override
                public Component maintenanceMotd() {
                    return miniMessage.deserialize(module.stringOption("maintenance", "StarX 正在维护中。"));
                }

                @Override
                public int maximumPlayers() {
                    return maximum;
                }

                @Override
                public String faviconPath() {
                    return module.stringOption("icon-path", "");
                }
            };
        }

        static Config defaultConfig() {
            return new Config() {
                @Override
                public Component normalMotd() {
                    return Component.text("欢迎来到 StarX！");
                }

                @Override
                public Component maintenanceMotd() {
                    return Component.text("StarX 正在维护中。");
                }
            };
        }
    }

    static final class PingPolicy {
        private final int configuredMaximum;

        PingPolicy(int configuredMaximum) {
            if (configuredMaximum <= 0) {
                throw new IllegalArgumentException("Configured maximum players must be positive");
            }
            this.configuredMaximum = configuredMaximum;
        }

        int maximumPlayers(int onlinePlayers) {
            if (onlinePlayers < 0) {
                throw new IllegalArgumentException("Online player count must not be negative");
            }
            return Math.max(this.configuredMaximum, onlinePlayers);
        }
    }

    private final class PingListener {
        @Subscribe
        public void onProxyPing(ProxyPingEvent event) {
            MotdModule.this.onProxyPing(event);
        }
    }
}
