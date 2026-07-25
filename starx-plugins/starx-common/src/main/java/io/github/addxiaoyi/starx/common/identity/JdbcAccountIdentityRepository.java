package io.github.addxiaoyi.starx.common.identity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

public final class JdbcAccountIdentityRepository {
  public static final String CREATE_ACCOUNTS_SQL = "CREATE TABLE IF NOT EXISTS starx_accounts (account_id VARCHAR(64) PRIMARY KEY, created_at BIGINT NOT NULL)";
  public static final String CREATE_IDENTITIES_SQL = "CREATE TABLE IF NOT EXISTS starx_account_identities (account_id VARCHAR(64) NOT NULL, minecraft_uuid VARCHAR(36) NOT NULL UNIQUE, identity_source VARCHAR(16) NOT NULL, current_name VARCHAR(16) NOT NULL, first_seen_at BIGINT NOT NULL, last_seen_at BIGINT NOT NULL, PRIMARY KEY (account_id, minecraft_uuid), FOREIGN KEY (account_id) REFERENCES starx_accounts(account_id))";

  private final DataSource dataSource;

  public JdbcAccountIdentityRepository(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  public void save(AccountIdentity identity) {
    Objects.requireNonNull(identity, "identity");
    long now = System.currentTimeMillis();
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        ensureAccount(connection, identity.accountId(), now);
        try (PreparedStatement insert = connection.prepareStatement(
            "INSERT INTO starx_account_identities (account_id, minecraft_uuid, identity_source, current_name, first_seen_at, last_seen_at) VALUES (?, ?, ?, ?, ?, ?)")) {
          insert.setString(1, identity.accountId());
          insert.setString(2, identity.minecraftUuid().toString());
          insert.setString(3, identity.source().name());
          insert.setString(4, identity.currentName());
          insert.setLong(5, now);
          insert.setLong(6, now);
          insert.executeUpdate();
        }
        connection.commit();
      } catch (SQLException error) {
        connection.rollback();
        if (isConstraintViolation(error)) {
          throw new IdentityConflictException("Minecraft UUID is already bound to another identity");
        }
        throw error;
      }
    } catch (SQLException error) {
      throw new IllegalStateException("Failed to save account identity", error);
    }
  }

  public void rename(UUID minecraftUuid, String newName) {
    Objects.requireNonNull(minecraftUuid, "minecraftUuid");
    String name = Objects.requireNonNull(newName, "newName").trim();
    if (name.isEmpty()) throw new IllegalArgumentException("newName must not be blank");
    try (Connection connection = dataSource.getConnection(); PreparedStatement update = connection.prepareStatement(
        "UPDATE starx_account_identities SET current_name = ?, last_seen_at = ? WHERE minecraft_uuid = ?")) {
      update.setString(1, name);
      update.setLong(2, System.currentTimeMillis());
      update.setString(3, minecraftUuid.toString());
      if (update.executeUpdate() != 1) throw new IllegalArgumentException("Minecraft UUID is not registered");
    } catch (SQLException error) {
      throw new IllegalStateException("Failed to rename account identity", error);
    }
  }

  public Optional<AccountIdentity> findByMinecraftUuid(UUID minecraftUuid) {
    try (Connection connection = dataSource.getConnection(); PreparedStatement query = connection.prepareStatement(
        "SELECT account_id, minecraft_uuid, identity_source, current_name FROM starx_account_identities WHERE minecraft_uuid = ?")) {
      query.setString(1, minecraftUuid.toString());
      try (ResultSet row = query.executeQuery()) {
        if (!row.next()) return Optional.empty();
        return Optional.of(new AccountIdentity(row.getString(1), UUID.fromString(row.getString(2)),
            IdentitySource.valueOf(row.getString(3)), row.getString(4)));
      }
    } catch (SQLException error) {
      throw new IllegalStateException("Failed to load account identity", error);
    }
  }

  public Optional<AccountIdentity> findByAccountId(String accountId) {
    String normalized = Objects.requireNonNull(accountId, "accountId").trim();
    if (normalized.isEmpty()) throw new IllegalArgumentException("accountId must not be blank");
    try (Connection connection = dataSource.getConnection(); PreparedStatement query = connection.prepareStatement(
        "SELECT account_id, minecraft_uuid, identity_source, current_name FROM starx_account_identities WHERE account_id = ?")) {
      query.setString(1, normalized);
      try (ResultSet row = query.executeQuery()) {
        if (!row.next()) return Optional.empty();
        return Optional.of(new AccountIdentity(row.getString(1), UUID.fromString(row.getString(2)),
            IdentitySource.valueOf(row.getString(3)), row.getString(4)));
      }
    } catch (SQLException error) {
      throw new IllegalStateException("Failed to load account identity", error);
    }
  }

  private static void ensureAccount(Connection connection, String accountId, long now) throws SQLException {
    try (PreparedStatement insert = connection.prepareStatement(
        "INSERT INTO starx_accounts (account_id, created_at) VALUES (?, ?)")) {
      insert.setString(1, accountId);
      insert.setLong(2, now);
      insert.executeUpdate();
    } catch (SQLException ignored) {
      // Existing account is expected for subsequent identities.
    }
  }

  private static boolean isConstraintViolation(SQLException error) {
    return error.getMessage() != null && (error.getMessage().contains("UNIQUE") || error.getMessage().contains("constraint"));
  }
}
