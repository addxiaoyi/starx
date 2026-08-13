package io.github.addxiaoyi.starx.common.account;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
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
        String accountId = accountId(connection, playerUuid);
        delete(connection, "DELETE FROM starx_player_server_segments WHERE player_uuid = ?", playerUuid);
        delete(connection, "DELETE FROM starx_player_sessions WHERE player_uuid = ?", playerUuid);
        delete(connection, "DELETE FROM starx_trusted_devices WHERE player_uuid = ?", playerUuid);
        delete(connection, "DELETE FROM starx_ip_sessions WHERE player_uuid = ?", playerUuid);
        delete(connection, "DELETE FROM starx_binding_audit WHERE player_uuid = ?", playerUuid);
        delete(connection, "DELETE FROM starx_player_bindings WHERE player_uuid = ?", playerUuid);
        delete(connection, "DELETE FROM starx_website_bindings WHERE player_uuid = ?", playerUuid);
        deletePlayerReferences(connection, playerUuid);
        delete(connection, "DELETE FROM starx_users WHERE uuid = ?", playerUuid);

        if (accountId != null) {
          delete(connection, "DELETE FROM starx_account_identities WHERE minecraft_uuid = ?", playerUuid);
          if (!hasIdentity(connection, accountId)) {
            delete(connection, "DELETE FROM starx_binding_challenges WHERE account_id = ?", accountId);
            delete(connection, "DELETE FROM starx_accounts WHERE account_id = ?", accountId);
          }
        }
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

  private static String accountId(Connection connection, UUID playerUuid) throws SQLException {
    try (PreparedStatement query = connection.prepareStatement(
        "SELECT account_id FROM starx_account_identities WHERE minecraft_uuid = ?")) {
      query.setString(1, playerUuid.toString());
      try (ResultSet rows = query.executeQuery()) {
        return rows.next() ? rows.getString(1) : null;
      }
    }
  }

  private static boolean hasIdentity(Connection connection, String accountId) throws SQLException {
    try (PreparedStatement query = connection.prepareStatement(
        "SELECT 1 FROM starx_account_identities WHERE account_id = ?")) {
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
}
