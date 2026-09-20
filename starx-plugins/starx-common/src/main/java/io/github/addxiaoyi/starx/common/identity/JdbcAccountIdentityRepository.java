package io.github.addxiaoyi.starx.common.identity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
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

  public void remove(UUID minecraftUuid) {
    Objects.requireNonNull(minecraftUuid, "minecraftUuid");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        String accountId = findAccountIdOptional(connection, minecraftUuid).orElse(null);
        if (accountId == null) {
          connection.commit();
          return;
        }
        try (PreparedStatement delete = connection.prepareStatement(
            "DELETE FROM starx_account_identities WHERE minecraft_uuid = ?")) {
          delete.setString(1, minecraftUuid.toString());
          delete.executeUpdate();
        }
        try (PreparedStatement remaining = connection.prepareStatement(
            "SELECT 1 FROM starx_account_identities WHERE account_id = ? LIMIT 1")) {
          remaining.setString(1, accountId);
          try (ResultSet rows = remaining.executeQuery()) {
            if (!rows.next()) {
              try (PreparedStatement challenges = connection.prepareStatement(
                  "DELETE FROM starx_binding_challenges WHERE account_id = ?")) {
                challenges.setString(1, accountId);
                challenges.executeUpdate();
              }
              try (PreparedStatement account = connection.prepareStatement(
                  "DELETE FROM starx_accounts WHERE account_id = ?")) {
                account.setString(1, accountId);
                account.executeUpdate();
              }
            }
          }
        }
        connection.commit();
      } catch (SQLException error) {
        connection.rollback();
        throw error;
      }
    } catch (SQLException error) {
      throw new IllegalStateException("Failed to remove account identity", error);
    }
  }

  public void rebindMinecraftUuid(
      UUID previousUuid, UUID currentUuid, IdentitySource source, String currentName) {
    Objects.requireNonNull(previousUuid, "previousUuid");
    Objects.requireNonNull(currentUuid, "currentUuid");
    Objects.requireNonNull(source, "source");
    String name = Objects.requireNonNull(currentName, "currentName").trim();
    if (name.isEmpty()) throw new IllegalArgumentException("currentName must not be blank");
    if (previousUuid.equals(currentUuid)) {
      rename(currentUuid, name);
      return;
    }
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        String accountId = findAccountId(connection, previousUuid);
        Optional<String> currentAccount = findAccountIdOptional(connection, currentUuid);
        if (currentAccount.isPresent() && !accountId.equals(currentAccount.get())) {
          throw new IdentityConflictException("Minecraft UUID is already bound to another identity");
        }

        long lastSeen = nextLastSeenAt(connection, accountId, System.currentTimeMillis());
        if (currentAccount.isPresent()) {
          try (PreparedStatement update = connection.prepareStatement(
              "UPDATE starx_account_identities SET identity_source = ?, current_name = ?, last_seen_at = ? WHERE minecraft_uuid = ?")) {
            update.setString(1, source.name());
            update.setString(2, name);
            update.setLong(3, lastSeen);
            update.setString(4, currentUuid.toString());
            update.executeUpdate();
          }
        } else {
          try (PreparedStatement insert = connection.prepareStatement(
              "INSERT INTO starx_account_identities (account_id, minecraft_uuid, identity_source, current_name, first_seen_at, last_seen_at) VALUES (?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, accountId);
            insert.setString(2, currentUuid.toString());
            insert.setString(3, source.name());
            insert.setString(4, name);
            insert.setLong(5, lastSeen);
            insert.setLong(6, lastSeen);
            insert.executeUpdate();
          }
        }
        connection.commit();
      } catch (RuntimeException error) {
        connection.rollback();
        throw error;
      } catch (SQLException error) {
        connection.rollback();
        if (isConstraintViolation(error)) {
          throw new IdentityConflictException("Minecraft UUID is already bound to another identity");
        }
        throw new IllegalStateException("Failed to rebind account identity", error);
      }
    } catch (SQLException error) {
      throw new IllegalStateException("Failed to rebind account identity", error);
    }
  }

  public Optional<AccountIdentity> findByMinecraftUuid(UUID minecraftUuid) {
    try (Connection connection = dataSource.getConnection(); PreparedStatement query = connection.prepareStatement(
        "SELECT account_id, minecraft_uuid, identity_source, current_name FROM starx_account_identities WHERE minecraft_uuid = ?")) {
      query.setString(1, minecraftUuid.toString());
      try (ResultSet row = query.executeQuery()) {
        if (!row.next()) return Optional.empty();
        return Optional.of(readIdentity(row));
      }
    } catch (SQLException error) {
      throw new IllegalStateException("Failed to load account identity", error);
    }
  }

  public Optional<AccountIdentity> findByAccountId(String accountId) {
    List<AccountIdentity> identities = findAllByAccountId(accountId);
    return identities.isEmpty() ? Optional.empty() : Optional.of(identities.get(0));
  }

  public List<AccountIdentity> findAllByAccountId(String accountId) {
    String normalized = Objects.requireNonNull(accountId, "accountId").trim();
    if (normalized.isEmpty()) throw new IllegalArgumentException("accountId must not be blank");
    try (Connection connection = dataSource.getConnection(); PreparedStatement query = connection.prepareStatement(
        "SELECT account_id, minecraft_uuid, identity_source, current_name FROM starx_account_identities "
            + "WHERE account_id = ? ORDER BY last_seen_at DESC, first_seen_at DESC, minecraft_uuid DESC")) {
      query.setString(1, normalized);
      try (ResultSet row = query.executeQuery()) {
        List<AccountIdentity> result = new ArrayList<>();
        while (row.next()) result.add(readIdentity(row));
        return List.copyOf(result);
      }
    } catch (SQLException error) {
      throw new IllegalStateException("Failed to load account identity", error);
    }
  }

  public List<AccountIdentity> findAllByCurrentName(String currentName) {
    String normalized = Objects.requireNonNull(currentName, "currentName").trim();
    if (normalized.isEmpty()) throw new IllegalArgumentException("currentName must not be blank");
    try (Connection connection = dataSource.getConnection(); PreparedStatement query = connection.prepareStatement(
        "SELECT account_id, minecraft_uuid, identity_source, current_name "
            + "FROM starx_account_identities WHERE LOWER(current_name) = LOWER(?) "
            + "ORDER BY last_seen_at DESC, first_seen_at DESC, minecraft_uuid DESC")) {
      query.setString(1, normalized);
      try (ResultSet row = query.executeQuery()) {
        List<AccountIdentity> result = new ArrayList<>();
        while (row.next()) result.add(readIdentity(row));
        return List.copyOf(result);
      }
    } catch (SQLException error) {
      throw new IllegalStateException("Failed to find account identity by current name", error);
    }
  }

  private static AccountIdentity readIdentity(ResultSet row) throws SQLException {
    return new AccountIdentity(row.getString(1), UUID.fromString(row.getString(2)),
        IdentitySource.valueOf(row.getString(3)), row.getString(4));
  }

  private static String findAccountId(Connection connection, UUID minecraftUuid) throws SQLException {
    return findAccountIdOptional(connection, minecraftUuid)
        .orElseThrow(() -> new IllegalArgumentException(
            "Minecraft UUID is not registered: " + minecraftUuid));
  }

  private static Optional<String> findAccountIdOptional(Connection connection, UUID minecraftUuid)
      throws SQLException {
    try (PreparedStatement query = connection.prepareStatement(
        "SELECT account_id FROM starx_account_identities WHERE minecraft_uuid = ?")) {
      query.setString(1, minecraftUuid.toString());
      try (ResultSet row = query.executeQuery()) {
        return row.next() ? Optional.of(row.getString(1)) : Optional.empty();
      }
    }
  }

  private static long nextLastSeenAt(Connection connection, String accountId, long candidate)
      throws SQLException {
    try (PreparedStatement query = connection.prepareStatement(
        "SELECT COALESCE(MAX(last_seen_at), 0) FROM starx_account_identities WHERE account_id = ?")) {
      query.setString(1, accountId);
      try (ResultSet row = query.executeQuery()) {
        long latest = row.next() ? row.getLong(1) : 0L;
        return Math.max(candidate, latest == Long.MAX_VALUE ? Long.MAX_VALUE : latest + 1);
      }
    }
  }

  private static void ensureAccount(Connection connection, String accountId, long now) throws SQLException {
    try (PreparedStatement insert = connection.prepareStatement(
        "INSERT INTO starx_accounts (account_id, created_at) VALUES (?, ?)")) {
      insert.setString(1, accountId);
      insert.setLong(2, now);
      insert.executeUpdate();
    } catch (SQLException error) {
      if (!isConstraintViolation(error)) {
        throw error;
      }
      if (!accountExists(connection, accountId)) {
        throw error;
      }
    }
  }

  private static boolean accountExists(Connection connection, String accountId) throws SQLException {
    try (PreparedStatement query = connection.prepareStatement(
        "SELECT 1 FROM starx_accounts WHERE account_id = ?")) {
      query.setString(1, accountId);
      try (ResultSet rows = query.executeQuery()) {
        return rows.next();
      }
    }
  }

  private static boolean isConstraintViolation(SQLException error) {
    SQLException current = error;
    while (current != null) {
      String state = current.getSQLState();
      if (current instanceof java.sql.SQLIntegrityConstraintViolationException
          || state != null && state.startsWith("23")) {
        return true;
      }
      String message = current.getMessage();
      if (message != null) {
        String normalized = message.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("unique") || normalized.contains("duplicate")) return true;
      }
      current = current.getNextException();
    }
    return false;
  }
}
