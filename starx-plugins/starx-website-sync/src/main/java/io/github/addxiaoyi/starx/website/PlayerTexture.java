package io.github.addxiaoyi.starx.website;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record PlayerTexture(
    String playerUuid,
    String playerName,
    String skinHash,
    String capeHash,
    String model,
    String source,
    String updatedAt,
    boolean deleted
) {
  private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
  private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern SOURCE = Pattern.compile("[a-z0-9._-]{1,40}");

  public PlayerTexture {
    playerUuid = UUID.fromString(Objects.requireNonNull(playerUuid, "playerUuid"))
        .toString();
    playerName = Objects.requireNonNullElse(playerName, "").trim();
    if (!PLAYER_NAME.matcher(playerName).matches()) {
      throw new IllegalArgumentException("Invalid Minecraft player name: " + playerName);
    }
    skinHash = normalizeHash(skinHash, !deleted, "skinHash");
    capeHash = normalizeHash(capeHash, false, "capeHash");
    model = Objects.requireNonNullElse(model, "classic").trim().toLowerCase(Locale.ROOT);
    if (!model.equals("classic") && !model.equals("slim")) {
      throw new IllegalArgumentException("Texture model must be classic or slim");
    }
    source = Objects.requireNonNullElse(source, "").trim().toLowerCase(Locale.ROOT);
    if (!SOURCE.matcher(source).matches()) {
      throw new IllegalArgumentException("Invalid texture source: " + source);
    }
    updatedAt = Instant.parse(Objects.requireNonNull(updatedAt, "updatedAt")).toString();
  }

  private static String normalizeHash(String value, boolean required, String label) {
    if (value == null || value.isBlank()) {
      if (required) {
        throw new IllegalArgumentException(label + " is required");
      }
      return null;
    }
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    if (!HASH.matcher(normalized).matches()) {
      throw new IllegalArgumentException(label + " must be a lowercase SHA-256 hash");
    }
    return normalized;
  }
}
