package io.github.addxiaoyi.starx.velocity.messaging;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import io.github.addxiaoyi.starx.api.event.EventBus;
import io.github.addxiaoyi.starx.api.messaging.PluginMessage;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class VelocityMessageBridge implements VelocityModule {
    private static final int MAX_COMMAND_BYTES = 96;
    private static final int MAX_PAYLOAD_BYTES = 24 * 1024;
    private final StarxVelocityPlugin plugin;
    private final ProxyServer proxy;
    private final EventBus eventBus;
    private final ChannelIdentifier channel;
    private MessageListener listener;

    public VelocityMessageBridge(StarxVelocityPlugin plugin, ProxyServer proxy, EventBus eventBus) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.channel = MinecraftChannelIdentifier.create("starx", "main");
    }

    @Override
    public String name() {
        return "starx.messaging";
    }

    @Override
    public void onEnable() {
        MessageListener currentListener = new MessageListener();
        this.listener = currentListener;
        this.proxy.getChannelRegistrar().register(this.channel);
        this.proxy.getEventManager().register(this.plugin, currentListener);
    }

    @Override
    public void onDisable() {
        MessageListener currentListener = this.listener;
        this.listener = null;
        if (currentListener != null) {
            this.proxy.getEventManager().unregisterListener(this.plugin, currentListener);
        }
        this.proxy.getChannelRegistrar().unregister(this.channel);
    }

    public void sendMessage(Player player, PluginMessage message) {
        Objects.requireNonNull(player, "player");
        player.sendPluginMessage(this.channel, encode(message));
    }

    static byte[] encode(PluginMessage message) {
        Objects.requireNonNull(message, "message");
        int commandBytes = message.command().getBytes(StandardCharsets.UTF_8).length;
        if (message.command().isBlank() || commandBytes > MAX_COMMAND_BYTES) {
            throw new IllegalArgumentException("StarX message command exceeds bridge limit");
        }
        byte[] payload = message.payload().toString().getBytes(StandardCharsets.UTF_8);
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException(
                "StarX message payload exceeds " + MAX_PAYLOAD_BYTES + " bytes");
        }
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(message.command());
        out.writeInt(payload.length);
        out.write(payload);
        return out.toByteArray();
    }

    public ChannelIdentifier channel() {
        return this.channel;
    }

    static Optional<PluginMessage> decode(byte[] packet) {
        if (packet == null || packet.length == 0) {
            return Optional.empty();
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(packet))) {
            String command = in.readUTF();
            int commandBytes = command.getBytes(StandardCharsets.UTF_8).length;
            if (command.isBlank() || commandBytes > MAX_COMMAND_BYTES) {
                return Optional.empty();
            }
            int length = in.readInt();
            if (length < 0 || length > MAX_PAYLOAD_BYTES || in.available() != length) {
                return Optional.empty();
            }
            String payload = new String(in.readNBytes(length), StandardCharsets.UTF_8);
            return Optional.of(new PluginMessage(command, Map.of("data", payload)));
        } catch (IOException | RuntimeException error) {
            return Optional.empty();
        }
    }

    private final class MessageListener {
        @Subscribe
        public void onPluginMessage(PluginMessageEvent event) {
            if (!event.getIdentifier().equals(VelocityMessageBridge.this.channel)) {
                return;
            }
            event.setResult(PluginMessageEvent.ForwardResult.handled());
            if (!(event.getSource() instanceof ServerConnection)) {
                return;
            }
            Optional<PluginMessage> decoded = decode(event.getData());
            if (decoded.isEmpty()) {
                return;
            }
            PluginMessage message = decoded.get();
            if ("plan_stats".equals(message.command())) {
                VelocityMessageBridge.this.eventBus.publish("plan:stats:report", message.payload());
            } else {
                VelocityMessageBridge.this.eventBus.publish("sync:player:state", Map.of("command", message.command(), "payload", message.payload()));
            }
        }
    }
}
