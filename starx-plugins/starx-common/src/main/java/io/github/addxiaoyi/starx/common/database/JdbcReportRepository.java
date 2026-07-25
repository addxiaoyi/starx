package io.github.addxiaoyi.starx.common.database;

import io.github.addxiaoyi.starx.common.model.Report;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

public class JdbcReportRepository {

  private static final String COLUMNS =
      "id, reporter_uuid, target_uuid, category, details, status, resolved_by, resolved_at";

  private final JdbcStore store;

  public JdbcReportRepository(DataSource source) {
    this.store = new JdbcStore(source);
  }

  public void create(Report report) {
    Objects.requireNonNull(report, "report");
    this.store.execute(
        "INSERT INTO starx_reports (id, reporter_uuid, target_uuid, category, details, status) VALUES (?, ?, ?, ?, ?, ?)",
        statement -> {
          statement.setString(1, report.id());
          statement.setString(2, report.reporterUuid().toString());
          statement.setString(3, report.targetUuid().toString());
          statement.setString(4, report.category());
          statement.setString(5, report.details());
          statement.setString(6, report.status());
        });
  }

  public List<Report> findByStatus(String status) {
    return this.store.many(
        "SELECT " + COLUMNS + " FROM starx_reports WHERE status = ? ORDER BY id DESC",
        statement -> statement.setString(1, status),
        this::map);
  }

  public List<Report> findByTarget(UUID targetId) {
    return byUuid("target_uuid", targetId);
  }

  public List<Report> findByReporter(UUID reporterId) {
    return byUuid("reporter_uuid", reporterId);
  }

  public Optional<Report> findById(String id) {
    return this.store.one(
        "SELECT " + COLUMNS + " FROM starx_reports WHERE id = ?",
        statement -> statement.setString(1, id),
        this::map);
  }

  public List<Report> findAll() {
    return this.store.many(
        "SELECT " + COLUMNS + " FROM starx_reports ORDER BY id DESC",
        statement -> { },
        this::map);
  }

  public void resolve(String id, String resolvedBy) {
    setStatus(id, "RESOLVED", resolvedBy);
  }

  public void dismiss(String id, String resolvedBy) {
    setStatus(id, "DISMISSED", resolvedBy);
  }

  private List<Report> byUuid(String column, UUID playerId) {
    return this.store.many(
        "SELECT " + COLUMNS + " FROM starx_reports WHERE " + column + " = ? ORDER BY id DESC",
        statement -> statement.setString(1, playerId.toString()),
        this::map);
  }

  private void setStatus(String id, String status, String resolvedBy) {
    this.store.execute(
        "UPDATE starx_reports SET status = ?, resolved_by = ?, resolved_at = ? WHERE id = ?",
        statement -> {
          statement.setString(1, status);
          statement.setString(2, resolvedBy);
          statement.setLong(3, System.currentTimeMillis());
          statement.setString(4, id);
        });
  }

  private Report map(ResultSet rows) throws SQLException {
    long resolvedAt = rows.getLong("resolved_at");
    return new Report(
        rows.getString("id"),
        UUID.fromString(rows.getString("reporter_uuid")),
        UUID.fromString(rows.getString("target_uuid")),
        rows.getString("category"),
        rows.getString("details"),
        rows.getString("status"),
        rows.getString("resolved_by"),
        rows.wasNull() ? null : resolvedAt);
  }
}
