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
package io.github.addxiaoyi.starx.velocity.module.integrations.napcat;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import io.github.addxiaoyi.starx.common.auth.BindingVerificationService;
import io.github.addxiaoyi.starx.common.database.JdbcBindingRepository;
import io.github.addxiaoyi.starx.common.model.PlayerBinding;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.config.StarxConfig;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import io.github.addxiaoyi.starx.velocity.module.integrations.napcat.NapCatWebSocketClient;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class NapCatModule
implements VelocityModule,
NapCatWebSocketClient.MessageHandler {
    private static final Logger log = LoggerFactory.getLogger(NapCatModule.class);
    private final StarxVelocityPlugin plugin;
    private final JdbcBindingRepository bindingRepo;
    private final BindingVerificationService bindingVerification;
    private final StarxConfig.NapcatConfig config;
    private final Function<UUID, UUID> canonicalUuidResolver;
    private final Function<UUID, Set<UUID>> knownMinecraftUuidsResolver;
    private NapCatWebSocketClient wsClient;
    private ChatListener listener;

    public NapCatModule(StarxVelocityPlugin plugin, JdbcBindingRepository bindingRepo, BindingVerificationService bindingVerification, StarxConfig.NapcatConfig config) {
        this(plugin, bindingRepo, bindingVerification, config, uuid -> uuid, uuid -> Set.of(uuid));
    }

    public NapCatModule(
        StarxVelocityPlugin plugin,
        JdbcBindingRepository bindingRepo,
        BindingVerificationService bindingVerification,
        StarxConfig.NapcatConfig config,
        Function<UUID, UUID> canonicalUuidResolver,
        Function<UUID, Set<UUID>> knownMinecraftUuidsResolver) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.bindingRepo = Objects.requireNonNull(bindingRepo, "bindingRepo");
        this.bindingVerification = Objects.requireNonNull(bindingVerification, "bindingVerification");
        this.config = Objects.requireNonNull(config, "config");
        this.canonicalUuidResolver = Objects.requireNonNull(canonicalUuidResolver, "canonicalUuidResolver");
        this.knownMinecraftUuidsResolver = Objects.requireNonNull(
            knownMinecraftUuidsResolver, "knownMinecraftUuidsResolver");
    }

    @Override
    public String name() {
        return "starx.integrations.napcat";
    }

    @Override
    public void onEnable() {
        if (!this.config.enabled()) {
            return;
        }
        this.wsClient = new NapCatWebSocketClient(this.config.wsUrl(), this.config.httpUrl(), this);
        this.wsClient.start();
        ChatListener currentListener = new ChatListener();
        this.listener = currentListener;
        this.plugin.proxy().getEventManager().register((Object)this.plugin, (Object)currentListener);
        log.info("NapCat module enabled: WS={}, HTTP={}", (Object)this.config.wsUrl(), (Object)this.config.httpUrl());
    }

    @Override
    public void onDisable() {
        if (this.wsClient != null) {
            this.wsClient.stop();
            this.wsClient = null;
        }
        ChatListener currentListener = this.listener;
        this.listener = null;
        if (currentListener != null) this.plugin.proxy().getEventManager().unregisterListener(this.plugin, currentListener);
    }

    @Override
    public void onPrivateMessage(long userId, String message, String nickname) {
        String code = NapCatModule.extractCode(message);
        if (code == null) {
            return;
        }
        AtomicReference<String> failure = new AtomicReference<>();
        UUID playerUuid = this.bindingVerification.verifyAndExecute(code, (operationId, candidate) -> {
            String qqId = String.valueOf(userId);
            UUID canonicalCandidate = this.canonicalUuidResolver.apply(candidate);
            if (!this.migrateKnownBindings(canonicalCandidate)) {
                failure.set("Your Minecraft account has conflicting bindings. Please contact an administrator.");
                return false;
            }
            Optional<PlayerBinding> existing = this.bindingRepo.findByPlayer(canonicalCandidate);
            if (existing.isPresent() && existing.get().qqId() != null) {
                failure.set(existing.get().qqId().equals(qqId)
                    ? "Your Minecraft account is already bound to this QQ."
                    : "This Minecraft account is already bound to another QQ.");
                return false;
            }
            Optional<PlayerBinding> qqOwner = this.bindingRepo.findByQq(qqId);
            if (qqOwner.isPresent() && !qqOwner.get().playerUuid().equals(canonicalCandidate)) {
                failure.set("This QQ is already bound to another Minecraft account.");
                return false;
            }
            boolean saved = this.bindingRepo.save(new PlayerBinding(
                canonicalCandidate, qqId, null, System.currentTimeMillis()));
            if (!saved) failure.set("This QQ or Minecraft account was bound by another request. Please retry.");
            return saved;
        });
        if (playerUuid == null) {
            this.sendPrivateMessage(userId, Objects.requireNonNullElse(
                failure.get(), "Invalid or expired verification code."));
            return;
        }
        String qqId = String.valueOf(userId);
        this.sendPrivateMessage(userId, "QQ binding successful! You can now play on the server.");
        log.info("QQ binding: player={} qq={}", (Object)playerUuid, (Object)qqId);
    }

    private boolean migrateKnownBindings(UUID currentUuid) {
        for (UUID knownUuid : this.knownMinecraftUuidsResolver.apply(currentUuid)) {
            if (!knownUuid.equals(currentUuid)
                && !this.bindingRepo.migratePlayer(knownUuid, currentUuid)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onGroupMessage(long groupId, long userId, String message, String nickname) {
        if (this.config.qqGroupId() <= 0L || groupId != this.config.qqGroupId()) {
            return;
        }
        this.broadcastQqMessage(nickname, message);
    }

    void onPlayerChat(PlayerChatEvent event) {
        if (!this.config.enabled() || this.config.qqGroupId() <= 0L) {
            return;
        }
        if (this.wsClient == null) {
            return;
        }
        Player player = event.getPlayer();
        String formatted = this.config.forwardFormat().replace("{player}", player.getUsername()).replace("{message}", event.getMessage());
        this.wsClient.sendGroupMessage(this.config.qqGroupId(), formatted);
    }

    private void broadcastQqMessage(String qqSender, String message) {
        if (qqSender == null || qqSender.isBlank() || message == null || message.isBlank()) {
            return;
        }
        Component component = formatQqMessage(qqSender, message);
        for (Player player : this.plugin.proxy().getAllPlayers()) {
            player.sendMessage(component);
        }
    }

    static Component formatQqMessage(String sender, String message) {
        return Component.text()
                .append(Component.text("[QQ] ", NamedTextColor.AQUA))
                .append(Component.text(sender, NamedTextColor.YELLOW))
                .append(Component.text(": ", NamedTextColor.WHITE))
                .append(Component.text(message, NamedTextColor.WHITE))
                .build();
    }

    private void sendPrivateMessage(long userId, String text) {
        if (this.wsClient != null) {
            this.wsClient.sendPrivateMessage(userId, text);
        }
    }

    private static String extractCode(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String trimmed = message.trim();
        if (trimmed.length() == 6 && trimmed.chars().allMatch(Character::isDigit)) {
            return trimmed;
        }
        return null;
    }

    private final class ChatListener {
        private ChatListener() {
        }

        @Subscribe
        public void onPlayerChat(PlayerChatEvent event) {
            NapCatModule.this.onPlayerChat(event);
        }
    }
}
