package io.github.addxiaoyi.starx.common.database;

import io.github.addxiaoyi.starx.common.model.TrustedDevice;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

public final class JdbcTrustedDeviceRepository {
  public static final String CREATE_TABLE_SQL = """
      CREATE TABLE IF NOT EXISTS starx_trusted_devices (
        id VARCHAR(36) PRIMARY KEY,
        player_uuid VARCHAR(36) NOT NULL,
        fingerprint_hash VARCHAR(64) NOT NULL,
        region_key VARCHAR(128) NOT NULL,
        label VARCHAR(128) NOT NULL,
        first_seen_at BIGINT NOT NULL,
        last_seen_at BIGINT NOT NULL,
        expires_at BIGINT NOT NULL,
        revoked_at BIGINT,
        UNIQUE(player_uuid, fingerprint_hash)
      )
      """;
  private static final int MAX_DEVICES_PER_PLAYER = 10;
  private static final int MAX_OBSERVE_ATTEMPTS = 6;
  private static final long RETRY_BASE_DELAY_MILLIS = 10L;

  private final DataSource source;

  public JdbcTrustedDeviceRepository(DataSource source) {
    this.source = Objects.requireNonNull(source, "source");
  }

  public TrustedDevice observe(
      UUID playerId,
      String rawFingerprint,
      String regionKey,
      String label,
      Instant expiresAt,
      Instant now
  ) {
    Objects.requireNonNull(playerId, "playerId");
    Objects.requireNonNull(expiresAt, "expiresAt");
    Objects.requireNonNull(now, "now");
    if (!expiresAt.isAfter(now)) throw new IllegalArgumentException("expiresAt must be in the future");
    String fingerprintHash = hash(playerId, rawFingerprint);
    String normalizedRegion = normalizeRegion(regionKey);
    String normalizedLabel = normalizeLabel(label);

    for (int attempt = 1; attempt <= MAX_OBSERVE_ATTEMPTS; attempt++) {
      try {
        return observeOnce(
            playerId, fingerprintHash, normalizedRegion, normalizedLabel, expiresAt, now);
      } catch (IllegalStateException error) {
        if (!isBusy(error) || attempt == MAX_OBSERVE_ATTEMPTS) throw error;
        try {
          Thread.sleep(RETRY_BASE_DELAY_MILLIS << (attempt - 1));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("Interrupted while observing trusted device", interrupted);
        }
      }
    }
    throw new IllegalStateException("Failed to observe trusted device");
  }

  private TrustedDevice observeOnce(
      UUID playerId,
      String fingerprintHash,
      String normalizedRegion,
      String normalizedLabel,
      Instant expiresAt,
      Instant now) {
    try (Connection connection = this.source.getConnection()) {
      boolean autoCommit = connection.getAutoCommit();
      boolean sqlite = isSqlite(connection);
      boolean sqliteManualTransaction = sqlite && autoCommit;
      if (!sqliteManualTransaction) connection.setAutoCommit(false);
      try {
        if (sqliteManualTransaction) beginImmediate(connection);
        TrustedDevice existing = findByHash(connection, playerId, fingerprintHash);
        TrustedDevice saved;
        if (existing == null) {
          try {
            saved = insert(
                connection, playerId, fingerprintHash, normalizedRegion, normalizedLabel,
                expiresAt, now);
          } catch (SQLException error) {
            if (!isUniqueViolation(error)) throw error;
            existing = findByHash(connection, playerId, fingerprintHash);
            if (existing == null) throw error;
            saved = update(connection, existing, normalizedRegion, normalizedLabel, expiresAt, now);
          }
        } else {
          saved = update(connection, existing, normalizedRegion, normalizedLabel, expiresAt, now);
        }
        capActiveDevices(connection, playerId, now);
        if (sqliteManualTransaction) {
          executeTransactionControl(connection, "COMMIT");
        } else {
          connection.commit();
          connection.setAutoCommit(autoCommit);
        }
        return saved;
      } catch (Exception error) {
        try {
          if (sqliteManualTransaction) {
            executeTransactionControl(connection, "ROLLBACK");
          } else {
            connection.rollback();
          }
        } catch (SQLException rollbackError) {
          error.addSuppressed(rollbackError);
        }
        throw error;
      }
    } catch (SQLException error) {
      throw new IllegalStateException("Failed to observe trusted device", error);
    }
  }

