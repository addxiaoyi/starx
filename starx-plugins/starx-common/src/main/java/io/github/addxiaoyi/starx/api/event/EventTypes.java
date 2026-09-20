/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.api.event;

public final class EventTypes {
    public static final String PLAYER_LOGIN_START = "player:login:start";
    public static final String PLAYER_LOGIN_SUCCESS = "player:login:success";
    public static final String PLAYER_LOGIN_FAILED = "player:login:failed";
    public static final String PLAYER_LOGOUT = "player:logout";
    public static final String PLAYER_REGISTER = "player:register";
    public static final String PLAYER_TOTP_ENABLED = "player:totp:enabled";
    public static final String PLAYER_TOTP_DISABLED = "player:totp:disabled";
    public static final String SECURITY_ALERT = "security:alert";
    public static final String BOT_CHECK_FAILED = "security:bot:failed";
    public static final String PLAYER_BRUTE_FORCE = "player:brute-force";
    public static final String SKIN_REFRESH_REQUEST = "skin:refresh:request";
    public static final String SKIN_APPLIED = "skin:applied";
    public static final String SKIN_UPDATED = "skin:updated";
    public static final String LINK_EXTERNAL_USER = "link:external-user";
    public static final String UNLINK_EXTERNAL_USER = "unlink:external-user";
    public static final String ADMIN_KICK_PLAYER = "admin:kick:player";
    public static final String ADMIN_BAN_PLAYER = "admin:ban:player";
    public static final String ADMIN_RESET_PASSWORD = "admin:reset:password";
    public static final String ADMIN_BIND_EMAIL = "admin:bind:email";
    public static final String SYNC_PLAYER_STATE = "sync:player:state";
    public static final String SYNC_CONFIG = "sync:config";
    public static final String PLAN_STATS_REPORT = "plan:stats:report";

    private EventTypes() {
    }
}
