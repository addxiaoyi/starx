/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.velocity.config;

import io.github.addxiaoyi.starx.common.auth.uniauth.UniAuthConfig;
import io.github.addxiaoyi.starx.common.config.DatabaseConfig;
import io.github.addxiaoyi.starx.website.WebsitePlatform;
import io.github.addxiaoyi.starx.website.WebsiteSyncConfig;
import java.util.Map;
import java.util.Objects;

public final class StarxConfig {
    private final String apiKey;
    private final HttpConfig http;
    private final WebhookConfig webhook;
    private final DatabaseConfig database;
    private final UniAuthConfig uniauth;
    private final NapcatConfig napcat;
    private final TotpConfig totp;
    private final UworldConfig uworld;
    private final AuthConfig auth;
    private final PlayerListConfig playerList;
    private final WebsiteSyncConfig websiteSync;
    private final NetworkAutomationConfig networkAutomation;
    private final Map<String, ModuleConfig> modules;

    public StarxConfig(String apiKey, HttpConfig http, WebhookConfig webhook, DatabaseConfig database, UniAuthConfig uniauth, NapcatConfig napcat, TotpConfig totp, UworldConfig uworld, AuthConfig auth, Map<String, ModuleConfig> modules) {
        this(apiKey, http, webhook, database, uniauth, napcat, totp, uworld, auth,
                PlayerListConfig.defaults(), modules);
    }

    public StarxConfig(String apiKey, HttpConfig http, WebhookConfig webhook, DatabaseConfig database, UniAuthConfig uniauth, NapcatConfig napcat, TotpConfig totp, UworldConfig uworld, AuthConfig auth, PlayerListConfig playerList, Map<String, ModuleConfig> modules) {
        this(apiKey, http, webhook, database, uniauth, napcat, totp, uworld, auth,
                playerList, WebsiteSyncConfig.disabled("proxy-1", WebsitePlatform.VELOCITY), modules);
    }

    public StarxConfig(String apiKey, HttpConfig http, WebhookConfig webhook, DatabaseConfig database, UniAuthConfig uniauth, NapcatConfig napcat, TotpConfig totp, UworldConfig uworld, AuthConfig auth, PlayerListConfig playerList, WebsiteSyncConfig websiteSync, Map<String, ModuleConfig> modules) {
        this(apiKey, http, webhook, database, uniauth, napcat, totp, uworld, auth, playerList,
                websiteSync, NetworkAutomationConfig.defaults(), modules);
    }

    public StarxConfig(String apiKey, HttpConfig http, WebhookConfig webhook, DatabaseConfig database, UniAuthConfig uniauth, NapcatConfig napcat, TotpConfig totp, UworldConfig uworld, AuthConfig auth, PlayerListConfig playerList, WebsiteSyncConfig websiteSync, NetworkAutomationConfig networkAutomation, Map<String, ModuleConfig> modules) {
        this.apiKey = apiKey;
        this.http = Objects.requireNonNull(http, "http");
        this.webhook = webhook;
        this.database = database == null ? DatabaseConfig.defaults() : database;
        this.uniauth = uniauth == null ? UniAuthConfig.defaults() : uniauth;
        this.napcat = napcat == null ? NapcatConfig.defaults() : napcat;
        this.totp = totp == null ? TotpConfig.defaults() : totp;
        this.uworld = uworld == null ? UworldConfig.defaults() : uworld;
        this.auth = auth == null ? AuthConfig.defaults() : auth;
        this.playerList = playerList == null ? PlayerListConfig.defaults() : playerList;
        this.websiteSync = websiteSync == null
                ? WebsiteSyncConfig.disabled("proxy-1", WebsitePlatform.VELOCITY)
                : websiteSync;
        this.networkAutomation = networkAutomation == null
                ? NetworkAutomationConfig.defaults()
                : networkAutomation;
        this.modules = modules == null ? Map.of() : Map.copyOf(modules);
    }

    public String apiKey() {
        return this.apiKey;
    }

    public HttpConfig http() {
        return this.http;
    }

    public WebhookConfig webhook() {
        return this.webhook;
    }

    public DatabaseConfig database() {
        return this.database;
    }

    public UniAuthConfig uniauth() {
        return this.uniauth;
    }

    public NapcatConfig napcat() {
        return this.napcat;
    }

    public TotpConfig totp() {
        return this.totp;
    }

    public UworldConfig uworld() {
        return this.uworld;
    }

    public AuthConfig auth() {
        return this.auth;
    }

