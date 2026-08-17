package io.github.addxiaoyi.starx.common.database;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class JdbcSchemaMigrationTest {
  @Test
  void onlyDuplicateConstraintFailuresAreIgnorable() {
    assertTrue(JdbcSchema.isDuplicateConstraint(new SQLException("UNIQUE constraint failed", "23000", 19)));
    assertTrue(JdbcSchema.isDuplicateConstraint(new SQLException("duplicate key", "23505")));
    assertFalse(JdbcSchema.isDuplicateConstraint(new SQLException("database is locked", "HY000")));
    assertFalse(JdbcSchema.isDuplicateConstraint(new SQLException("permission denied", "42000")));
  }
}
