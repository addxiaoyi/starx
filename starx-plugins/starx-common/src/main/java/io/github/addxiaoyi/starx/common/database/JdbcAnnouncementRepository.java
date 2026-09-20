package io.github.addxiaoyi.starx.common.database;

import io.github.addxiaoyi.starx.common.model.Announcement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Collection;
import java.util.LinkedHashSet;
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
    return findUnreadByPlayer(List.of(playerId));
  }

  public List<Announcement> findUnreadByPlayer(Collection<UUID> playerIds) {
    List<UUID> ids = JdbcUuidQuery.distinct(playerIds);
    if (ids.isEmpty()) return List.of();
    return this.store.many(
        "SELECT a." + COLUMNS.replace(", ", ", a.")
            + " FROM starx_announcements a LEFT JOIN starx_announcement_reads r"
            + " ON a.id = r.announcement_id AND r.player_uuid IN ("
            + JdbcUuidQuery.placeholders(ids.size()) + ")"
            + " WHERE (a.expires_at IS NULL OR a.expires_at > ?)"
            + " AND r.announcement_id IS NULL ORDER BY a.created_at DESC",
        statement -> {
          JdbcUuidQuery.bind(statement, ids);
          statement.setLong(ids.size() + 1, System.currentTimeMillis());
        },
        this::map);
  }

  public void markRead(String announcementId, UUID playerId) {
    markRead(announcementId, playerId, List.of(playerId));
  }

  public void markRead(String announcementId, UUID playerId, Collection<UUID> knownPlayerIds) {
    Objects.requireNonNull(playerId, "playerId");
    LinkedHashSet<UUID> allPlayerIds = new LinkedHashSet<>();
    allPlayerIds.add(playerId);
    if (knownPlayerIds != null) allPlayerIds.addAll(knownPlayerIds);
    List<UUID> ids = JdbcUuidQuery.distinct(allPlayerIds);
    if (ids.isEmpty()) return;
    boolean alreadyRead = this.store.one(
        "SELECT 1 FROM starx_announcement_reads WHERE announcement_id = ? AND player_uuid IN ("
            + JdbcUuidQuery.placeholders(ids.size()) + ")",
        statement -> {
          statement.setString(1, announcementId);
          int index = 2;
          for (UUID id : ids) statement.setString(index++, id.toString());
        },
        rows -> true).isPresent();
    if (alreadyRead) {
      return;
    }
    try {
      this.store.execute(
          "INSERT INTO starx_announcement_reads (announcement_id, player_uuid, read_at) VALUES (?, ?, ?)",
          statement -> {
            statement.setString(1, announcementId);
            statement.setString(2, playerId.toString());
            statement.setLong(3, System.currentTimeMillis());
          });
    } catch (RuntimeException error) {
      if (!isUniqueViolation(error)) throw error;
    }
  }

  private static boolean isUniqueViolation(Throwable error) {
    Throwable current = error;
    while (current != null) {
      if (current instanceof java.sql.SQLIntegrityConstraintViolationException) return true;
      if (current instanceof SQLException sql
          && ("23".equals(sql.getSQLState()) || "23505".equals(sql.getSQLState()))) {
        return true;
      }
      String message = current.getMessage();
      if (message != null) {
        String normalized = message.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("unique constraint") || normalized.contains("duplicate key")) {
          return true;
        }
      }
      current = current.getCause();
    }
    return false;
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
