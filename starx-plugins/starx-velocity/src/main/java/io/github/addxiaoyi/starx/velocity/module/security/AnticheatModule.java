/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  com.google.common.io.ByteArrayDataInput
 *  com.google.common.io.ByteStreams
 *  com.velocitypowered.api.command.Command
 *  com.velocitypowered.api.command.CommandManager
 *  com.velocitypowered.api.command.CommandMeta
 *  com.velocitypowered.api.command.CommandSource
 *  com.velocitypowered.api.command.SimpleCommand
 *  com.velocitypowered.api.command.SimpleCommand$Invocation
 *  com.velocitypowered.api.event.Subscribe
 *  com.velocitypowered.api.event.connection.LoginEvent
 *  com.velocitypowered.api.event.connection.PluginMessageEvent
 *  com.velocitypowered.api.event.player.ServerConnectedEvent
 *  com.velocitypowered.api.proxy.messages.ChannelIdentifier
 *  com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier
 *  net.kyori.adventure.text.Component
 *  net.kyori.adventure.text.format.NamedTextColor
 *  net.kyori.adventure.text.format.TextColor
 */
package io.github.addxiaoyi.starx.velocity.module.security;

import com.velocitypowered.api.command.Command;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import io.github.addxiaoyi.starx.api.event.EventBus;
import io.github.addxiaoyi.starx.api.event.StarxEvent;
import com.google.gson.Gson;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

