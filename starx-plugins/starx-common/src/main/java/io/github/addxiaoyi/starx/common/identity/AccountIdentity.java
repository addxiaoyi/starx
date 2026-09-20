package io.github.addxiaoyi.starx.common.identity;

import java.util.Objects;
import java.util.UUID;

public record AccountIdentity(
    String accountId,
    UUID minecraftUuid,
    IdentitySource source,
    String currentName
) {
  public AccountIdentity {
    accountId = requireText(accountId, "accountId");
    minecraftUuid = Objects.requireNonNull(minecraftUuid, "minecraftUuid");
    source = Objects.requireNonNull(source, "source");
    currentName = requireText(currentName, "currentName");
  }

  public AccountIdentity rename(String newName) {
    return new AccountIdentity(accountId, minecraftUuid, source, newName);
  }

  public AccountIdentity replaceMinecraftUuid(UUID ignored) {
    throw new UnsupportedOperationException("Minecraft UUID is immutable");
  }

  private static String requireText(String value, String field) {
    String normalized = Objects.requireNonNull(value, field).trim();
    if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
    return normalized;
  }
}
