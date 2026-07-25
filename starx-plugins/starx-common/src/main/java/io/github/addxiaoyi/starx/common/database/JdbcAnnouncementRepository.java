package io.github.addxiaoyi.starx.common.database;

import io.github.addxiaoyi.starx.common.model.Announcement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

public class JdbcAnnouncementRepository {

  private static final String COLUMNS =
      "id, title, content, created_by, created_at, expires_at";

  private final JdbcStore store;

  public JdbcAnnouncementRepository(DataSource source) {
    this.store = new JdbcStore(source);
  }

  public void create(Announcement announcement) {
    Objects.requireNonNull(announcement, "announcement");
    this.store.execute(
        "INSERT INTO starx_announcements (id, title, content, created_by, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)",
        statement -> {
          statement.setString(1, announcement.id());
          statement.setString(2, announcement.title());
          statement.setString(3, announcement.content());
          statement.setString(4, announcement.createdBy());
          statement.setLong(5, announcement.createdAt());
          if (announcement.expiresAt() == null) {
            statement.setNull(6, Types.BIGINT);
          } else {
            statement.setLong(6, announcement.expiresAt());
          }
        });
  }

  public List<Announcement> findActive() {
    return this.store.many(
        "SELECT " + COLUMNS + " FROM starx_announcements WHERE expires_at IS NULL OR expires_at > ? ORDER BY created_at DESC",
        statement -> statement.setLong(1, System.currentTimeMillis()),
        this::map);
  }

  public Optional<Announcement> findById(String id) {
    return this.store.one(
        "SELECT " + COLUMNS + " FROM starx_announcements WHERE id = ?",
        statement -> statement.setString(1, id),
        this::map);
  }

  public List<Announcement> findUnreadByPlayer(UUID playerId) {
    return this.store.many(
        "SELECT a." + COLUMNS.replace(", ", ", a.")
            + " FROM starx_announcements a LEFT JOIN starx_announcement_reads r"
            + " ON a.id = r.announcement_id AND r.player_uuid = ?"
            + " WHERE (a.expires_at IS NULL OR a.expires_at > ?)"
            + " AND r.announcement_id IS NULL ORDER BY a.created_at DESC",
        statement -> {
          statement.setString(1, playerId.toString());
          statement.setLong(2, System.currentTimeMillis());
        },
        this::map);
  }

  public void markRead(String announcementId, UUID playerId) {
    boolean alreadyRead = this.store.one(
        "SELECT 1 FROM starx_announcement_reads WHERE announcement_id = ? AND player_uuid = ?",
        statement -> {
          statement.setString(1, announcementId);
          statement.setString(2, playerId.toString());
        },
        rows -> true).isPresent();
    if (alreadyRead) {
      return;
    }
    this.store.execute(
        "INSERT INTO starx_announcement_reads (announcement_id, player_uuid, read_at) VALUES (?, ?, ?)",
        statement -> {
          statement.setString(1, announcementId);
          statement.setString(2, playerId.toString());
          statement.setLong(3, System.currentTimeMillis());
        });
  }

  private Announcement map(ResultSet rows) throws SQLException {
    long expiresAt = rows.getLong("expires_at");
    return new Announcement(
        rows.getString("id"),
        rows.getString("title"),
        rows.getString("content"),
        rows.getString("created_by"),
        rows.getLong("created_at"),
        rows.wasNull() ? null : expiresAt);
  }
}
