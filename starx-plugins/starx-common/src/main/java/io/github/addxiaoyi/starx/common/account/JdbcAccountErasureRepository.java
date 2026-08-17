package io.github.addxiaoyi.starx.common.account;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/** Removes player-owned StarX data without rewriting the Minecraft UUID. */
public final class JdbcAccountErasureRepository {
  private final DataSource dataSource;

  public JdbcAccountErasureRepository(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  public int erase(UUID playerUuid, long erasedAt) {
    Objects.requireNonNull(playerUuid, "playerUuid");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        erase(connection, playerUuid);
        connection.commit();
        return 1;
      } catch (Exception error) {
        connection.rollback();
        throw error;
      }
    } catch (SQLException error) {
      throw new IllegalStateException("Failed to erase account data", error);
    } catch (RuntimeException error) {
      throw error;
    } catch (Exception error) {
      throw new IllegalStateException("Failed to erase account data", error);
    }
  }

  public void eraseAndComplete(
      JdbcAccountDeletionRepository deletions,
      String requestId,
      String claimToken,
      UUID playerUuid,
      long erasedAt) {
    Objects.requireNonNull(deletions, "deletions");
    Objects.requireNonNull(playerUuid, "playerUuid");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        erase(connection, playerUuid);
        if (!deletions.complete(connection, requestId, claimToken, erasedAt)) {
          throw new IllegalStateException("Deletion request changed before completion: " + requestId);
        }
        connection.commit();
      } catch (Exception error) {
        connection.rollback();
        throw error;
      }
    } catch (SQLException error) {
      throw new IllegalStateException("Failed to erase account data", error);
    } catch (RuntimeException error) {
      throw error;
    } catch (Exception error) {
      throw new IllegalStateException("Failed to erase account data", error);
    }
  }

  public void eraseAndCompletePending(
      JdbcAccountDeletionRepository deletions,
      UUID playerUuid,
      Set<UUID> knownPlayerUuids,
      long erasedAt) {
    Objects.requireNonNull(deletions, "deletions");
    Objects.requireNonNull(playerUuid, "playerUuid");
    Objects.requireNonNull(knownPlayerUuids, "knownPlayerUuids");
    LinkedHashSet<UUID> playerUuids = new LinkedHashSet<>(knownPlayerUuids);
    playerUuids.add(playerUuid);
    playerUuids.remove(null);
    if (playerUuids.isEmpty()) throw new IllegalArgumentException("knownPlayerUuids must not be empty");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        deletions.completePending(connection, playerUuids, erasedAt);
        if (deletions.hasClaimed(connection, playerUuids)) {
          throw new IllegalStateException("Deletion request is already being processed");
        }
        erase(connection, playerUuid);
        connection.commit();
      } catch (Exception error) {
        connection.rollback();
        throw error;
      }
    } catch (SQLException error) {
      throw new IllegalStateException("Failed to erase account data", error);
    } catch (RuntimeException error) {
      throw error;
    } catch (Exception error) {
      throw new IllegalStateException("Failed to erase account data", error);
    }
  }

  private static void erase(Connection connection, UUID playerUuid) throws SQLException {
    List<AccountLink> links = findAccountLinks(connection, playerUuid);
    Set<UUID> playerUuids = new LinkedHashSet<>();
    playerUuids.add(playerUuid);
    for (AccountLink link : links) {
      playerUuids.add(link.minecraftUuid());
      if ("MOJANG".equals(link.identitySource()) || "FLOODGATE".equals(link.identitySource())) {
        UUID storedOfflineUuid = findStoredOfflineUuid(connection, link.currentName());
        if (storedOfflineUuid != null) playerUuids.add(storedOfflineUuid);
      }
    }
    for (UUID target : playerUuids) {
      delete(connection, "DELETE FROM starx_player_server_segments WHERE player_uuid = ?", target);
      delete(connection, "DELETE FROM starx_player_sessions WHERE player_uuid = ?", target);
      delete(connection, "DELETE FROM starx_tutorial_progress WHERE player_uuid = ?", target);
      delete(connection, "DELETE FROM starx_trusted_devices WHERE player_uuid = ?", target);
      delete(connection, "DELETE FROM starx_ip_sessions WHERE player_uuid = ?", target);
      delete(connection, "DELETE FROM starx_binding_audit WHERE player_uuid = ?", target);
      delete(connection, "DELETE FROM starx_player_bindings WHERE player_uuid = ?", target);
      delete(connection, "DELETE FROM starx_website_bindings WHERE player_uuid = ?", target);
      deletePlayerReferences(connection, target);
      delete(connection, "DELETE FROM starx_users WHERE uuid = ?", target);
    }
    if (!links.isEmpty()) {
      String accountId = links.get(0).accountId();
      delete(connection, "DELETE FROM starx_account_identities WHERE account_id = ?", accountId);
      delete(connection, "DELETE FROM starx_binding_challenges WHERE account_id = ?", accountId);
      delete(connection, "DELETE FROM starx_accounts WHERE account_id = ?", accountId);
    } else {
      eraseOrphanedMinecraftAccount(connection, playerUuid);
    }
  }

  private static List<AccountLink> findAccountLinks(Connection connection, UUID playerUuid)
      throws SQLException {
    String accountId = findAccountId(connection, "minecraft_uuid = ?", playerUuid.toString());
    if (accountId == null) {
      accountId = findAccountId(connection, "account_id = ?", "mc:" + playerUuid);
    }
    if (accountId == null) return List.of();

    try (PreparedStatement query = connection.prepareStatement(
        "SELECT account_id, minecraft_uuid, identity_source, current_name "
            + "FROM starx_account_identities WHERE account_id = ?")) {
      query.setString(1, accountId);
      try (ResultSet rows = query.executeQuery()) {
        List<AccountLink> links = new ArrayList<>();
        while (rows.next()) {
          links.add(new AccountLink(
              rows.getString(1), UUID.fromString(rows.getString(2)), rows.getString(3), rows.getString(4)));
        }
        return List.copyOf(links);
      }
    }
  }

  private static String findAccountId(Connection connection, String predicate, String value)
      throws SQLException {
    try (PreparedStatement query = connection.prepareStatement(
        "SELECT account_id "
            + "FROM starx_account_identities WHERE " + predicate)) {
      query.setString(1, value);
      try (ResultSet rows = query.executeQuery()) {
        return rows.next() ? rows.getString(1) : null;
      }
    }
  }

  private static UUID offlineUuid(String username) {
    return UUID.nameUUIDFromBytes(
        ("OfflinePlayer:" + username).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static UUID findStoredOfflineUuid(Connection connection, String username)
      throws SQLException {
    try (PreparedStatement query = connection.prepareStatement(
        "SELECT uuid, username FROM starx_users WHERE LOWER(username) = LOWER(?)")) {
      query.setString(1, username);
      try (ResultSet row = query.executeQuery()) {
        if (!row.next()) return null;
        UUID uuid = UUID.fromString(row.getString(1));
        return offlineUuid(row.getString(2)).equals(uuid) ? uuid : null;
      }
    }
  }

  private static void eraseOrphanedMinecraftAccount(Connection connection, UUID playerUuid)
      throws SQLException {
    String accountId = "mc:" + playerUuid;
    if (accountExists(connection, accountId)) {
      delete(connection, "DELETE FROM starx_binding_challenges WHERE account_id = ?", accountId);
      delete(connection, "DELETE FROM starx_accounts WHERE account_id = ?", accountId);
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

  private static void deletePlayerReferences(Connection connection, UUID playerUuid) throws SQLException {
    String value = playerUuid.toString();
    delete(connection, "DELETE FROM starx_announcement_reads WHERE player_uuid = ?", value);
    delete(connection, "DELETE FROM starx_staff_vote_records WHERE voter_uuid = ?", value);
    delete(connection, "DELETE FROM starx_staff_votes WHERE target_uuid = ? OR initiator_uuid = ?", value, value);
    delete(connection, "DELETE FROM starx_reports WHERE reporter_uuid = ? OR target_uuid = ? OR resolved_by = ?", value, value, value);
    delete(connection, "DELETE FROM starx_staff_notes WHERE target_uuid = ? OR staff_uuid = ?", value, value);
    delete(connection, "DELETE FROM starx_punishments WHERE target_uuid = ? OR staff_uuid = ?", value, value);
  }

  private static int delete(Connection connection, String sql, Object... values) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < values.length; index++) {
        Object value = values[index];
        statement.setObject(index + 1, value instanceof UUID uuid ? uuid.toString() : value);
      }
      return statement.executeUpdate();
    }
  }

  private record AccountLink(String accountId, UUID minecraftUuid, String identitySource, String currentName) {}
}