  private static void beginImmediate(Connection connection) throws SQLException {
    executeTransactionControl(connection, "BEGIN IMMEDIATE");
  }

  private static void executeTransactionControl(Connection connection, String sql)
      throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static boolean isSqlite(Connection connection) throws SQLException {
    String url = connection.getMetaData().getURL();
    return url != null && url.toLowerCase(Locale.ROOT).startsWith("jdbc:sqlite:");
  }

  public boolean isTrusted(UUID playerId, String rawFingerprint, String regionKey, Instant now) {
    return isTrustedForUuid(playerId, rawFingerprint, regionKey, now);
  }

  public boolean isTrusted(Collection<UUID> playerIds, String rawFingerprint, String regionKey, Instant now) {
    List<UUID> ids = JdbcUuidQuery.distinct(playerIds);
    for (UUID playerId : ids) {
      if (isTrustedForUuid(playerId, rawFingerprint, regionKey, now)) return true;
    }
    return false;
  }

  private boolean isTrustedForUuid(UUID playerId, String rawFingerprint, String regionKey, Instant now) {
    String sql = """
        SELECT 1 FROM starx_trusted_devices
        WHERE player_uuid = ? AND fingerprint_hash = ? AND region_key = ?
          AND revoked_at IS NULL AND expires_at > ?
        """;
    try (Connection connection = this.source.getConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, playerId.toString());
      statement.setString(2, hash(playerId, rawFingerprint));
      statement.setString(3, normalizeRegion(regionKey));
      statement.setLong(4, now.toEpochMilli());
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
      }
    } catch (SQLException error) {
      throw new IllegalStateException("Failed to inspect trusted device", error);
    }
  }

  public boolean hasFamiliarRegion(UUID playerId, String regionKey, Instant now) {
    return hasFamiliarRegion(List.of(playerId), regionKey, now);
  }

  public boolean hasFamiliarRegion(Collection<UUID> playerIds, String regionKey, Instant now) {
    List<UUID> ids = JdbcUuidQuery.distinct(playerIds);
    if (ids.isEmpty()) return false;
    Objects.requireNonNull(now, "now");
    String sql = "SELECT 1 FROM starx_trusted_devices WHERE player_uuid IN ("
        + JdbcUuidQuery.placeholders(ids.size()) + ") AND region_key = ?"
        + " AND revoked_at IS NULL AND expires_at > ? LIMIT 1";
    try (Connection connection = this.source.getConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      JdbcUuidQuery.bind(statement, ids);
      statement.setString(ids.size() + 1, normalizeRegion(regionKey));
      statement.setLong(ids.size() + 2, now.toEpochMilli());
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
      }
    } catch (SQLException error) {
      throw new IllegalStateException("Failed to inspect familiar region", error);
    }
  }

  public List<TrustedDevice> listActive(UUID playerId, Instant now) {
    return listActive(List.of(playerId), now);
  }

  public List<TrustedDevice> listActive(Collection<UUID> playerIds, Instant now) {
    List<UUID> ids = JdbcUuidQuery.distinct(playerIds);
    if (ids.isEmpty()) return List.of();
    String sql = "SELECT * FROM starx_trusted_devices WHERE player_uuid IN ("
        + JdbcUuidQuery.placeholders(ids.size()) + ") AND revoked_at IS NULL AND expires_at > ?"
        + " ORDER BY last_seen_at DESC, id DESC";
    try (Connection connection = this.source.getConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      JdbcUuidQuery.bind(statement, ids);
      statement.setLong(ids.size() + 1, now.toEpochMilli());
      try (ResultSet rows = statement.executeQuery()) {
        List<TrustedDevice> devices = new ArrayList<>();
        while (rows.next()) devices.add(map(rows));
        return List.copyOf(devices);
      }
    } catch (SQLException error) {
      throw new IllegalStateException("Failed to list trusted devices", error);
    }
  }

  public boolean revoke(UUID playerId, UUID deviceId, Instant now) {
    String sql = """
        UPDATE starx_trusted_devices SET revoked_at = ?
        WHERE id = ? AND player_uuid = ? AND revoked_at IS NULL
        """;
    return updateCount(sql, statement -> {
      statement.setLong(1, now.toEpochMilli());
      statement.setString(2, deviceId.toString());
      statement.setString(3, playerId.toString());
    }) == 1;
  }

  public int revokeAllExcept(UUID playerId, UUID keepId, Instant now) {
    String sql = """
        UPDATE starx_trusted_devices SET revoked_at = ?
        WHERE player_uuid = ? AND id <> ? AND revoked_at IS NULL
        """;
    return updateCount(sql, statement -> {
      statement.setLong(1, now.toEpochMilli());
      statement.setString(2, playerId.toString());
      statement.setString(3, keepId.toString());
    });
  }

  public int revokeAll(UUID playerId, Instant now) {
    return revokeAll(List.of(playerId), now);
  }

  public int revokeAll(Collection<UUID> playerIds, Instant now) {
    List<UUID> ids = JdbcUuidQuery.distinct(playerIds);
    if (ids.isEmpty()) return 0;
    return updateCount(
        "UPDATE starx_trusted_devices SET revoked_at = ? "
            + "WHERE player_uuid IN (" + JdbcUuidQuery.placeholders(ids.size()) + ") AND revoked_at IS NULL",
        statement -> {
          statement.setLong(1, now.toEpochMilli());
          int index = 2;
          for (UUID id : ids) statement.setString(index++, id.toString());
        });
  }

  private TrustedDevice insert(
      Connection connection, UUID playerId, String hash, String region, String label,
      Instant expiresAt, Instant now) throws SQLException {
    UUID id = UUID.randomUUID();
    String sql = """
        INSERT INTO starx_trusted_devices
          (id, player_uuid, fingerprint_hash, region_key, label, first_seen_at, last_seen_at, expires_at, revoked_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL)
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, id.toString());
      statement.setString(2, playerId.toString());
      statement.setString(3, hash);
      statement.setString(4, region);
      statement.setString(5, label);
      statement.setLong(6, now.toEpochMilli());
      statement.setLong(7, now.toEpochMilli());
      statement.setLong(8, expiresAt.toEpochMilli());
      statement.executeUpdate();
    }
    return new TrustedDevice(id, playerId, hash, region, label, now, now, expiresAt, null);
  }

  private TrustedDevice update(
      Connection connection, TrustedDevice existing, String region, String label,
      Instant expiresAt, Instant now) throws SQLException {
    String sql = """
        UPDATE starx_trusted_devices
        SET region_key = ?, label = ?, last_seen_at = ?, expires_at = ?, revoked_at = NULL
        WHERE id = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, region);
      statement.setString(2, label);
      statement.setLong(3, now.toEpochMilli());
      statement.setLong(4, expiresAt.toEpochMilli());
      statement.setString(5, existing.id().toString());
      statement.executeUpdate();
    }
    return new TrustedDevice(existing.id(), existing.playerId(), existing.fingerprintHash(), region,
        label, existing.firstSeenAt(), now, expiresAt, null);
  }

  private static TrustedDevice findByHash(Connection connection, UUID playerId, String hash)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT * FROM starx_trusted_devices WHERE player_uuid = ? AND fingerprint_hash = ?")) {
      statement.setString(1, playerId.toString());
      statement.setString(2, hash);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? map(rows) : null;
      }
    }
  }

  private static void capActiveDevices(Connection connection, UUID playerId, Instant now)
      throws SQLException {
    List<UUID> activeIds = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT id FROM starx_trusted_devices
        WHERE player_uuid = ? AND revoked_at IS NULL AND expires_at > ?
        ORDER BY last_seen_at DESC, id DESC
        """)) {
      statement.setString(1, playerId.toString());
      statement.setLong(2, now.toEpochMilli());
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) activeIds.add(UUID.fromString(rows.getString(1)));
      }
    }
    if (activeIds.size() <= MAX_DEVICES_PER_PLAYER) return;
    try (PreparedStatement statement = connection.prepareStatement(
        "UPDATE starx_trusted_devices SET revoked_at = ? WHERE id = ?")) {
      for (int index = MAX_DEVICES_PER_PLAYER; index < activeIds.size(); index++) {
        statement.setLong(1, now.toEpochMilli());
        statement.setString(2, activeIds.get(index).toString());
        statement.addBatch();
      }
      statement.executeBatch();
    }
  }

  private int updateCount(String sql, SqlBinder binder) {
    try (Connection connection = this.source.getConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      binder.bind(statement);
      return statement.executeUpdate();
    } catch (SQLException error) {
      throw new IllegalStateException("Failed to update trusted device", error);
    }
  }

  static boolean isUniqueViolation(Throwable error) {
    Throwable current = error;
    while (current != null) {
      if (current instanceof java.sql.SQLIntegrityConstraintViolationException) return true;
      if (current instanceof SQLException sql) {
        String state = sql.getSQLState();
        if (state != null && state.startsWith("23")) return true;
      }
      String message = current.getMessage();
      if (message != null) {
        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.contains("unique constraint") || normalized.contains("duplicate key")) {
          return true;
        }
      }
      current = current.getCause();
    }
    return false;
  }

  private static boolean isBusy(Throwable error) {
    Throwable current = error;
    while (current != null) {
      String message = current.getMessage();
      if (message != null) {
        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.contains("database is locked") || normalized.contains("database is busy")) {
          return true;
        }
      }
      current = current.getCause();
    }
    return false;
  }

  private static TrustedDevice map(ResultSet rows) throws SQLException {
    long revokedAt = rows.getLong("revoked_at");
    return new TrustedDevice(
        UUID.fromString(rows.getString("id")),
        UUID.fromString(rows.getString("player_uuid")),
        rows.getString("fingerprint_hash"),
        rows.getString("region_key"),
        rows.getString("label"),
        Instant.ofEpochMilli(rows.getLong("first_seen_at")),
        Instant.ofEpochMilli(rows.getLong("last_seen_at")),
        Instant.ofEpochMilli(rows.getLong("expires_at")),
        rows.wasNull() ? null : Instant.ofEpochMilli(revokedAt));
  }

  static String normalizeRegion(String raw) {
    if (raw == null || raw.isBlank()) return "unknown";
    String[] parts = raw.trim().toLowerCase(Locale.ROOT).split("/");
    List<String> normalized = new ArrayList<>();
    for (String part : parts) {
      String value = part.trim().replaceAll("\\s+", "-");
      if (!value.isBlank()) normalized.add(value);
    }
    return normalized.isEmpty() ? "unknown" : String.join("/", normalized);
  }

  private static String normalizeLabel(String raw) {
    String label = raw == null ? "" : raw.trim();
    if (label.isBlank()) return "Minecraft client";
    return label.length() <= 128 ? label : label.substring(0, 128);
  }

  private static String hash(UUID playerId, String rawFingerprint) {
    if (rawFingerprint == null || rawFingerprint.isBlank()) {
      throw new IllegalArgumentException("rawFingerprint is required");
    }
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(
          (playerId + ":" + rawFingerprint.trim()).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }

  @FunctionalInterface
  private interface SqlBinder {
    void bind(PreparedStatement statement) throws SQLException;
  }
}