public final class AnticheatModule
implements VelocityModule {
    private static final Gson GSON = new Gson();
    private static final int MAX_DETECTION_JSON_BYTES = 24 * 1024;
    private static final int MAX_TRACKED_PLAYERS = 4_096;
    private final StarxVelocityPlugin plugin;
    private final EventBus eventBus;
    private final Config config;
    private final ChannelIdentifier channel;
    private final TrackingRegistry detectionData = new TrackingRegistry(MAX_TRACKED_PLAYERS);
    private LoginListener loginListener;
    private ServerConnectedListener serverConnectedListener;
    private PluginMessageListener pluginMessageListener;

    public AnticheatModule(StarxVelocityPlugin plugin, EventBus eventBus, Config config) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.config = Objects.requireNonNull(config, "config");
        this.channel = MinecraftChannelIdentifier.create((String)"starx", (String)"anticheat");
    }

    @Override
    public String name() {
        return "starx.security.anticheat";
    }

    @Override
    public void onEnable() {
        LoginListener currentLoginListener = new LoginListener();
        ServerConnectedListener currentServerListener = new ServerConnectedListener();
        PluginMessageListener currentMessageListener = new PluginMessageListener();
        this.loginListener = currentLoginListener;
        this.serverConnectedListener = currentServerListener;
        this.pluginMessageListener = currentMessageListener;
        this.plugin.proxy().getChannelRegistrar().register(new ChannelIdentifier[]{this.channel});
        this.plugin.proxy().getEventManager().register((Object)this.plugin, (Object)currentLoginListener);
        this.plugin.proxy().getEventManager().register((Object)this.plugin, (Object)currentServerListener);
        this.plugin.proxy().getEventManager().register((Object)this.plugin, (Object)currentMessageListener);
        this.registerCommand();
    }

    @Override
    public void onDisable() {
        unregister(this.loginListener);
        unregister(this.serverConnectedListener);
        unregister(this.pluginMessageListener);
        this.loginListener = null;
        this.serverConnectedListener = null;
        this.pluginMessageListener = null;
        this.plugin.proxy().getChannelRegistrar().unregister(this.channel);
        this.plugin.proxy().getCommandManager().unregister("sxguard");
        this.detectionData.clear();
    }

    private void unregister(Object listener) {
        if (listener != null) {
            this.plugin.proxy().getEventManager().unregisterListener(this.plugin, listener);
        }
    }

    int getDetectionCount(UUID playerId) {
        PlayerDetectionData data = this.detectionData.get(playerId);
        return data != null ? data.totalViolations : 0;
    }

    boolean isPlayerTracked(UUID playerId) {
        return this.detectionData.contains(playerId);
    }

    void onLogin(LoginEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        String username = event.getPlayer().getUsername();
        this.detectionData.track(playerId, username, System.currentTimeMillis());
    }

    void onServerConnected(ServerConnectedEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        String username = event.getPlayer().getUsername();
        this.detectionData.track(playerId, username, System.currentTimeMillis());
    }

    void onPluginMessage(PluginMessageEvent event) {
        UUID playerId;
        if (!event.getIdentifier().equals((Object)this.channel)) {
            return;
        }
        Optional<Map<String, Object>> decoded = decodeDetection(event.getData());
        if (decoded.isEmpty()) {
            return;
        }
        Map<String, Object> payload = decoded.get();
        String playerUuidStr = (String)payload.get("player");
        String checkName = (String)payload.get("check");
        String category = payload.get("category") instanceof String value ? value : "unknown";
        double vl = payload.get("vl") instanceof Number ? ((Number)payload.get("vl")).doubleValue() : 0.0;
        String debug = payload.getOrDefault("debug", "").toString();
        if (playerUuidStr == null || checkName == null) {
            return;
        }
        if (!this.config.enabledChecks().contains(checkName)) {
            return;
        }
        try {
            playerId = UUID.fromString(playerUuidStr);
        }
        catch (IllegalArgumentException e) {
            return;
        }
        PlayerDetectionData data = this.detectionData.track(playerId, "Unknown", System.currentTimeMillis());
        data.addViolation(checkName, category, vl, debug);
        data.lastDetectionTime = System.currentTimeMillis();
        if (data.totalViolations >= this.config.alertThreshold()) {
            this.eventBus.publish(new StarxEvent("security:alert", Map.of("uuid", playerId, "username", data.username, "type", "anticheat", "check", checkName, "category", category, "totalViolations", data.totalViolations, "detail", debug)));
        }
        this.eventBus.publish(new StarxEvent("security:anticheat:detection", Map.of("uuid", playerId, "username", data.username, "check", checkName, "category", category, "vl", vl, "totalViolations", data.totalViolations, "debug", debug)));
    }

    static Optional<Map<String, Object>> decodeDetection(byte[] packet) {
        if (packet == null || packet.length == 0) {
            return Optional.empty();
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(packet))) {
            if (!"anticheat:detection".equals(in.readUTF())) {
                return Optional.empty();
            }
            int length = in.readInt();
            if (length < 0 || length > MAX_DETECTION_JSON_BYTES || in.available() != length) {
                return Optional.empty();
            }
            byte[] body = in.readNBytes(length);
            Map<?, ?> raw = GSON.fromJson(new String(body, StandardCharsets.UTF_8), Map.class);
            if (raw == null) {
                return Optional.empty();
            }
            Map<String, Object> payload = new HashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    return Optional.empty();
                }
                payload.put(key, entry.getValue());
            }
            Object player = payload.get("player");
            Object check = payload.get("check");
            if (!(player instanceof String playerId) || playerId.isBlank()
                || !(check instanceof String checkName) || checkName.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(Map.copyOf(payload));
        } catch (IOException | RuntimeException error) {
            return Optional.empty();
        }
    }

    private void registerCommand() {
        CommandManager cmdManager = this.plugin.proxy().getCommandManager();
        CommandMeta meta = cmdManager.metaBuilder("sxguard").plugin((Object)this.plugin).build();
        cmdManager.register(meta, (Command)new AnticheatCommand());
    }

    public static interface Config {
        public boolean enabled();

        public int alertThreshold();

        public long collectIntervalMs();

        public List<String> enabledChecks();

        public static Config defaultConfig() {
            return new Config(){

                @Override
                public boolean enabled() {
                    return true;
                }

                @Override
                public int alertThreshold() {
                    return 5;
                }

                @Override
                public long collectIntervalMs() {
                    return 60000L;
                }

                @Override
                public List<String> enabledChecks() {
                    return Arrays.asList("Speed", "Fly", "KillAura", "Reach", "NoSlow", "Timer", "Jesus", "AntiKnockback", "FastPlace", "AutoClicker");
                }
            };
        }
    }

    private final class LoginListener {
        private LoginListener() {
        }

        @Subscribe
        public void onLogin(LoginEvent event) {
            AnticheatModule.this.onLogin(event);
        }
    }

    private final class ServerConnectedListener {
        private ServerConnectedListener() {
        }

        @Subscribe
        public void onServerConnected(ServerConnectedEvent event) {
            AnticheatModule.this.onServerConnected(event);
        }
    }

    private final class PluginMessageListener {
        private PluginMessageListener() {
        }

        @Subscribe
        public void onPluginMessage(PluginMessageEvent event) {
            AnticheatModule.this.onPluginMessage(event);
        }
    }

    static final class PlayerDetectionData {
        final UUID playerId;
        final String username;
        int totalViolations;
        long firstDetectionTime;
        long lastDetectionTime;
        final Map<String, Integer> checkViolations = new HashMap<String, Integer>();
        final List<String> recentDetections = new ArrayList<String>();

        PlayerDetectionData(UUID playerId, String username) {
            this(playerId, username, System.currentTimeMillis());
        }

        PlayerDetectionData(UUID playerId, String username, long now) {
            this.playerId = playerId;
            this.username = username;
            this.firstDetectionTime = now;
            this.lastDetectionTime = now;
        }

        void addViolation(String checkName, String category, double vl, String debug) {
            int roundedVl = (int)Math.max(1L, Math.round(vl));
            this.totalViolations += roundedVl;
            this.checkViolations.merge(checkName, roundedVl, Integer::sum);
            if (this.recentDetections.size() >= 20) {
                this.recentDetections.remove(0);
            }
            this.recentDetections.add(checkName + "[" + category + "] VL=" + roundedVl);
        }
    }

    static final class TrackingRegistry {
        private final int capacity;
        private final Map<UUID, PlayerDetectionData> entries = new ConcurrentHashMap<>();

        TrackingRegistry(int capacity) {
            if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
            this.capacity = capacity;
        }

        synchronized PlayerDetectionData track(UUID playerId, String username, long now) {
            Objects.requireNonNull(playerId, "playerId");
            PlayerDetectionData data = this.entries.computeIfAbsent(
                playerId, ignored -> new PlayerDetectionData(playerId, username, now));
            data.lastDetectionTime = Math.max(data.lastDetectionTime, now);
            while (this.entries.size() > this.capacity) {
                this.entries.values().stream()
                    .min(java.util.Comparator.comparingLong(value -> value.lastDetectionTime))
                    .ifPresent(oldest -> this.entries.remove(oldest.playerId, oldest));
            }
            return data;
        }

        PlayerDetectionData get(UUID playerId) { return this.entries.get(playerId); }
        boolean contains(UUID playerId) { return this.entries.containsKey(playerId); }
        int size() { return this.entries.size(); }
        java.util.Collection<PlayerDetectionData> values() { return List.copyOf(this.entries.values()); }
        Set<Map.Entry<UUID, PlayerDetectionData>> entrySet() { return Map.copyOf(this.entries).entrySet(); }
        void remove(UUID playerId) { this.entries.remove(playerId); }
        void clear() { this.entries.clear(); }
    }

    private final class AnticheatCommand
    implements SimpleCommand {
        private AnticheatCommand() {
        }

        public void execute(SimpleCommand.Invocation invocation) {
            String subCommand;
            CommandSource source = invocation.source();
            String[] args = (String[])invocation.arguments();
            if (args.length == 0 || !"anticheat".equalsIgnoreCase(args[0])) {
                return;
            }
            if (args.length == 1) {
                this.showSummary(source);
                return;
            }
            switch (subCommand = args[1].toLowerCase()) {
                case "stats": {
                    this.showSummary(source);
                    break;
                }
                case "player": {
                    if (args.length >= 3) {
                        this.showPlayer(source, args[2]);
                        break;
                    }
                    source.sendMessage((Component)Component.text((String)"\u7528\u6cd5: /starx anticheat player <\u73a9\u5bb6\u540d>", (TextColor)NamedTextColor.RED));
                    break;
                }
                case "clear": {
                    if (args.length >= 3) {
                        this.clearPlayer(source, args[2]);
                        break;
                    }
                    AnticheatModule.this.detectionData.clear();
                    source.sendMessage((Component)Component.text((String)"\u5df2\u6e05\u9664\u6240\u6709\u53cd\u4f5c\u5f0a\u68c0\u6d4b\u6570\u636e", (TextColor)NamedTextColor.GREEN));
                    break;
                }
                default: {
                    source.sendMessage((Component)Component.text((String)("\u672a\u77e5\u5b50\u547d\u4ee4: " + subCommand), (TextColor)NamedTextColor.RED));
                }
            }
        }

        private void showSummary(CommandSource source) {
            int totalPlayers = AnticheatModule.this.detectionData.size();
            int totalViolations = AnticheatModule.this.detectionData.values().stream().mapToInt(d -> d.totalViolations).sum();
            int alertCount = (int)AnticheatModule.this.detectionData.values().stream().filter(d -> d.totalViolations >= AnticheatModule.this.config.alertThreshold()).count();
            source.sendMessage((Component)Component.text((String)"=== \u53cd\u4f5c\u5f0a\u68c0\u6d4b\u7edf\u8ba1 ===", (TextColor)NamedTextColor.GOLD));
            source.sendMessage((Component)Component.text((String)("\u8ffd\u8e2a\u73a9\u5bb6\u6570: " + totalPlayers), (TextColor)NamedTextColor.YELLOW));
            source.sendMessage((Component)Component.text((String)("\u603b\u8fdd\u89c4\u6b21\u6570: " + totalViolations), (TextColor)NamedTextColor.YELLOW));
            source.sendMessage((Component)Component.text((String)("\u89e6\u53d1\u544a\u8b66\u73a9\u5bb6\u6570: " + alertCount), (TextColor)NamedTextColor.YELLOW));
            source.sendMessage((Component)Component.text((String)("\u544a\u8b66\u9608\u503c: " + AnticheatModule.this.config.alertThreshold()), (TextColor)NamedTextColor.GRAY));
            source.sendMessage((Component)Component.text((String)("\u542f\u7528\u68c0\u6d4b: " + String.join((CharSequence)", ", AnticheatModule.this.config.enabledChecks())), (TextColor)NamedTextColor.GRAY));
        }

        private void showPlayer(CommandSource source, String playerName) {
            PlayerDetectionData found = null;
            for (PlayerDetectionData playerDetectionData : AnticheatModule.this.detectionData.values()) {
                if (!playerDetectionData.username.equalsIgnoreCase(playerName)) continue;
                found = playerDetectionData;
                break;
            }
            if (found == null) {
                source.sendMessage((Component)Component.text((String)("\u672a\u627e\u5230\u73a9\u5bb6 " + playerName + " \u7684\u68c0\u6d4b\u6570\u636e"), (TextColor)NamedTextColor.RED));
                return;
            }
            source.sendMessage((Component)Component.text((String)("=== " + found.username + " \u68c0\u6d4b\u8be6\u60c5 ==="), (TextColor)NamedTextColor.GOLD));
            source.sendMessage((Component)Component.text((String)("\u603b\u8fdd\u89c4\u6b21\u6570: " + found.totalViolations), (TextColor)NamedTextColor.YELLOW));
            source.sendMessage((Component)Component.text((String)"\u68c0\u6d4b\u7c7b\u578b\u660e\u7ec6:", (TextColor)NamedTextColor.YELLOW));
            for (Map.Entry entry : found.checkViolations.entrySet()) {
                source.sendMessage((Component)Component.text((String)("  " + (String)entry.getKey() + ": " + String.valueOf(entry.getValue())), (TextColor)NamedTextColor.GRAY));
            }
            source.sendMessage((Component)Component.text((String)"\u6700\u8fd1\u68c0\u6d4b:", (TextColor)NamedTextColor.YELLOW));
            for (String string : found.recentDetections) {
                source.sendMessage((Component)Component.text((String)("  " + string), (TextColor)NamedTextColor.GRAY));
            }
        }

        private void clearPlayer(CommandSource source, String playerName) {
            PlayerDetectionData found = null;
            for (Map.Entry<UUID, PlayerDetectionData> entry : AnticheatModule.this.detectionData.entrySet()) {
                if (!entry.getValue().username.equalsIgnoreCase(playerName)) continue;
                found = entry.getValue();
                AnticheatModule.this.detectionData.remove(entry.getKey());
                break;
            }
            if (found == null) {
                source.sendMessage((Component)Component.text((String)("\u672a\u627e\u5230\u73a9\u5bb6 " + playerName + " \u7684\u68c0\u6d4b\u6570\u636e"), (TextColor)NamedTextColor.RED));
            } else {
                source.sendMessage((Component)Component.text((String)("\u5df2\u6e05\u9664\u73a9\u5bb6 " + playerName + " \u7684\u68c0\u6d4b\u6570\u636e"), (TextColor)NamedTextColor.GREEN));
            }
        }
    }
}
