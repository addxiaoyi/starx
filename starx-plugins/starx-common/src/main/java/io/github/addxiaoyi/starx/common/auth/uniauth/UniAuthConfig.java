package io.github.addxiaoyi.starx.common.auth.uniauth;

import java.util.Locale;
import java.util.Objects;

public final class UniAuthConfig {
  private final boolean enabled;
  private final String apiUrl;
  private final String apiKey;
  private final int timeoutMs;
  private final boolean bridgeMode;
  private final ProfileSyncConfig profileSync;

  public UniAuthConfig(
      boolean enabled,
      String apiUrl,
      String apiKey,
      int timeoutMs,
      boolean bridgeMode) {
    this(enabled, apiUrl, apiKey, timeoutMs, bridgeMode, ProfileSyncConfig.defaults());
  }

  public UniAuthConfig(
      boolean enabled,
      String apiUrl,
      String apiKey,
      int timeoutMs,
      boolean bridgeMode,
      ProfileSyncConfig profileSync) {
    this.enabled = enabled;
    this.apiUrl = normalizeUrl(apiUrl);
    this.apiKey = Objects.requireNonNullElse(apiKey, "").trim();
    this.timeoutMs = timeoutMs <= 0 ? 5000 : timeoutMs;
    this.bridgeMode = bridgeMode;
    this.profileSync = profileSync == null ? ProfileSyncConfig.defaults() : profileSync;
  }

  public static UniAuthConfig defaults() {
    return new UniAuthConfig(false, "https://api.example.com/uniauth/", "", 5000, false);
  }

  public boolean enabled() { return enabled; }
  public String apiUrl() { return apiUrl; }
  public String apiKey() { return apiKey; }
  public int timeoutMs() { return timeoutMs; }
  public boolean bridgeMode() { return bridgeMode; }
  public ProfileSyncConfig profileSync() { return profileSync; }

  private static String normalizeUrl(String value) {
    String url = Objects.requireNonNullElse(value, "").trim();
    if (url.isBlank()) {
      return url;
    }
    return url.endsWith("/") ? url : url + "/";
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) return true;
    if (!(obj instanceof UniAuthConfig that)) return false;
    return enabled == that.enabled
        && timeoutMs == that.timeoutMs
        && bridgeMode == that.bridgeMode
        && Objects.equals(apiUrl, that.apiUrl)
        && Objects.equals(apiKey, that.apiKey)
        && Objects.equals(profileSync, that.profileSync);
  }

  @Override
  public int hashCode() {
    return Objects.hash(enabled, apiUrl, apiKey, timeoutMs, bridgeMode, profileSync);
  }

  @Override
  public String toString() {
    return "UniAuthConfig[enabled=" + enabled
        + ", apiUrl=" + apiUrl
        + ", apiKey=***, timeoutMs=" + timeoutMs
        + ", bridgeMode=" + bridgeMode
        + ", profileSync=" + profileSync + "]";
  }

  public record ProfileSyncConfig(
      boolean enabled,
      boolean onLogin,
      boolean syncEmail,
      boolean syncExternalUserId,
      boolean overwriteLocalValues,
      String sourceSystem) {
    public ProfileSyncConfig {
      sourceSystem = normalizeSource(sourceSystem);
    }

    public static ProfileSyncConfig defaults() {
      return new ProfileSyncConfig(false, true, true, true, false, "uniauth");
    }

    private static String normalizeSource(String value) {
      String normalized = Objects.requireNonNullElse(value, "uniauth").trim().toLowerCase(Locale.ROOT);
      if (normalized.isBlank() || !normalized.matches("[a-z0-9_-]{1,50}")) {
        return "uniauth";
      }
      return normalized;
    }
  }
}
