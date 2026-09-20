package io.github.addxiaoyi.starx.common.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

final class JdbcStore {

  private final DataSource source;

  JdbcStore(DataSource source) {
    this.source = Objects.requireNonNull(source, "source");
  }

  <T> Optional<T> one(String sql, Binder binder, RowMapper<T> mapper) {
    try (Connection connection = this.source.getConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      binder.bind(statement);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? Optional.ofNullable(mapper.map(rows)) : Optional.empty();
      }
    } catch (SQLException error) {
      throw new RuntimeException("Query failed: " + sql, error);
    }
  }

  <T> List<T> many(String sql, Binder binder, RowMapper<T> mapper) {
    List<T> values = new ArrayList<>();
    try (Connection connection = this.source.getConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      binder.bind(statement);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          values.add(mapper.map(rows));
        }
      }
      return values;
    } catch (SQLException error) {
      throw new RuntimeException("Query failed: " + sql, error);
    }
  }

  int execute(String sql, Binder binder) {
    try (Connection connection = this.source.getConnection()) {
      return execute(connection, sql, binder);
    } catch (SQLException error) {
      throw new RuntimeException("Execute failed: " + sql, error);
    }
  }

  void transaction(TransactionBody body) {
    try (Connection connection = this.source.getConnection()) {
      boolean previousAutoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try {
        body.execute(connection);
        connection.commit();
      } catch (Exception error) {
        connection.rollback();
        throw new RuntimeException("Transaction failed", error);
      } finally {
        connection.setAutoCommit(previousAutoCommit);
      }
    } catch (SQLException error) {
      throw new RuntimeException("Transaction failed", error);
    }
  }

  static int execute(Connection connection, String sql, Binder binder) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      binder.bind(statement);
      return statement.executeUpdate();
    }
  }

  @FunctionalInterface
  interface Binder {
    void bind(PreparedStatement statement) throws SQLException;
  }

  @FunctionalInterface
  interface RowMapper<T> {
    T map(ResultSet rows) throws SQLException;
  }

  @FunctionalInterface
  interface TransactionBody {
    void execute(Connection connection) throws Exception;
  }
}
