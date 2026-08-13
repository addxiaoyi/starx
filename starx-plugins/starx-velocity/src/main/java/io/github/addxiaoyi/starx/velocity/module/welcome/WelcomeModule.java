/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  com.velocitypowered.api.event.Subscribe
 *  com.velocitypowered.api.event.connection.DisconnectEvent
 *  com.velocitypowered.api.event.player.ServerPostConnectEvent
 *  com.velocitypowered.api.proxy.Player
 *  net.kyori.adventure.text.Component
 *  net.kyori.adventure.text.TextComponent
 *  net.kyori.adventure.text.TextComponent$Builder
 *  net.kyori.adventure.text.event.ClickEvent
 *  net.kyori.adventure.text.event.HoverEvent
 *  net.kyori.adventure.text.event.HoverEventSource
 *  net.kyori.adventure.text.format.TextColor
 *  net.kyori.adventure.text.format.TextDecoration
 */
package io.github.addxiaoyi.starx.velocity.module.welcome;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import io.github.addxiaoyi.starx.common.auth.AuthService;
import io.github.addxiaoyi.starx.common.model.StarxUser;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import io.github.addxiaoyi.starx.velocity.network.LocalAddressInfo;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class WelcomeModule
implements VelocityModule {
    private static final Logger LOGGER = Logger.getLogger(WelcomeModule.class.getName());
    private final StarxVelocityPlugin plugin;
    private final JdbcUserRepository userRepository;
    private final AuthService authService;
    private final Map<UUID, Instant> loginTimestamps = new ConcurrentHashMap<UUID, Instant>();
    private final Map<UUID, UUID> accountIds = new ConcurrentHashMap<UUID, UUID>();
    private final Config config;
    private WelcomeListener listener;

    public WelcomeModule(StarxVelocityPlugin plugin, JdbcUserRepository userRepository) {
        this.plugin = plugin;
        this.userRepository = userRepository;
        this.authService = null;
        this.config = Config.defaultConfig();
    }

    public WelcomeModule(StarxVelocityPlugin plugin, JdbcUserRepository userRepository, Config config) {
        this.plugin = plugin;
        this.userRepository = userRepository;
        this.authService = null;
        this.config = config;
    }

    public WelcomeModule(
        StarxVelocityPlugin plugin,
        JdbcUserRepository userRepository,
        AuthService authService
    ) {
        this.plugin = plugin;
        this.userRepository = userRepository;
        this.authService = authService;
        this.config = Config.defaultConfig();
    }

    @Override
    public String name() {
        return "starx.welcome";
    }

    @Override
    public void onEnable() {
        WelcomeListener currentListener = new WelcomeListener();
        this.listener = currentListener;
        this.plugin.proxy().getEventManager().register(this.plugin, currentListener);
        LOGGER.info("Welcome module enabled");
    }

    @Override
    public void onDisable() {
        WelcomeListener currentListener = this.listener;
        this.listener = null;
        if (currentListener != null) {
            this.plugin.proxy().getEventManager().unregisterListener(this.plugin, currentListener);
        }
        this.loginTimestamps.clear();
        this.accountIds.clear();
    }

    private void onPlayerJoin(Player player) {
        UUID uuid = player.getUniqueId();
        String username = player.getUsername();
        this.loginTimestamps.put(uuid, Instant.now());
        String ip = this.getPlayerIp(player);
        LocalAddressInfo currentAddress = LocalAddressInfo.parse(ip);
        Optional<StarxUser> userOpt = this.authService == null
            ? this.userRepository.findFullByUuid(uuid)
            : this.authService.findConnectedUser(uuid);
        UUID accountUuid = userOpt.map(StarxUser::uuid).orElse(uuid);
        this.accountIds.put(uuid, accountUuid);
        this.saveLoginAddress(accountUuid, currentAddress);
        if (userOpt.isPresent()) {
            StarxUser user = userOpt.get();
            this.sendWelcomeMessage(player, user, currentAddress);
        } else {
            this.sendFirstTimeWelcome(player, username);
        }
    }

    private void onPlayerQuit(Player player) {
        UUID uuid = player.getUniqueId();
        UUID accountUuid = this.accountIds.remove(uuid);
        if (accountUuid == null) {
            accountUuid = uuid;
        }
        Instant loginTime = this.loginTimestamps.remove(uuid);
        if (loginTime != null) {
            this.recordSession(accountUuid, loginTime, Instant.now());
        }
    }

    void recordSession(UUID uuid, Instant loginTime, Instant logoutTime) {
        long duration = Math.max(0L, Duration.between(loginTime, logoutTime).getSeconds());
        if (duration > 0L) {
            this.userRepository.updateTotalPlaytime(uuid, duration);
        }
        this.userRepository.updateLastLogout(uuid, logoutTime);
    }

    private String getPlayerIp(Player player) {
        InetSocketAddress address = player.getRemoteAddress();
        if (address != null && address.getAddress() != null) {
            return address.getAddress().getHostAddress();
        }
        return "";
    }

    private void saveLoginAddress(UUID uuid, LocalAddressInfo address) {
        this.plugin.proxy().getScheduler().buildTask((Object)this.plugin, () -> {
            try {
                this.userRepository.updateLoginInfo(
                        uuid,
                        address.address(),
                        "",
                        address.locationLabel());
            }
            catch (Exception e) {
                LOGGER.log(Level.WARNING, "无法保存玩家 " + uuid + " 的登录地址", e);
            }
        }).schedule();
    }

    private void sendWelcomeMessage(
            Player player, StarxUser user, LocalAddressInfo currentAddress) {
        String username = user.username();
        String ip = user.lastLoginIp();
        String isp = user.lastLoginIsp();
        String location = user.lastLoginLocation();
        Long totalPlaytime = user.totalPlaytime();
        Instant lastLogout = user.lastLogoutAt();
        String email = user.email();
        String totpSecret = user.totpSecret();
        Boolean welcomeShown = user.welcomeMessageShown();
        boolean returning = Boolean.TRUE.equals(welcomeShown);
        List<WelcomeCard.Fact> facts = new ArrayList<>();
        if (!currentAddress.address().isBlank()) {
            facts.add(new WelcomeCard.Fact("本次 IP", currentAddress.address()));
            facts.add(new WelcomeCard.Fact("本次位置", currentAddress.locationLabel()));
        }
        if (ip != null && !ip.isBlank() && !ip.equals(currentAddress.address())) {
            facts.add(new WelcomeCard.Fact("上次 IP", ip));
        }
        if (location != null && !location.isBlank()
                && !location.equals(currentAddress.locationLabel())) {
            facts.add(new WelcomeCard.Fact("上次位置", location));
        }
        if (isp != null && !isp.isBlank()) {
            facts.add(new WelcomeCard.Fact("网络", isp));
        }
        if (ip != null && !ip.isBlank()) {
            facts.add(new WelcomeCard.Fact("登录 IP", ip));
        }
        if (totalPlaytime != null && totalPlaytime > 0L) {
            facts.add(new WelcomeCard.Fact("累计游玩", this.formatPlaytime(totalPlaytime)));
        }
        if (lastLogout != null) {
            String offlineDuration = this.formatDuration(Duration.between(lastLogout, Instant.now()));
            facts.add(new WelcomeCard.Fact("上次离线", offlineDuration + " 前"));
        }
        boolean needsEmail = email == null || email.isBlank();
        boolean needs2FA = totpSecret == null || totpSecret.isBlank();
        if (!returning) {
            this.userRepository.markWelcomeMessageShown(user.uuid());
        }
        player.sendMessage(WelcomeCard.account(
                username, returning, facts, needsEmail, needs2FA));
    }

    private void sendFirstTimeWelcome(Player player, String username) {
        player.sendMessage(WelcomeCard.firstLogin(username));
    }

    private String formatPlaytime(long seconds) {
        long hours = seconds / 3600L;
        long minutes = seconds % 3600L / 60L;
        if (hours > 0L) {
            return hours + " \u5c0f\u65f6 " + minutes + " \u5206\u949f";
        }
        return minutes + " \u5206\u949f";
    }

    private String formatDuration(Duration duration) {
        long days = duration.toDays();
        long hours = duration.toHours() % 24L;
        long minutes = duration.toMinutes() % 60L;
        if (days > 0L) {
            return days + " \u5929";
        }
        if (hours > 0L) {
            return hours + " \u5c0f\u65f6";
        }
        if (minutes > 0L) {
            return minutes + " \u5206\u949f";
        }
        return "\u521a\u521a";
    }

    public static interface Config {
        public boolean enabled();

        public static Config defaultConfig() {
            return () -> true;
        }
    }

    private class WelcomeListener {
        private WelcomeListener() {
        }

        @Subscribe
        public void onServerConnect(ServerPostConnectEvent event) {
            if (event.getPreviousServer() == null) {
                WelcomeModule.this.onPlayerJoin(event.getPlayer());
            }
        }

        @Subscribe
        public void onDisconnect(DisconnectEvent event) {
            WelcomeModule.this.onPlayerQuit(event.getPlayer());
        }
    }
}
