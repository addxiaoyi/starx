package io.github.addxiaoyi.starx.common.database;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

final class BindingUniquenessGuard {
  private BindingUniquenessGuard() {}

  static void verify(Connection connection) throws SQLException {
    String conflict = firstConflict(connection, "qq_id", "QQ");
    if (conflict == null) conflict = firstConflict(connection, "discord_id", "Discord");
    if (conflict == null) conflict = firstExternalConflict(connection, "starx_users");
    if (conflict == null) conflict = firstExternalConflict(connection, "starx_website_bindings");
    if (conflict == null) conflict = firstCrossTableExternalConflict(connection);
    if (conflict != null) {
      throw new IllegalStateException(
          "Duplicate player binding blocks the uniqueness migration: " + conflict
              + ". Resolve the duplicate rows before restarting StarX.");
    }
  }

  private static String firstExternalConflict(Connection connection, String table)
      throws SQLException {
    String sql = "SELECT external_user_id, COUNT(*) AS total FROM " + table
        + " WHERE external_user_id IS NOT NULL AND TRIM(external_user_id) <> ''"
        + " GROUP BY external_user_id HAVING COUNT(*) > 1";
    try (Statement query = connection.createStatement(); ResultSet rows = query.executeQuery(sql)) {
      if (!rows.next()) return null;
      return table + ".external_user_id=" + rows.getString(1) + " count=" + rows.getInt(2);
    }
  }

  private static String firstCrossTableExternalConflict(Connection connection)
      throws SQLException {
    String sql = "SELECT external_user_id, COUNT(DISTINCT owner_uuid) AS owners FROM ("
        + "SELECT external_user_id, uuid AS owner_uuid FROM starx_users "
        + "WHERE external_user_id IS NOT NULL AND TRIM(external_user_id) <> '' UNION ALL "
        + "SELECT external_user_id, player_uuid AS owner_uuid FROM starx_website_bindings "
        + "WHERE external_user_id IS NOT NULL AND TRIM(external_user_id) <> '') "
        + "GROUP BY external_user_id HAVING COUNT(DISTINCT owner_uuid) > 1";
    try (Statement query = connection.createStatement(); ResultSet rows = query.executeQuery(sql)) {
      if (!rows.next()) return null;
      return "cross-table external_user_id=" + rows.getString(1)
          + " owners=" + rows.getInt(2);
    }
  }

  private static String firstConflict(Connection connection, String column, String label)
      throws SQLException {
    String sql = "SELECT " + column + ", COUNT(*) AS total FROM starx_player_bindings "
        + "WHERE " + column + " IS NOT NULL GROUP BY " + column + " HAVING COUNT(*) > 1";
    try (Statement query = connection.createStatement(); ResultSet rows = query.executeQuery(sql)) {
      if (!rows.next()) return null;
      return label + "=" + rows.getString(1) + " count=" + rows.getInt(2);
    }
  }
}
