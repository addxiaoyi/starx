/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.common.database;

import io.github.addxiaoyi.starx.common.model.PlayerBinding;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

public class JdbcBindingRepository {
    public static final String CREATE_AUDIT_TABLE_SQL = "CREATE TABLE IF NOT EXISTS starx_binding_audit (audit_id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL, binding_kind VARCHAR(16) NOT NULL, action VARCHAR(16) NOT NULL, actor VARCHAR(128) NOT NULL, occurred_at BIGINT NOT NULL)";
    private final DataSource dataSource;

    public JdbcBindingRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Optional<PlayerBinding> findByPlayer(UUID playerUuid) {
        return this.queryOne("SELECT player_uuid, qq_id, discord_id, created_at FROM starx_player_bindings WHERE player_uuid = ?", ps -> ps.setString(1, playerUuid.toString()), this::map);
    }

    public Optional<PlayerBinding> findByQq(String qqId) {
        return this.queryOne("SELECT player_uuid, qq_id, discord_id, created_at FROM starx_player_bindings WHERE qq_id = ?", ps -> ps.setString(1, qqId), this::map);
    }

    public Optional<PlayerBinding> findByDiscord(String discordId) {
        return this.queryOne("SELECT player_uuid, qq_id, discord_id, created_at FROM starx_player_bindings WHERE discord_id = ?", ps -> ps.setString(1, discordId), this::map);
    }

    public boolean save(PlayerBinding binding) {
        final boolean[] saved = {false};
        try {
          this.withTransaction(conn -> {
            if (ownedByAnother(conn, "qq_id", binding.qqId(), binding.playerUuid())
                || ownedByAnother(conn, "discord_id", binding.discordId(), binding.playerUuid())) {
              return;
            }
            block25: {
                try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM starx_player_bindings WHERE player_uuid = ?");){
                    ps.setString(1, binding.playerUuid().toString());
                    try (ResultSet rs = ps.executeQuery();){
                        if (rs.next()) {
                            try (PreparedStatement update = conn.prepareStatement(
                                    "UPDATE starx_player_bindings SET qq_id = COALESCE(?, qq_id), "
                                        + "discord_id = COALESCE(?, discord_id) WHERE player_uuid = ?")) {
                                update.setString(1, binding.qqId());
                                update.setString(2, binding.discordId());
                                update.setString(3, binding.playerUuid().toString());
                                update.executeUpdate();
                                saved[0] = true;
                                break block25;
                            }
                        }
                        try (PreparedStatement insert = conn.prepareStatement("INSERT INTO starx_player_bindings (player_uuid, qq_id, discord_id, created_at) VALUES (?, ?, ?, ?)");){
                            insert.setString(1, binding.playerUuid().toString());
                            insert.setString(2, binding.qqId());
                            insert.setString(3, binding.discordId());
                            insert.setLong(4, binding.createdAt());
                            insert.executeUpdate();
                            saved[0] = true;
                        }
                    }
                }
            }
          });
          return saved[0];
        } catch (RuntimeException error) {
          if (isConstraintViolation(error)) return false;
          throw error;
        }
    }

    private static boolean ownedByAnother(
            Connection connection, String column, String value, UUID playerUuid) throws SQLException {
        if (value == null || value.isBlank()) return false;
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT player_uuid FROM starx_player_bindings WHERE " + column + " = ?")) {
            query.setString(1, value);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() && !playerUuid.toString().equals(rows.getString(1));
            }
        }
    }

    private static boolean isConstraintViolation(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof java.sql.SQLIntegrityConstraintViolationException) return true;
            if (current instanceof SQLException sql && "23".equals(sql.getSQLState())) return true;
            String message = current.getMessage();
            if (message != null && message.toLowerCase(java.util.Locale.ROOT).contains("unique constraint")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public boolean unbind(UUID playerUuid, String kind, String actor, long occurredAt) {
        String normalized = kind == null ? "" : kind.trim().toUpperCase(java.util.Locale.ROOT);
        if (!normalized.equals("QQ") && !normalized.equals("DISCORD")) {
            throw new IllegalArgumentException("kind must be QQ or DISCORD");
        }
        if (actor == null || actor.isBlank()) throw new IllegalArgumentException("actor is required");
        final boolean[] changed = {false};
        this.withTransaction(conn -> {
            String column = normalized.equals("QQ") ? "qq_id" : "discord_id";
            try (PreparedStatement update = conn.prepareStatement(
                    "UPDATE starx_player_bindings SET " + column + " = NULL WHERE player_uuid = ? AND " + column + " IS NOT NULL")) {
                update.setString(1, playerUuid.toString());
                changed[0] = update.executeUpdate() == 1;
            }
            if (!changed[0]) return;
            try (PreparedStatement audit = conn.prepareStatement(
                    "INSERT INTO starx_binding_audit (audit_id, player_uuid, binding_kind, action, actor, occurred_at) VALUES (?, ?, ?, 'UNBIND', ?, ?)")) {
                audit.setString(1, UUID.randomUUID().toString());
                audit.setString(2, playerUuid.toString());
                audit.setString(3, normalized);
                audit.setString(4, actor.trim());
                audit.setLong(5, occurredAt);
                audit.executeUpdate();
            }
        });
        return changed[0];
    }

    public int auditCount(UUID playerUuid, String kind) {
        return this.queryOne(
                "SELECT COUNT(*) AS total FROM starx_binding_audit WHERE player_uuid = ? AND binding_kind = ?",
                ps -> { ps.setString(1, playerUuid.toString()); ps.setString(2, kind.toUpperCase(java.util.Locale.ROOT)); },
                rs -> rs.getInt("total")).orElse(0);
    }

    private void withTransaction(TransactionBody body) {
        try (Connection conn = this.dataSource.getConnection();){
            conn.setAutoCommit(false);
            try {
                body.execute(conn);
                conn.commit();
            }
            catch (Exception e) {
                conn.rollback();
                throw new RuntimeException("Transaction failed", e);
            }
        }
        catch (SQLException e) {
            throw new RuntimeException("Transaction failed", e);
        }
    }

    private PlayerBinding map(ResultSet rs) throws SQLException {
        return new PlayerBinding(UUID.fromString(rs.getString("player_uuid")), rs.getString("qq_id"), rs.getString("discord_id"), rs.getLong("created_at"));
    }

    private <T> Optional<T> queryOne(String sql, ParamBinder binder, RowMapper<T> mapper) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.ofNullable(mapper.map(rows)) : Optional.empty();
            }
        } catch (SQLException error) {
            throw new RuntimeException("Query failed: " + sql, error);
        }
    }

    private void execute(String sql, ParamBinder binder) {
        try (Connection conn = this.dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);){
            binder.bind(ps);
            ps.executeUpdate();
        }
        catch (SQLException e) {
            throw new RuntimeException("Execute failed: " + sql, e);
        }
    }

    @FunctionalInterface
    private static interface ParamBinder {
        public void bind(PreparedStatement var1) throws SQLException;
    }

    @FunctionalInterface
    private static interface RowMapper<T> {
        public T map(ResultSet var1) throws SQLException;
    }

    @FunctionalInterface
    private static interface TransactionBody {
        public void execute(Connection var1) throws Exception;
    }
}
