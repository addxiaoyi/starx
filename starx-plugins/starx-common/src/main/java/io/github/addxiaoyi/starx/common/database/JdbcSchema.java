package io.github.addxiaoyi.starx.common.database;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

final class JdbcSchema {
  private JdbcSchema() {}

  static void addColumnIfMissing(
      Connection connection, String table, String column, String definition) throws SQLException {
    if (columnExists(connection, table, column)) return;
    try (Statement statement = connection.createStatement()) {
      statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    } catch (SQLException error) {
      if (!columnExists(connection, table, column)) throw error;
    }
  }

  static void createIndex(
      Connection connection, String table, String name, boolean unique, String columns)
      throws SQLException {
    if (indexExists(connection, table, name)) return;
    String sql = "CREATE " + (unique ? "UNIQUE " : "") + "INDEX " + name
        + " ON " + table + "(" + columns + ")";
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    } catch (SQLException error) {
      if (!indexExists(connection, table, name)) throw error;
    }
  }

  private static boolean indexExists(Connection connection, String table, String name)
      throws SQLException {
    try (ResultSet indexes = connection.getMetaData().getIndexInfo(
        connection.getCatalog(), null, table, false, false)) {
      while (indexes.next()) {
        String current = indexes.getString("INDEX_NAME");
        if (current != null && current.equalsIgnoreCase(name)) return true;
      }
    }
    return false;
  }

  private static boolean columnExists(Connection connection, String table, String column)
      throws SQLException {
    try (ResultSet columns = connection.getMetaData().getColumns(
        connection.getCatalog(), null, table, column)) {
      if (columns.next()) return true;
    }
    try (ResultSet columns = connection.getMetaData().getColumns(
        connection.getCatalog(), null, table.toUpperCase(), column.toUpperCase())) {
      return columns.next();
    }
  }
}
