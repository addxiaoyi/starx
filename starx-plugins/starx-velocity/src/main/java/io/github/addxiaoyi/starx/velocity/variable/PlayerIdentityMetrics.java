package io.github.addxiaoyi.starx.velocity.variable;

import io.github.addxiaoyi.starx.common.model.PlayerBinding;
import io.github.addxiaoyi.starx.common.model.StarxUser;
import io.github.addxiaoyi.starx.common.session.PlayerSessionSummary;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

public record PlayerIdentityMetrics(
    long playtimeSeconds,
    int serverFootprint,
    int reputation,
    String trustLevel) {

  public PlayerIdentityMetrics {
    playtimeSeconds = Math.max(0L, playtimeSeconds);
    serverFootprint = Math.max(0, serverFootprint);
    if (reputation < 0 || reputation > 100) {
      throw new IllegalArgumentException("reputation must be between 0 and 100");
    }
    trustLevel = trustLevel == null || trustLevel.isBlank() ? "未评级" : trustLevel;
  }

  public static PlayerIdentityMetrics from(
      StarxUser user,
      PlayerBinding binding,
      PlayerSessionSummary session,
      Map<String, Long> serverPlaytimeMillis,
      Instant now) {
    long profileSeconds = user == null || user.totalPlaytime() == null
        ? 0L : Math.max(0L, user.totalPlaytime());
    long sessionSeconds = session == null ? 0L : Math.max(0L, session.totalPlaytime() / 1_000L);
    long playtimeSeconds = Math.max(profileSeconds, sessionSeconds);
    int footprint = serverPlaytimeMillis == null ? 0 : (int) serverPlaytimeMillis.entrySet().stream()
        .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank())
        .filter(entry -> entry.getValue() != null && entry.getValue() > 0L)
        .count();
    if (user == null) return new PlayerIdentityMetrics(playtimeSeconds, footprint, 0, "未评级");

    int reputation = 0;
    reputation += (int) Math.min(40L, playtimeSeconds / 3_600L);
    reputation += Math.min(20, session == null ? 0 : Math.max(0, session.loginCount()));
    reputation += hasText(user.totpSecret()) ? 15 : 0;
    reputation += user.premium() ? 10 : 0;
    reputation += binding != null && (hasText(binding.qqId()) || hasText(binding.discordId())) ? 10 : 0;
    if (user.createdAt() != null && now != null && !now.isBefore(user.createdAt())) {
      reputation += (int) Math.min(5L, Duration.between(user.createdAt(), now).toDays() / 30L);
    }
    reputation = Math.min(100, reputation);
    return new PlayerIdentityMetrics(playtimeSeconds, footprint, reputation, trustLevel(reputation));
  }

  static String trustLevel(int reputation) {
    if (reputation < 20) return "新手";
    if (reputation < 50) return "稳定";
    if (reputation < 80) return "可信";
    return "核心";
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