    public PlayerListConfig playerList() {
        return this.playerList;
    }

    public WebsiteSyncConfig websiteSync() {
        return this.websiteSync;
    }

    public NetworkAutomationConfig networkAutomation() {
        return this.networkAutomation;
    }

    public Map<String, ModuleConfig> modules() {
        return this.modules;
    }

    public boolean isModuleEnabled(String name) {
        if ("starx.uworld.diagnostics".equals(name)) {
            return this.isModuleEnabled("starx.uworld");
        }
        ModuleConfig module = this.modules.get(name);
        if (module == null && "starx.backend-bridge".equals(name)) {
            return true;
        }
        if (module == null && "starx.uworld".equals(name)) {
            module = this.modules.get("starx.limbo");
        }
        if (module == null) {
            module = switch (name) {
                case "starx.auth.offline-identity" -> this.modules.get("starx.auth.floodgate");
                case "starx.player-list" -> this.modules.get("starx.auth.tab");
                case "starx.variables" -> this.modules.get("starx.placeholder");
                default -> null;
            };
        }
        return module != null && module.enabled();
    }


public static final class HttpConfig {
    private static final int MAX_FALLBACK_RANGE_WIDTH = 4096;
    private final String bind;
    private final int port;
    private final String frpPublicUrl;
    private final PortConflictPolicy portConflictPolicy;
    private final int fallbackRangeStart;
    private final int fallbackRangeEnd;

    public HttpConfig(String bind, int port) {
        this(bind, port, "");
    }

    public HttpConfig(String bind, int port, String frpPublicUrl) {
        this(
                bind,
                normalizePort(port),
                frpPublicUrl,
                PortConflictPolicy.PERSIST,
                normalizePort(port),
                Math.min(65_535, normalizePort(port) + 100));
    }

    public HttpConfig(
            String bind,
            int port,
            String frpPublicUrl,
            String portConflictPolicy,
            int fallbackRangeStart,
            int fallbackRangeEnd) {
        this(
                bind,
                port,
                frpPublicUrl,
                PortConflictPolicy.parse(portConflictPolicy),
                fallbackRangeStart,
                fallbackRangeEnd);
    }

    public HttpConfig(
            String bind,
            int port,
            String frpPublicUrl,
            PortConflictPolicy portConflictPolicy,
            int fallbackRangeStart,
            int fallbackRangeEnd) {
        this.bind = bind == null || bind.isBlank() ? "127.0.0.1" : bind.trim();
        this.port = normalizePort(port);
        this.frpPublicUrl = normalizePublicUrl(frpPublicUrl);
        this.portConflictPolicy = Objects.requireNonNull(
                portConflictPolicy, "portConflictPolicy");
        requirePort(fallbackRangeStart, "http.fallback-range-start");
        requirePort(fallbackRangeEnd, "http.fallback-range-end");
        if (fallbackRangeEnd < fallbackRangeStart) {
            throw new IllegalArgumentException(
                    "http.fallback-range-end must be greater than or equal to "
                            + "http.fallback-range-start");
        }
        if (fallbackRangeEnd - fallbackRangeStart + 1 > MAX_FALLBACK_RANGE_WIDTH) {
            throw new IllegalArgumentException(
                    "HTTP fallback range may contain at most "
                            + MAX_FALLBACK_RANGE_WIDTH + " ports");
        }
        this.fallbackRangeStart = fallbackRangeStart;
        this.fallbackRangeEnd = fallbackRangeEnd;
    }

    public String bind() {
        return this.bind;
    }

    public int port() {
        return this.port;
    }

    public String frpPublicUrl() {
        return this.frpPublicUrl;
    }

    public PortConflictPolicy portConflictPolicy() {
        return this.portConflictPolicy;
    }

    public int fallbackRangeStart() {
        return this.fallbackRangeStart;
    }

    public int fallbackRangeEnd() {
        return this.fallbackRangeEnd;
    }

    private static int normalizePort(int port) {
        return port <= 0 || port > 65_535 ? 8788 : port;
    }

    private static void requirePort(int port, String name) {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException(name + " must be between 1 and 65535");
        }
    }

