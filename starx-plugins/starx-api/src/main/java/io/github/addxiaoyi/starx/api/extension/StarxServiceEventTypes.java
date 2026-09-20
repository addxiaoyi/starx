package io.github.addxiaoyi.starx.api.extension;

/** Stable event type identifiers exposed through {@link StarxService}. */
public final class StarxServiceEventTypes {
  /** An extension completed enable successfully. */
  public static final String EXTENSION_ENABLED = "starx.extension.enabled";
  /** An extension was unregistered. */
  public static final String EXTENSION_DISABLED = "starx.extension.disabled";
  /** A Paper or Folia backend service became ready. */
  public static final String BACKEND_READY = "starx.backend.ready";
  /** A Paper or Folia backend service is stopping. */
  public static final String BACKEND_STOPPING = "starx.backend.stopping";

  /** A player authentication flow started. */
  public static final String PLAYER_LOGIN_START = "player:login:start";
  /** A player authentication flow succeeded. */
  public static final String PLAYER_LOGIN_SUCCESS = "player:login:success";
  /** A player authentication flow failed. */
  public static final String PLAYER_LOGIN_FAILED = "player:login:failed";
  /** A player session ended. */
  public static final String PLAYER_LOGOUT = "player:logout";
  /** A player account was registered. */
  public static final String PLAYER_REGISTER = "player:register";
  /** TOTP was enabled for a player. */
  public static final String PLAYER_TOTP_ENABLED = "player:totp:enabled";
  /** TOTP was disabled for a player. */
  public static final String PLAYER_TOTP_DISABLED = "player:totp:disabled";
  /** A generic security alert was emitted. */
  public static final String SECURITY_ALERT = "security:alert";
  /** A bot-detection check failed. */
  public static final String BOT_CHECK_FAILED = "security:bot:failed";
  /** Repeated authentication attempts triggered brute-force detection. */
  public static final String PLAYER_BRUTE_FORCE = "player:brute-force";
  /** A skin refresh was requested. */
  public static final String SKIN_REFRESH_REQUEST = "skin:refresh:request";
  /** A skin was applied to a player. */
  public static final String SKIN_APPLIED = "skin:applied";
  /** Stored skin data was updated. */
  public static final String SKIN_UPDATED = "skin:updated";
  /** An external identity was linked. */
  public static final String LINK_EXTERNAL_USER = "link:external-user";
  /** An external identity was unlinked. */
  public static final String UNLINK_EXTERNAL_USER = "unlink:external-user";
  /** An administrator requested a player kick. */
  public static final String ADMIN_KICK_PLAYER = "admin:kick:player";
  /** An administrator requested a player ban. */
  public static final String ADMIN_BAN_PLAYER = "admin:ban:player";
  /** An administrator reset a player password. */
  public static final String ADMIN_RESET_PASSWORD = "admin:reset:password";
  /** An administrator bound an email address. */
  public static final String ADMIN_BIND_EMAIL = "admin:bind:email";
  /** Player state synchronization was requested. */
  public static final String SYNC_PLAYER_STATE = "sync:player:state";
  /** Configuration synchronization was requested. */
  public static final String SYNC_CONFIG = "sync:config";
  /** A Plan statistics report was emitted. */
  public static final String PLAN_STATS_REPORT = "plan:stats:report";

  private StarxServiceEventTypes() {}
}
