package io.github.addxiaoyi.starx.common.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.addxiaoyi.starx.common.model.Report;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class JdbcReportRepositoryTest {
  @Test
  void closedReportCannotBeOverwrittenByAnotherDecision(@TempDir Path tempDir) throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("reports.db").toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("CREATE TABLE starx_reports ("
          + "id VARCHAR(36) PRIMARY KEY, reporter_uuid VARCHAR(36) NOT NULL, "
          + "target_uuid VARCHAR(36) NOT NULL, category VARCHAR(32) NOT NULL, "
          + "details VARCHAR(512), status VARCHAR(16) NOT NULL, "
          + "resolved_by VARCHAR(36), resolved_at BIGINT)");
    }

    String reportId = UUID.randomUUID().toString();
    UUID reporter = UUID.randomUUID();
    UUID target = UUID.randomUUID();
    JdbcReportRepository repository = new JdbcReportRepository(source);
    repository.create(new Report(
        reportId, reporter, target, "CHEATING", "details", "PENDING", null, null));

    assertEquals(true, repository.resolve(reportId, "staff-one"));
    assertEquals(false, repository.dismiss(reportId, "staff-two"));

    Report stored = repository.findById(reportId).orElseThrow();
    assertEquals("RESOLVED", stored.status());
    assertEquals("staff-one", stored.resolvedBy());
  }
}
