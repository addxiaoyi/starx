package io.github.addxiaoyi.starx.common.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class JdbcSchemaTest {
  @TempDir Path tempDir;

  @Test
  void indexCreationIsIdempotentWithoutVendorSpecificSyntax() throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("schema.db").toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("CREATE TABLE sample (value VARCHAR(64))");
      JdbcSchema.createIndex(connection, "sample", "idx_sample_value", true, "value");
      JdbcSchema.createIndex(connection, "sample", "idx_sample_value", true, "value");
      try (ResultSet indexes = connection.getMetaData().getIndexInfo(null, null, "sample", false, false)) {
        int count = 0;
        while (indexes.next()) {
          if ("idx_sample_value".equalsIgnoreCase(indexes.getString("INDEX_NAME"))) count++;
        }
        assertEquals(1, count);
      }
    }
  }

  @Test
  void columnMigrationIsIdempotentForExistingTables() throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("column.db").toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("CREATE TABLE sample (value VARCHAR(64))");
      JdbcSchema.addColumnIfMissing(connection, "sample", "device_fingerprint", "VARCHAR(512)");
      JdbcSchema.addColumnIfMissing(connection, "sample", "device_fingerprint", "VARCHAR(512)");
      try (ResultSet columns = connection.getMetaData().getColumns(
          null, null, "sample", "device_fingerprint")) {
        int count = 0;
        while (columns.next()) count++;
        assertEquals(1, count);
      }
    }
  }
}
