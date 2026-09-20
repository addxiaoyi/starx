package io.github.addxiaoyi.starx.common.model;

import java.time.Instant;
import java.util.UUID;

public record TrustedDevice(
    UUID id,
    UUID playerId,
    String fingerprintHash,
    String regionKey,
    String label,
    Instant firstSeenAt,
    Instant lastSeenAt,
    Instant expiresAt,
    Instant revokedAt
) {
  public boolean activeAt(Instant now) {
    return this.revokedAt == null && this.expiresAt.isAfter(now);
  }
}
