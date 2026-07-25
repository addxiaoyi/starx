package io.github.addxiaoyi.starx.common.identity;

import io.github.addxiaoyi.starx.api.dto.UserDto;
import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import java.util.Objects;
import java.util.UUID;

public final class AccountIdentityResolver {
  private static final String ACCOUNT_PREFIX = "mc:";

  private final JdbcAccountIdentityRepository identities;
  private final JdbcUserRepository users;

  public AccountIdentityResolver(
      JdbcAccountIdentityRepository identities, JdbcUserRepository users) {
    this.identities = Objects.requireNonNull(identities, "identities");
    this.users = Objects.requireNonNull(users, "users");
  }

  public String accountId(UUID minecraftUuid) {
    Objects.requireNonNull(minecraftUuid, "minecraftUuid");
    AccountIdentity existing = identities.findByMinecraftUuid(minecraftUuid).orElse(null);
    if (existing != null) return existing.accountId();

    UserDto user = users.findByUuid(minecraftUuid)
        .orElseThrow(() -> new IllegalArgumentException("Player is not registered"));
    String accountId = ACCOUNT_PREFIX + minecraftUuid;
    IdentitySource source = user.premium() ? IdentitySource.MOJANG : IdentitySource.OFFLINE;
    try {
      identities.save(new AccountIdentity(accountId, minecraftUuid, source, user.username()));
    } catch (IdentityConflictException race) {
      AccountIdentity winner = identities.findByMinecraftUuid(minecraftUuid).orElseThrow(() -> race);
      return winner.accountId();
    }
    return accountId;
  }

  public UUID minecraftUuid(String accountId) {
    if (accountId == null || accountId.isBlank()) return null;
    return identities.findByAccountId(accountId.trim())
        .map(AccountIdentity::minecraftUuid)
        .orElse(null);
  }

  public String username(String accountId) {
    if (accountId == null || accountId.isBlank()) return null;
    return identities.findByAccountId(accountId.trim())
        .map(AccountIdentity::currentName)
        .orElse(null);
  }
}
