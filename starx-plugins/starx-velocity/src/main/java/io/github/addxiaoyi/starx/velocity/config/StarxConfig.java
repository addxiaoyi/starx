/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.velocity.config;

import io.github.addxiaoyi.starx.common.auth.uniauth.UniAuthConfig;
import io.github.addxiaoyi.starx.common.config.DatabaseConfig;
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
    private final Map<String, ModuleConfig> modules;

    public StarxConfig(String apiKey, HttpConfig http, WebhookConfig webhook, DatabaseConfig database, UniAuthConfig uniauth, NapcatConfig napcat, TotpConfig totp, UworldConfig uworld, AuthConfig auth, Map<String, ModuleConfig> modules) {
        this(apiKey, http, webhook, database, uniauth, napcat, totp, uworld, auth,
                PlayerListConfig.defaults(), modules);
    }

    public StarxConfig(String apiKey, HttpConfig http, WebhookConfig webhook, DatabaseConfig database, UniAuthConfig uniauth, NapcatConfig napcat, TotpConfig totp, UworldConfig uworld, AuthConfig auth, PlayerListConfig playerList, Map<String, ModuleConfig> modules) {
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
        private final String bind;
        private final int port;
        private final String frpPublicUrl;

        public HttpConfig(String bind, int port) {
            this(bind, port, "");
        }

        public HttpConfig(String bind, int port, String frpPublicUrl) {
            this.bind = bind == null || bind.isBlank() ? "127.0.0.1" : bind;
            this.port = port <= 0 || port > 65535 ? 8788 : port;
            this.frpPublicUrl = normalizePublicUrl(frpPublicUrl);
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
            this.allowOfflineDefault = allowOfflineDefault;
            this.ux = ux == null ? AuthUxConfig.defaults() : ux;
            this.offlineIdentity = offlineIdentity == null
                    ? OfflineIdentityConfig.defaults()
                    : offlineIdentity;
            this.passwordBypassMinutes = Math.max(0, passwordBypassMinutes);
            this.bindingWebsiteUrl = normalizeUrl(bindingWebsiteUrl);
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
            AuthUxMessages messages) {

        public AuthUxConfig {
            promptSound = normalized(promptSound, "minecraft:block.note_block.chime");
            successSound = normalized(successSound, "minecraft:entity.player.levelup");
            errorSound = normalized(errorSound, "minecraft:block.note_block.bass");
            messages = messages == null ? AuthUxMessages.defaults() : messages;
        }

        public static AuthUxConfig defaults() {
            return new AuthUxConfig(
                    true,
                    true,
                    true,
                    "minecraft:block.note_block.chime",
                    "minecraft:entity.player.levelup",
                    "minecraft:block.note_block.bass",
                    AuthUxMessages.defaults());
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
            String successSubtitle) {

        public AuthUxMessages {
            loginTitle = normalized(loginTitle, "欢迎回来");
            loginSubtitle = normalized(loginSubtitle, "请在聊天栏输入密码登录");
            registerTitle = normalized(registerTitle, "欢迎来到 StarMC");
            registerSubtitle = normalized(registerSubtitle, "在聊天栏输入密码即可注册");
            totpTitle = normalized(totpTitle, "二步验证");
            totpSubtitle = normalized(totpSubtitle, "请输入 6 位验证码");
            successTitle = normalized(successTitle, "认证成功");
            successSubtitle = normalized(successSubtitle, "正在进入服务器");
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
                    "正在进入服务器");
        }

        private static String normalized(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }
}
