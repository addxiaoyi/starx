package io.github.addxiaoyi.starx.common.database;

import io.github.addxiaoyi.starx.common.model.Punishment;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

public class JdbcPunishmentRepository {

  private static final String COLUMNS =
      "id, target_uuid, target_name, type, reason, staff_uuid, staff_name, created_at, expires_at, active";

  private final JdbcStore store;

  public JdbcPunishmentRepository(DataSource source) {
    this.store = new JdbcStore(source);
  }

  public void record(Punishment punishment) {
    Objects.requireNonNull(punishment, "punishment");
    this.store.execute(
        "INSERT INTO starx_punishments (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        statement -> {
          statement.setString(1, punishment.id());
          statement.setString(2, punishment.targetUuid().toString());
          statement.setString(3, punishment.targetName());
          statement.setString(4, punishment.type());
          statement.setString(5, punishment.reason());
          statement.setString(6, punishment.staffUuid().toString());
          statement.setString(7, punishment.staffName());
          statement.setLong(8, punishment.createdAt());
          if (punishment.expiresAt() == null) {
            statement.setNull(9, Types.BIGINT);
          } else {
            statement.setLong(9, punishment.expiresAt());
          }
          statement.setBoolean(10, punishment.active());
        });
  }

  public List<Punishment> findByPlayer(UUID targetId) {
    return byUuid("target_uuid", targetId);
  }

  public List<Punishment> findByType(String type) {
    return this.store.many(
        "SELECT " + COLUMNS + " FROM starx_punishments WHERE type = ? ORDER BY created_at DESC",
        statement -> statement.setString(1, type),
        this::map);
  }

  public List<Punishment> findActive() {
    return this.store.many(
        "SELECT " + COLUMNS + " FROM starx_punishments WHERE active = TRUE ORDER BY created_at DESC",
        statement -> { },
        this::map);
  }

  public List<Punishment> findActiveByTargetUuid(UUID targetId) {
    return this.store.many(
        "SELECT " + COLUMNS + " FROM starx_punishments WHERE target_uuid = ?"
            + " AND active = TRUE AND (expires_at IS NULL OR expires_at > ?) ORDER BY created_at DESC",
        statement -> {
          statement.setString(1, targetId.toString());
          statement.setLong(2, System.currentTimeMillis());
        },
        this::map);
  }

  public Optional<Punishment> findById(String id) {
    return this.store.one(
        "SELECT " + COLUMNS + " FROM starx_punishments WHERE id = ?",
        statement -> statement.setString(1, id),
        this::map);
  }

  public void deactivate(String id) {
    this.store.execute(
        "UPDATE starx_punishments SET active = FALSE WHERE id = ?",
        statement -> statement.setString(1, id));
  }

  public List<Punishment> findAll() {
    return this.store.many(
        "SELECT " + COLUMNS + " FROM starx_punishments ORDER BY created_at DESC",
        statement -> { },
        this::map);
  }

  private List<Punishment> byUuid(String column, UUID targetId) {
    return this.store.many(
        "SELECT " + COLUMNS + " FROM starx_punishments WHERE " + column + " = ? ORDER BY created_at DESC",
        statement -> statement.setString(1, targetId.toString()),
        this::map);
  }

  private Punishment map(ResultSet rows) throws SQLException {
    long expiresAt = rows.getLong("expires_at");
    return new Punishment(
        rows.getString("id"),
        UUID.fromString(rows.getString("target_uuid")),
        rows.getString("target_name"),
        rows.getString("type"),
        rows.getString("reason"),
        UUID.fromString(rows.getString("staff_uuid")),
        rows.getString("staff_name"),
        rows.getLong("created_at"),
        rows.wasNull() ? null : expiresAt,
        rows.getBoolean("active"));
  }
}
