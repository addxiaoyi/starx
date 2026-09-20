package io.github.addxiaoyi.starx.common.identity;

import io.github.addxiaoyi.starx.api.dto.UserDto;
import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import io.github.addxiaoyi.starx.common.model.StarxUser;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
    return accountId(minecraftUuid, null);
  }

  public String accountId(UUID minecraftUuid, String username) {
    return accountId(minecraftUuid, username, null);
  }

  public String accountId(UUID minecraftUuid, String username, IdentitySource trustedSource) {
    Objects.requireNonNull(minecraftUuid, "minecraftUuid");
    String normalizedName = username == null ? null : normalizeUsername(username);
    AccountIdentity existing = identities.findByMinecraftUuid(minecraftUuid).orElse(null);
    if (existing != null) {
      if (normalizedName != null
          && isTrustedMigrationSource(trustedSource)
          && !existing.currentName().equals(normalizedName)) {
        identities.rename(minecraftUuid, normalizedName);
      }
      return existing.accountId();
    }

    UserDto user = users.findByUuid(minecraftUuid).orElse(null);
    if (user == null && normalizedName != null) {
      UserDto offlineAccount = users.findByUsername(normalizedName).orElse(null);
      if (offlineAccount != null
          && !offlineAccount.premium()
          && offlineUuid(offlineAccount.username()).equals(offlineAccount.uuid())) {
        if (!isTrustedMigrationSource(trustedSource)) {
          throw new IllegalArgumentException("Trusted identity proof is required for UUID migration");
        }
        AccountIdentity legacy = identities.findByMinecraftUuid(offlineAccount.uuid()).orElse(null);
        if (legacy == null) {
          try {
            identities.save(new AccountIdentity(
                ACCOUNT_PREFIX + offlineAccount.uuid(),
                offlineAccount.uuid(),
                IdentitySource.OFFLINE,
                normalizedName));
            legacy = identities.findByMinecraftUuid(offlineAccount.uuid()).orElse(null);
          } catch (IdentityConflictException race) {
            legacy = identities.findByMinecraftUuid(offlineAccount.uuid()).orElseThrow(() -> race);
          }
        }
        if (legacy != null && legacy.source() == IdentitySource.OFFLINE) {
          try {
            identities.rebindMinecraftUuid(
                offlineAccount.uuid(), minecraftUuid, trustedSource, normalizedName);
          } catch (IdentityConflictException race) {
            return identities.findByMinecraftUuid(minecraftUuid)
                .map(AccountIdentity::accountId)
                .orElseThrow(() -> race);
          }
          return legacy.accountId();
        }
      }
      throw new IllegalArgumentException("Player is not registered");
    }
    if (user == null) {
      throw new IllegalArgumentException("Player is not registered");
    }
    String accountId = ACCOUNT_PREFIX + minecraftUuid;
    IdentitySource source = trustedSource != null
        ? trustedSource
        : user.premium() ? IdentitySource.MOJANG : IdentitySource.OFFLINE;
    try {
      identities.save(new AccountIdentity(accountId, minecraftUuid, source, user.username()));
    } catch (IdentityConflictException race) {
      AccountIdentity winner = identities.findByMinecraftUuid(minecraftUuid).orElseThrow(() -> race);
      return winner.accountId();
    }
    return accountId;
  }

  public void remove(UUID minecraftUuid) {
    this.identities.remove(Objects.requireNonNull(minecraftUuid, "minecraftUuid"));
  }

  private static boolean isTrustedMigrationSource(IdentitySource source) {
    return source == IdentitySource.MOJANG || source == IdentitySource.FLOODGATE;
  }

  private static String normalizeUsername(String username) {
    String normalized = Objects.requireNonNull(username, "username").trim();
    if (normalized.isEmpty()) throw new IllegalArgumentException("username must not be blank");
    return normalized;
  }

  private static UUID offlineUuid(String username) {
    return UUID.nameUUIDFromBytes(
        ("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
  }

  public UUID minecraftUuid(String accountId) {
    if (accountId == null || accountId.isBlank()) return null;
    AccountIdentity canonical = canonicalIdentity(accountId.trim());
    return canonical == null ? null : canonical.minecraftUuid();
  }

  public UUID resolveMinecraftUuid(UUID minecraftUuid) {
    Objects.requireNonNull(minecraftUuid, "minecraftUuid");
    AccountIdentity direct = identities.findByMinecraftUuid(minecraftUuid).orElse(null);
    if (direct != null) {
      AccountIdentity canonical = canonicalIdentity(direct.accountId());
      return canonical == null ? direct.minecraftUuid() : canonical.minecraftUuid();
    }

    AccountIdentity migrated = canonicalIdentity(ACCOUNT_PREFIX + minecraftUuid);
    if (migrated != null && isTrustedMigrationSource(migrated.source())) {
      return migrated.minecraftUuid();
    }
    return minecraftUuid;
  }

  public Set<UUID> knownMinecraftUuids(UUID requested) {
    Objects.requireNonNull(requested, "requested");
    UUID current = resolveMinecraftUuid(requested);
    LinkedHashSet<UUID> known = new LinkedHashSet<>();
    known.add(requested);
    known.add(current);
    AccountIdentity identity = identities.findByMinecraftUuid(requested)
        .orElseGet(() -> identities.findByMinecraftUuid(current).orElse(null));
    if (identity != null) {
      identities.findAllByAccountId(identity.accountId()).stream()
          .map(AccountIdentity::minecraftUuid)
          .forEach(known::add);
    }
    resolveUser(current).map(UserDto::uuid).ifPresent(known::add);
    return Collections.unmodifiableSet(known);
  }

  public String username(String accountId) {
    if (accountId == null || accountId.isBlank()) return null;
    AccountIdentity canonical = canonicalIdentity(accountId.trim());
    return canonical == null ? null : canonical.currentName();
  }

  private AccountIdentity canonicalIdentity(String accountId) {
    var accountIdentities = identities.findAllByAccountId(accountId);
    return accountIdentities.stream()
        .filter(identity -> isTrustedMigrationSource(identity.source()))
        .findFirst()
        .orElse(accountIdentities.isEmpty() ? null : accountIdentities.get(0));
  }

  public Optional<UserDto> resolveUser(UUID minecraftUuid) {
    Objects.requireNonNull(minecraftUuid, "minecraftUuid");
    UUID current = resolveMinecraftUuid(minecraftUuid);
    Optional<UserDto> exact = users.findByUuid(current);
    if (exact.isPresent()) return exact;

    AccountIdentity identity = identities.findByMinecraftUuid(current).orElse(null);
    if (identity == null
        || identity.source() != IdentitySource.MOJANG
            && identity.source() != IdentitySource.FLOODGATE) {
      return Optional.empty();
    }
    for (AccountIdentity historical : identities.findAllByAccountId(identity.accountId())) {
      if (historical.source() != IdentitySource.OFFLINE) continue;
      Optional<UserDto> legacy = users.findByUuid(historical.minecraftUuid())
          .filter(user -> offlineUuid(user.username()).equals(user.uuid()));
      if (legacy.isPresent()) return legacy;
    }
    return users.findByUsername(identity.currentName())
        .filter(user -> offlineUuid(user.username()).equals(user.uuid()));
  }

  public Optional<UserDto> resolveUserByName(String username) {
    String normalizedName = normalizeUsername(username);
    Optional<UserDto> direct = users.findByUsername(normalizedName);
    if (direct.isPresent()) return direct;
    return resolveMinecraftUuidByCurrentName(normalizedName).flatMap(this::resolveUser);
  }

  public Optional<StarxUser> resolveFullUserByName(String username) {
    String normalizedName = normalizeUsername(username);
    Optional<StarxUser> direct = users.findFullByUsername(normalizedName);
    if (direct.isPresent()) return direct;
    return resolveMinecraftUuidByCurrentName(normalizedName)
        .flatMap(this::resolveUser)
        .flatMap(user -> users.findFullByUuid(user.uuid()));
  }

  private Optional<UUID> resolveMinecraftUuidByCurrentName(String username) {
    for (AccountIdentity identity : identities.findAllByCurrentName(username)) {
      AccountIdentity canonical = canonicalIdentity(identity.accountId());
      if (canonical != null && canonical.currentName().equalsIgnoreCase(username)) {
        return Optional.of(canonical.minecraftUuid());
      }
    }
    return Optional.empty();
  }
}