    private static String normalizePublicUrl(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim();
        java.net.URI uri;
        try {
            uri = java.net.URI.create(normalized);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("http.frp-public-url must be a valid URL", error);
        }
        String scheme = uri.getScheme();
        boolean supportedScheme = "http".equalsIgnoreCase(scheme)
                || "https".equalsIgnoreCase(scheme);
        if (!supportedScheme || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "http.frp-public-url must be an HTTP(S) base URL without credentials, query, or fragment");
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    public enum PortConflictPolicy {
        STRICT,
        FALLBACK,
        PERSIST,
        EPHEMERAL;

        static PortConflictPolicy parse(String value) {
            String normalized = value == null || value.isBlank()
                    ? "persist"
                    : value.trim().replace('-', '_');
            try {
                return PortConflictPolicy.valueOf(
                        normalized.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException(
                        "http.port-conflict-policy must be strict, fallback, persist, or ephemeral",
                        error);
            }
        }

        public boolean usesLease() {
            return this == PERSIST || this == EPHEMERAL;
        }

        public boolean allowsFallbackRange() {
            return this != STRICT;
        }

        public boolean allowsEphemeralFallback() {
            return this == EPHEMERAL;
        }
    }
}

    public static final class WebhookConfig {
        private final String url;
        private final String secret;

        public WebhookConfig(String url, String secret) {
            this.url = url;
            this.secret = secret;
        }

        public String url() {
            return this.url;
        }

        public String secret() {
            return this.secret;
        }

        public boolean isConfigured() {
            return this.url != null && !this.url.isBlank();
        }
    }

    public static final class NapcatConfig {
        private final boolean enabled;
        private final String wsUrl;
        private final String httpUrl;
        private final long qqGroupId;
        private final String forwardFormat;

        public NapcatConfig(boolean enabled, String wsUrl, String httpUrl, long qqGroupId, String forwardFormat) {
            this.enabled = enabled;
            this.wsUrl = wsUrl == null || wsUrl.isBlank() ? "ws://127.0.0.1:6700" : wsUrl;
            this.httpUrl = httpUrl;
            this.qqGroupId = qqGroupId;
            this.forwardFormat = forwardFormat == null || forwardFormat.isBlank() ? "[MC] {player}: {message}" : forwardFormat;
        }

        public boolean enabled() {
            return this.enabled;
        }

        public String wsUrl() {
            return this.wsUrl;
        }

        public String httpUrl() {
            return this.httpUrl;
        }

        public long qqGroupId() {
            return this.qqGroupId;
        }

        public String forwardFormat() {
            return this.forwardFormat;
        }

        public static NapcatConfig defaults() {
            return new NapcatConfig(false, "ws://127.0.0.1:6700", "", 0L, "[MC] {player}: {message}");
        }
    }

    public static final class TotpConfig {
        private final boolean enabled;

        public TotpConfig(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean enabled() {
            return this.enabled;
        }

        public static TotpConfig defaults() {
            return new TotpConfig(true);
        }
    }

    public static final class ModuleConfig {
        private final boolean enabled;
        private final Map<String, Object> options;

        public ModuleConfig(boolean enabled) {
            this(enabled, Map.of());
        }

        public ModuleConfig(boolean enabled, Map<String, Object> options) {
            this.enabled = enabled;
            this.options = Map.copyOf(Objects.requireNonNull(options, "options"));
        }

        public boolean enabled() {
            return this.enabled;
        }

        public boolean booleanOption(String key, boolean fallback) {
            Object value = this.options.get(key);
            if (value instanceof Boolean flag) {
                return flag;
            }
            if (value instanceof Number number) {
                return number.intValue() != 0;
            }
            if (value instanceof String text) {
                if ("true".equalsIgnoreCase(text.trim())) {
                    return true;
                }
                if ("false".equalsIgnoreCase(text.trim())) {
                    return false;
                }
            }
            return fallback;
        }

        public int intOption(String key, int fallback) {
            Object value = this.options.get(key);
            if (value instanceof Number number) {
                return number.intValue();
            }
            if (value instanceof String text) {
                try {
                    return Integer.parseInt(text.trim());
                } catch (NumberFormatException ignored) {
                    return fallback;
                }
            }
            return fallback;
        }

        public String stringOption(String key, String fallback) {
            Object value = this.options.get(key);
            if (value == null) {
                return fallback;
            }
            String text = String.valueOf(value).trim();
            return text.isEmpty() ? fallback : text;
        }

        public java.util.Set<String> stringSet(String key, java.util.Set<String> fallback) {
            Object value = this.options.get(key);
            java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
            if (value instanceof java.util.Collection<?> collection) {
                for (Object item : collection) {
                    if (item != null && !String.valueOf(item).isBlank()) {
                        result.add(String.valueOf(item).trim());
                    }
                }
            } else if (value instanceof String text && !text.isBlank()) {
                for (String item : text.split(",")) {
                    if (!item.isBlank()) {
                        result.add(item.trim());
                    }
                }
            }
            return result.isEmpty() ? java.util.Set.copyOf(fallback) : java.util.Set.copyOf(result);
        }

        public Map<String, Object> options() {
            return this.options;
        }
    }

    public static final class AuthConfig {
        private final boolean allowOfflineDefault;
        private final AuthUxConfig ux;
        private final OfflineIdentityConfig offlineIdentity;
        private final int passwordBypassMinutes;
        private final String bindingWebsiteUrl;
        private final boolean premiumBypass;
        private final boolean floodgateBypass;
        private final boolean skinSiteBypass;

        public AuthConfig(boolean allowOfflineDefault) {
            this(allowOfflineDefault, AuthUxConfig.defaults(), OfflineIdentityConfig.defaults(), 30);
        }

        public AuthConfig(boolean allowOfflineDefault, AuthUxConfig ux) {
            this(allowOfflineDefault, ux, OfflineIdentityConfig.defaults(), 30);
        }

        public AuthConfig(boolean allowOfflineDefault, AuthUxConfig ux, OfflineIdentityConfig offlineIdentity) {
            this(allowOfflineDefault, ux, offlineIdentity, 30);
        }

        public AuthConfig(boolean allowOfflineDefault, AuthUxConfig ux, OfflineIdentityConfig offlineIdentity,
                          int passwordBypassMinutes) {
            this(allowOfflineDefault, ux, offlineIdentity, passwordBypassMinutes, "https://star-web.top");
        }

        public AuthConfig(boolean allowOfflineDefault, AuthUxConfig ux, OfflineIdentityConfig offlineIdentity,
                          int passwordBypassMinutes, String bindingWebsiteUrl) {
            this(allowOfflineDefault, ux, offlineIdentity, passwordBypassMinutes, bindingWebsiteUrl,
                    true, true, true);
        }

        public AuthConfig(boolean allowOfflineDefault, AuthUxConfig ux, OfflineIdentityConfig offlineIdentity,
                          int passwordBypassMinutes, String bindingWebsiteUrl,
                          boolean premiumBypass, boolean floodgateBypass, boolean skinSiteBypass) {
            this.allowOfflineDefault = allowOfflineDefault;
            this.ux = ux == null ? AuthUxConfig.defaults() : ux;
            this.offlineIdentity = offlineIdentity == null
                    ? OfflineIdentityConfig.defaults()
                    : offlineIdentity;
            this.passwordBypassMinutes = Math.max(0, passwordBypassMinutes);
            this.bindingWebsiteUrl = normalizeUrl(bindingWebsiteUrl);
            this.premiumBypass = premiumBypass;
            this.floodgateBypass = floodgateBypass;
            this.skinSiteBypass = skinSiteBypass;
        }

        public boolean allowOfflineDefault() {
            return this.allowOfflineDefault;
        }

        public AuthUxConfig ux() {
            return this.ux;
        }

        public OfflineIdentityConfig offlineIdentity() {
            return this.offlineIdentity;
        }

        public int passwordBypassMinutes() {
            return this.passwordBypassMinutes;
        }

        public String bindingWebsiteUrl() { return this.bindingWebsiteUrl; }

        public boolean premiumBypass() { return this.premiumBypass; }

        public boolean floodgateBypass() { return this.floodgateBypass; }

        public boolean skinSiteBypass() { return this.skinSiteBypass; }

        private static String normalizeUrl(String value) {
            String url = value == null || value.isBlank() ? "https://star-web.top" : value.trim();
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                throw new IllegalArgumentException("auth.binding-website-url must be an HTTP(S) URL");
            }
            return url.replaceAll("/+$", "");
        }

        public static AuthConfig defaults() {
            return new AuthConfig(false);
        }
    }

    public record OfflineIdentityConfig(String prefix, String displayName) {

        public OfflineIdentityConfig {
            prefix = normalized(prefix, ".");
            displayName = normalized(displayName, "前缀离线账号");
        }

        public static OfflineIdentityConfig defaults() {
            return new OfflineIdentityConfig(".", "前缀离线账号");
        }

        private static String normalized(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }

    public record PlayerListConfig(int refreshSeconds, String header, String footer) {

        private static final String DEFAULT_HEADER =
                "<gold><bold>StarMC</bold></gold>\n<gray>欢迎，<white>{starx_player}</white></gray>";
        private static final String DEFAULT_FOOTER =
                "<gray>服务器 <white>{starx_server}</white> · 在线 <white>{starx_online}</white></gray>";

        public PlayerListConfig {
            refreshSeconds = refreshSeconds < 1 || refreshSeconds > 300 ? 5 : refreshSeconds;
            header = normalized(header, DEFAULT_HEADER);
            footer = normalized(footer, DEFAULT_FOOTER);
        }

        public static PlayerListConfig defaults() {
            return new PlayerListConfig(5, DEFAULT_HEADER, DEFAULT_FOOTER);
        }

        private static String normalized(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.strip();
        }
    }

    public record AuthUxConfig(
            boolean titlesEnabled,
            boolean actionBarEnabled,
            boolean soundsEnabled,
            String promptSound,
            String successSound,
            String errorSound,
            AuthUxMessages messages,
            AuthCardMessages card) {

        public AuthUxConfig(
                boolean titlesEnabled,
                boolean actionBarEnabled,
                boolean soundsEnabled,
                String promptSound,
                String successSound,
                String errorSound,
                AuthUxMessages messages) {
            this(
                    titlesEnabled,
                    actionBarEnabled,
                    soundsEnabled,
                    promptSound,
                    successSound,
                    errorSound,
                    messages,
                    AuthCardMessages.defaults());
        }

        public AuthUxConfig {
            promptSound = normalized(promptSound, "minecraft:block.note_block.chime");
            successSound = normalized(successSound, "minecraft:entity.player.levelup");
            errorSound = normalized(errorSound, "minecraft:block.note_block.bass");
            messages = messages == null ? AuthUxMessages.defaults() : messages;
            card = card == null ? AuthCardMessages.defaults() : card;
        }

        public static AuthUxConfig defaults() {
            return new AuthUxConfig(
                    true,
                    true,
                    true,
                    "minecraft:block.note_block.chime",
                    "minecraft:entity.player.levelup",
                    "minecraft:block.note_block.bass",
                    AuthUxMessages.defaults(),
                    AuthCardMessages.defaults());
        }

        private static String normalized(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }

    public record AuthUxMessages(
            String loginTitle,
            String loginSubtitle,
            String registerTitle,
            String registerSubtitle,
            String totpTitle,
            String totpSubtitle,
            String successTitle,
            String successSubtitle,
            String loginPrompt,
            String loginActionBar,
            String registerPrompt,
            String registerActionBar,
            String totpPrompt,
            String totpActionBar) {

        public AuthUxMessages(
                String loginTitle,
                String loginSubtitle,
                String registerTitle,
                String registerSubtitle,
                String totpTitle,
                String totpSubtitle,
                String successTitle,
                String successSubtitle) {
            this(
                    loginTitle,
                    loginSubtitle,
                    registerTitle,
                    registerSubtitle,
                    totpTitle,
                    totpSubtitle,
                    successTitle,
                    successSubtitle,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        public AuthUxMessages {
            loginTitle = normalized(loginTitle, "欢迎回来");
            loginSubtitle = normalized(loginSubtitle, "请在聊天栏输入密码登录");
            registerTitle = normalized(registerTitle, "欢迎来到 StarMC");
            registerSubtitle = normalized(registerSubtitle, "在聊天栏输入密码即可注册");
            totpTitle = normalized(totpTitle, "二步验证");
            totpSubtitle = normalized(totpSubtitle, "请输入 6 位验证码");
            successTitle = normalized(successTitle, "认证成功");
            successSubtitle = normalized(successSubtitle, "正在进入服务器");
            loginPrompt = normalized(loginPrompt, "请输入密码完成登录。");
            loginActionBar = normalized(
                    loginActionBar,
                    "直接输入密码即可，聊天内容不会公开");
            registerPrompt = normalized(
                    registerPrompt,
                    "请直接在聊天栏输入你的密码完成注册。");
            registerActionBar = normalized(
                    registerActionBar,
                    "密码仅用于认证，不会显示在聊天中");
            totpPrompt = normalized(
                    totpPrompt,
                    "请输入验证器的 6 位验证码，或 10 位恢复码。");
            totpActionBar = normalized(totpActionBar, "验证码提交后将自动验证");
        }

        public static AuthUxMessages defaults() {
            return new AuthUxMessages(
                    "欢迎回来",
                    "请在聊天栏输入密码登录",
                    "欢迎来到 StarMC",
                    "在聊天栏输入密码即可注册",
                    "二步验证",
                    "请输入 6 位验证码",
                    "认证成功",
                    "正在进入服务器",
                    "请输入密码完成登录。",
                    "直接输入密码即可，聊天内容不会公开",
                    "请直接在聊天栏输入你的密码完成注册。",
                    "密码仅用于认证，不会显示在聊天中",
                    "请输入验证器的 6 位验证码，或 10 位恢复码。",
                    "验证码提交后将自动验证");
        }

        private static String normalized(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }

    public record AuthCardMessages(
            String title,
            String playerPrefix,
            String uuidPrefix,
            String accountTypePrefix,
            String currentIpPrefix,
            String lastIpPrefix,
            String lastLoginPrefix,
            String playtimePrefix,
            String registeredAtPrefix,
            String targetPrefix,
            String premiumAccount,
            String offlineAccount,
            String firstLoginAccount,
            String newPlayerName,
            String noHistory,
            String registrationPremiumAccount,
            String registrationOfflineAccount,
            String registrationHistory,
            String registrationPendingTime,
            String unknownValue,
            String targetUnavailable,
            String loginLinkText,
            String loginLinkHover,
            String registrationLinkText,
            String registrationLinkHover,
            String hourUnit,
            String minuteUnit) {

        public AuthCardMessages {
            title = normalized(title, "✦ StarMC 安全登录中心 ✦");
            playerPrefix = normalized(playerPrefix, "玩家：");
            uuidPrefix = normalized(uuidPrefix, "玩家 UUID：");
            accountTypePrefix = normalized(accountTypePrefix, "账号类型：");
            currentIpPrefix = normalized(currentIpPrefix, "当前 IP：");
            lastIpPrefix = normalized(lastIpPrefix, "上次 IP：");
            lastLoginPrefix = normalized(lastLoginPrefix, "上次登录：");
            playtimePrefix = normalized(playtimePrefix, "累计游玩：");
            registeredAtPrefix = normalized(registeredAtPrefix, "注册时间：");
            targetPrefix = normalized(targetPrefix, "认证目标：");
            premiumAccount = normalized(premiumAccount, "正版账号");
            offlineAccount = normalized(offlineAccount, "离线账号");
            firstLoginAccount = normalized(firstLoginAccount, "首次登录");
            newPlayerName = normalized(newPlayerName, "新玩家");
            noHistory = normalized(noHistory, "无历史记录");
            registrationPremiumAccount = normalized(
                    registrationPremiumAccount,
                    "正版账号（待注册）");
            registrationOfflineAccount = normalized(
                    registrationOfflineAccount,
                    "离线账号（待注册）");
            registrationHistory = normalized(
                    registrationHistory,
                    "首次注册，无历史记录");
            registrationPendingTime = normalized(
                    registrationPendingTime,
                    "完成注册后生成");
            unknownValue = normalized(unknownValue, "未知");
            targetUnavailable = normalized(targetUnavailable, "暂不可用");
            loginLinkText = normalized(
                    loginLinkText,
                    "[点击打开 StarX 账号绑定 · 绑定后免密登录]");
            loginLinkHover = normalized(
                    loginLinkHover,
                    "打开安全绑定页面（5 分钟内有效）");
            registrationLinkText = normalized(
                    registrationLinkText,
                    "[打开 StarX 账号中心 · 注册后可绑定免密登录]");
            registrationLinkHover = normalized(
                    registrationLinkHover,
                    "先完成游戏内注册，再进行账号绑定");
            hourUnit = normalized(hourUnit, "小时");
            minuteUnit = normalized(minuteUnit, "分钟");
        }

        public static AuthCardMessages defaults() {
            return new AuthCardMessages(
                    "✦ StarMC 安全登录中心 ✦",
                    "玩家：",
                    "玩家 UUID：",
                    "账号类型：",
                    "当前 IP：",
                    "上次 IP：",
                    "上次登录：",
                    "累计游玩：",
                    "注册时间：",
                    "认证目标：",
                    "正版账号",
                    "离线账号",
                    "首次登录",
                    "新玩家",
                    "无历史记录",
                    "正版账号（待注册）",
                    "离线账号（待注册）",
                    "首次注册，无历史记录",
                    "完成注册后生成",
                    "未知",
                    "暂不可用",
                    "[点击打开 StarX 账号绑定 · 绑定后免密登录]",
                    "打开安全绑定页面（5 分钟内有效）",
                    "[打开 StarX 账号中心 · 注册后可绑定免密登录]",
                    "先完成游戏内注册，再进行账号绑定",
                    "小时",
                    "分钟");
        }

        private static String normalized(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }
}
