package io.github.addxiaoyi.starx.common.database;

import io.github.addxiaoyi.starx.common.model.StaffNote;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.Collection;
import javax.sql.DataSource;

public class JdbcStaffNoteRepository {

  private static final String COLUMNS =
      "id, target_uuid, note, severity, staff_uuid, created_at";

  private final JdbcStore store;

  public JdbcStaffNoteRepository(DataSource source) {
    this.store = new JdbcStore(source);
  }

  public void addNote(StaffNote note) {
    Objects.requireNonNull(note, "note");
    this.store.execute(
        "INSERT INTO starx_staff_notes (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?)",
        statement -> {
          statement.setString(1, note.id());
          statement.setString(2, note.targetUuid().toString());
          statement.setString(3, note.note());
          statement.setString(4, note.severity());
          statement.setString(5, note.staffUuid().toString());
          statement.setLong(6, note.createdAt());
        });
  }

  public List<StaffNote> findByPlayer(UUID targetId) {
    return findByPlayers(List.of(targetId));
  }

  public List<StaffNote> findByPlayers(Collection<UUID> targetIds) {
    List<UUID> ids = JdbcUuidQuery.distinct(targetIds);
    if (ids.isEmpty()) return List.of();
    return this.store.many(
        "SELECT " + COLUMNS + " FROM starx_staff_notes WHERE target_uuid IN ("
            + JdbcUuidQuery.placeholders(ids.size()) + ") ORDER BY created_at DESC",
        statement -> JdbcUuidQuery.bind(statement, ids),
        this::map);
  }

  public List<StaffNote> findAll() {
    return this.store.many(
        "SELECT " + COLUMNS + " FROM starx_staff_notes ORDER BY created_at DESC",
        statement -> { },
        this::map);
  }

  public Optional<StaffNote> findById(String id) {
    return this.store.one(
        "SELECT " + COLUMNS + " FROM starx_staff_notes WHERE id = ?",
        statement -> statement.setString(1, id),
        this::map);
  }

  private StaffNote map(ResultSet rows) throws SQLException {
    return new StaffNote(
        rows.getString("id"),
        UUID.fromString(rows.getString("target_uuid")),
        rows.getString("note"),
        rows.getString("severity"),
        UUID.fromString(rows.getString("staff_uuid")),
        rows.getLong("created_at"));
  }
}
