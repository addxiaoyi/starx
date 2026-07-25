package io.github.addxiaoyi.starx.common.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;
import javax.sql.DataSource;

public final class JdbcRuntimeSettingRepository {
  public static final String CREATE_TABLE_SQL = "CREATE TABLE IF NOT EXISTS starx_runtime_settings (setting_key VARCHAR(96) PRIMARY KEY, setting_value VARCHAR(512) NOT NULL, updated_at BIGINT NOT NULL)";

  private final DataSource dataSource;

  public JdbcRuntimeSettingRepository(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  public boolean getBoolean(String key, boolean fallback) {
    String value = get(key);
    if (value == null) return fallback;
    return switch (value.toLowerCase(Locale.ROOT)) {
      case "true" -> true;
      case "false" -> false;
      default -> throw new IllegalStateException("Runtime setting is not boolean: " + key);
    };
  }

  public void putBoolean(String key, boolean value, long updatedAt) {
    put(key, Boolean.toString(value), updatedAt);
  }

  public String get(String key) {
    String normalized = requireText(key, "key");
    try (Connection connection = dataSource.getConnection(); PreparedStatement query = connection.prepareStatement(
        "SELECT setting_value FROM starx_runtime_settings WHERE setting_key = ?")) {
      query.setString(1, normalized);
      try (ResultSet row = query.executeQuery()) {
        return row.next() ? row.getString(1) : null;
      }
    } catch (SQLException error) {
      throw new IllegalStateException("Failed to read runtime setting: " + normalized, error);
    }
  }

  public void put(String key, String value, long updatedAt) {
    String normalizedKey = requireText(key, "key");
    String normalizedValue = requireText(value, "value");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        int changed = update(connection, normalizedKey, normalizedValue, updatedAt);
        if (changed == 0) insert(connection, normalizedKey, normalizedValue, updatedAt);
        connection.commit();
      } catch (SQLException error) {
        connection.rollback();
        throw error;
      }
    } catch (SQLException error) {
      throw new IllegalStateException("Failed to write runtime setting: " + normalizedKey, error);
    }
  }

  private static int update(Connection connection, String key, String value, long updatedAt)
      throws SQLException {
    try (PreparedStatement update = connection.prepareStatement(
        "UPDATE starx_runtime_settings SET setting_value = ?, updated_at = ? WHERE setting_key = ?")) {
      update.setString(1, value);
      update.setLong(2, updatedAt);
      update.setString(3, key);
      return update.executeUpdate();
    }
  }

  private static void insert(Connection connection, String key, String value, long updatedAt)
      throws SQLException {
    try (PreparedStatement insert = connection.prepareStatement(
        "INSERT INTO starx_runtime_settings (setting_key, setting_value, updated_at) VALUES (?, ?, ?)")) {
      insert.setString(1, key);
      insert.setString(2, value);
      insert.setLong(3, updatedAt);
      insert.executeUpdate();
    }
  }

  private static String requireText(String value, String field) {
    String text = Objects.requireNonNull(value, field).trim();
    if (text.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
    return text;
  }
}
