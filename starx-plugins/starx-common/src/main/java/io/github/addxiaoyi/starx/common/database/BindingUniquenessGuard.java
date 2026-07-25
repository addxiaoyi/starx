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
    if (conflict != null) {
      throw new IllegalStateException(
          "Duplicate player binding blocks the uniqueness migration: " + conflict
              + ". Resolve the duplicate rows before restarting StarX.");
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
