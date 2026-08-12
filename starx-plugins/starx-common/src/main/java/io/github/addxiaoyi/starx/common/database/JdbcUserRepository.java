/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.common.database;

import io.github.addxiaoyi.starx.api.dto.UserDto;
import io.github.addxiaoyi.starx.api.repository.UserRepository;
import io.github.addxiaoyi.starx.common.model.StarxUser;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

public class JdbcUserRepository
implements UserRepository {
    private static final String SELECT_COLUMNS = "uuid, username, email, password_hash, totp_secret, premium, created_at, last_login_at, external_user_id, trusted_devices, COALESCE(recovery_codes, '') as recovery_codes, source_system, migration_state, password_migrated_at, last_login_ip, last_login_isp, last_login_location, total_playtime, last_logout_at, welcome_message_shown";
    private static final String SELECT_BY_UUID = "SELECT uuid, username, email, password_hash, totp_secret, premium, created_at, last_login_at, external_user_id, trusted_devices, COALESCE(recovery_codes, '') as recovery_codes, source_system, migration_state, password_migrated_at, last_login_ip, last_login_isp, last_login_location, total_playtime, last_logout_at, welcome_message_shown FROM starx_users WHERE uuid = ?";
    private static final String SELECT_BY_USERNAME = "SELECT uuid, username, email, password_hash, totp_secret, premium, created_at, last_login_at, external_user_id, trusted_devices, COALESCE(recovery_codes, '') as recovery_codes, source_system, migration_state, password_migrated_at, last_login_ip, last_login_isp, last_login_location, total_playtime, last_logout_at, welcome_message_shown FROM starx_users WHERE LOWER(username) = LOWER(?)";
    private static final String SELECT_BY_EMAIL = "SELECT uuid, username, email, password_hash, totp_secret, premium, created_at, last_login_at, external_user_id, trusted_devices, COALESCE(recovery_codes, '') as recovery_codes, source_system, migration_state, password_migrated_at, last_login_ip, last_login_isp, last_login_location, total_playtime, last_logout_at, welcome_message_shown FROM starx_users WHERE email = ?";
    private static final String SELECT_ALL = "SELECT uuid, username, email, password_hash, totp_secret, premium, created_at, last_login_at, external_user_id, trusted_devices, COALESCE(recovery_codes, '') as recovery_codes, source_system, migration_state, password_migrated_at, last_login_ip, last_login_isp, last_login_location, total_playtime, last_logout_at, welcome_message_shown FROM starx_users";
    private static final String DELETE_BY_UUID = "DELETE FROM starx_users WHERE uuid = ?";
    private static final Type TRUSTED_DEVICES_TYPE = new TypeToken<List<String>>(){}.getType();
    private final DataSource dataSource;
    private final Gson gson = new Gson();

    public JdbcUserRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Optional<UserDto> findByUuid(UUID uuid) {
        return this.findFullByUuid(uuid).map(this::toDto);
    }

    @Override
    public Optional<UserDto> findByUsername(String username) {
        return this.queryOne(SELECT_BY_USERNAME, stmt -> stmt.setString(1, username), rs -> this.toDto(this.mapUser(rs)));
    }

    @Override
    public Optional<UserDto> findByEmail(String email) {
        return this.queryOne(SELECT_BY_EMAIL, stmt -> stmt.setString(1, email), rs -> this.toDto(this.mapUser(rs)));
    }

    @Override
    public boolean existsByUsername(String username) {
        return this.queryOne("SELECT 1 FROM starx_users WHERE username = ?", stmt -> stmt.setString(1, username), rs -> 1).isPresent();
    }

    public List<UserDto> findAll() {
        return this.queryList(SELECT_ALL, stmt -> {}, rs -> this.toDto(this.mapUser(rs)));
    }

    @Override
    public void save(UserDto user) {
        this.withTransaction(conn -> {
            Optional<StarxUser> existing = this.findFullByUuid(conn, user.uuid());
            if (existing.isPresent()) {
                this.updateFromDto(conn, user, existing.get().passwordHash(), existing.get().totpSecret(), existing.get().trustedDevices());
            } else {
                this.insertFromDto(conn, user);
            }
        });
    }

    public void saveUser(StarxUser user) {
        this.withTransaction(conn -> {
            Optional<StarxUser> existing = this.findFullByUuid(conn, user.uuid());
            if (existing.isPresent()) {
                this.update(conn, user);
            } else {
                this.insert(conn, user);
            }
        });
    }

    @Override
    public void delete(UUID uuid) {
        this.execute(DELETE_BY_UUID, stmt -> stmt.setString(1, uuid.toString()));
    }

    public Optional<StarxUser> findFullByUuid(UUID uuid) {
        return this.queryOne(SELECT_BY_UUID, stmt -> stmt.setString(1, uuid.toString()), rs -> this.mapUser(rs));
    }

    public Optional<StarxUser> findFullByUsername(String username) {
        return this.queryOne(SELECT_BY_USERNAME, stmt -> stmt.setString(1, username), rs -> this.mapUser(rs));
    }

    @Override
    public boolean existsByUuid(UUID uuid) {
        return this.queryOne("SELECT 1 FROM starx_users WHERE uuid = ?", stmt -> stmt.setString(1, uuid.toString()), rs -> 1).isPresent();
    }

    public boolean existsByUsernameOrUuid(String username, UUID uuid) {
        return this.queryOne("SELECT 1 FROM starx_users WHERE username = ? OR uuid = ?", stmt -> {
            stmt.setString(1, username);
            stmt.setString(2, uuid.toString());
        }, rs -> 1).isPresent();
    }

    public Optional<String> findTotpSecretByUuid(UUID uuid) {
        return this.queryOne("SELECT totp_secret FROM starx_users WHERE uuid = ?", stmt -> stmt.setString(1, uuid.toString()), rs -> rs.getString("totp_secret"));
    }

    public Optional<String> findTrustedDevicesByUuid(UUID uuid) {
        return this.queryOne("SELECT trusted_devices FROM starx_users WHERE uuid = ?", stmt -> stmt.setString(1, uuid.toString()), rs -> rs.getString("trusted_devices"));
    }

    public Optional<String> findPasswordHashByUuid(UUID uuid) {
        return this.queryOne("SELECT password_hash FROM starx_users WHERE uuid = ?", stmt -> stmt.setString(1, uuid.toString()), rs -> rs.getString("password_hash"));
    }

    public void updatePasswordHash(UUID uuid, String passwordHash) {
        this.execute("UPDATE starx_users SET password_hash = ? WHERE uuid = ?", stmt -> {
            stmt.setString(1, passwordHash);
            stmt.setString(2, uuid.toString());
        });
    }

    public void updatePassword(UUID uuid, String passwordHash) {
        this.updatePasswordHash(uuid, passwordHash);
    }

    public void create(StarxUser user) {
        this.withTransaction(conn -> this.insert(conn, user));
    }

    public void updateTotpSecret(UUID uuid, String totpSecret) {
        this.execute("UPDATE starx_users SET totp_secret = ? WHERE uuid = ?", stmt -> {
            stmt.setString(1, totpSecret);
            stmt.setString(2, uuid.toString());
        });
    }

    public boolean disableTotp(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        return this.executeUpdate(
            "UPDATE starx_users SET totp_secret = NULL, recovery_codes = NULL "
                + "WHERE uuid = ? AND totp_secret IS NOT NULL",
            stmt -> stmt.setString(1, uuid.toString())) == 1;
    }

    public boolean enableTotp(UUID uuid, String totpSecret, String recoveryCodes) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(totpSecret, "totpSecret");
        Objects.requireNonNull(recoveryCodes, "recoveryCodes");
        return this.executeUpdate(
            "UPDATE starx_users SET totp_secret = ?, recovery_codes = ? "
                + "WHERE uuid = ? AND (totp_secret IS NULL OR totp_secret = '')",
            stmt -> {
                stmt.setString(1, totpSecret);
                stmt.setString(2, recoveryCodes);
                stmt.setString(3, uuid.toString());
            }) == 1;
    }

    public boolean tryUpdateEmail(UUID uuid, String email) {
        return this.executeUpdate(
            "UPDATE starx_users SET email = ? WHERE uuid = ? "
                + "AND NOT EXISTS (SELECT 1 FROM starx_users WHERE email = ? AND uuid <> ?)",
            stmt -> {
            stmt.setString(1, email);
            stmt.setString(2, uuid.toString());
            stmt.setString(3, email);
            stmt.setString(4, uuid.toString());
        }) == 1;
    }

    public void updateLastLogin(UUID uuid, Instant lastLogin) {
        this.execute("UPDATE starx_users SET last_login_at = ? WHERE uuid = ?", stmt -> {
            stmt.setTimestamp(1, Timestamp.from(lastLogin));
            stmt.setString(2, uuid.toString());
        });
    }

    public void updatePremium(UUID uuid, boolean premium) {
        this.execute("UPDATE starx_users SET premium = ? WHERE uuid = ?", stmt -> {
            stmt.setBoolean(1, premium);
            stmt.setString(2, uuid.toString());
        });
    }

    public void updateTrustedDevices(UUID uuid, List<String> trustedDevices) {
        this.execute("UPDATE starx_users SET trusted_devices = ? WHERE uuid = ?", stmt -> {
            stmt.setString(1, this.toJson(trustedDevices));
            stmt.setString(2, uuid.toString());
        });
    }

    public boolean replaceRecoveryCodes(UUID uuid, String expected, String replacement) {
        return this.executeUpdate(
            "UPDATE starx_users SET recovery_codes = ? WHERE uuid = ? AND recovery_codes = ?",
            stmt -> {
                stmt.setString(1, replacement);
                stmt.setString(2, uuid.toString());
                stmt.setString(3, expected);
            }) == 1;
    }

    public void updateMigrationState(UUID uuid, String migrationState) {
        this.execute("UPDATE starx_users SET migration_state = ? WHERE uuid = ?", stmt -> {
            stmt.setString(1, migrationState);
            stmt.setString(2, uuid.toString());
        });
    }

    public void updatePasswordMigratedAt(UUID uuid, Instant passwordMigratedAt) {
        this.execute("UPDATE starx_users SET password_migrated_at = ? WHERE uuid = ?", stmt -> {
            stmt.setTimestamp(1, passwordMigratedAt != null ? Timestamp.from(passwordMigratedAt) : null);
            stmt.setString(2, uuid.toString());
        });
    }

    public void updateLoginInfo(UUID uuid, String ip, String isp, String location) {
        this.execute("UPDATE starx_users SET last_login_ip = ?, last_login_isp = ?, last_login_location = ? WHERE uuid = ?", stmt -> {
            stmt.setString(1, ip);
            stmt.setString(2, isp);
            stmt.setString(3, location);
            stmt.setString(4, uuid.toString());
        });
    }

    public void updateTotalPlaytime(UUID uuid, long additionalPlaytime) {
        this.execute("UPDATE starx_users SET total_playtime = COALESCE(total_playtime, 0) + ? WHERE uuid = ?", stmt -> {
            stmt.setLong(1, additionalPlaytime);
            stmt.setString(2, uuid.toString());
        });
    }

    public void updateLastLogout(UUID uuid, Instant lastLogoutAt) {
        this.execute("UPDATE starx_users SET last_logout_at = ? WHERE uuid = ?", stmt -> {
            stmt.setTimestamp(1, lastLogoutAt != null ? Timestamp.from(lastLogoutAt) : null);
            stmt.setString(2, uuid.toString());
        });
    }

    public void markWelcomeMessageShown(UUID uuid) {
        this.execute("UPDATE starx_users SET welcome_message_shown = TRUE WHERE uuid = ?", stmt -> stmt.setString(1, uuid.toString()));
    }

    public void updateSourceSystem(UUID uuid, String sourceSystem) {
        this.execute("UPDATE starx_users SET source_system = ? WHERE uuid = ?", stmt -> {
            stmt.setString(1, sourceSystem);
            stmt.setString(2, uuid.toString());
        });
    }

    public void updateExternalIdentity(
        UUID uuid,
        String externalUserId,
        String sourceSystem
    ) {
        Objects.requireNonNull(uuid, "uuid");
        this.execute(
            "UPDATE starx_users SET external_user_id = ?, source_system = ? WHERE uuid = ?",
            stmt -> {
                stmt.setString(1, externalUserId);
                stmt.setString(2, sourceSystem);
                stmt.setString(3, uuid.toString());
            });
    }

    public void saveWebsiteBinding(UUID uuid, String username, String externalUserId, boolean verified) {
        this.execute("DELETE FROM starx_website_bindings WHERE player_uuid = ?", stmt -> stmt.setString(1, uuid.toString()));
        if (externalUserId == null || externalUserId.isBlank()) return;
        this.execute(
            "INSERT INTO starx_website_bindings (player_uuid, username, external_user_id, verified, updated_at) VALUES (?, ?, ?, ?, ?)",
            stmt -> {
                stmt.setString(1, uuid.toString());
                stmt.setString(2, username);
                stmt.setString(3, externalUserId);
                stmt.setBoolean(4, verified);
                stmt.setLong(5, System.currentTimeMillis());
            });
    }

    public boolean hasTrustedWebsiteBinding(UUID uuid, String username) {
        return this.queryOne(
            "SELECT 1 FROM starx_website_bindings WHERE player_uuid = ? AND LOWER(username) = LOWER(?) AND verified = TRUE",
            stmt -> { stmt.setString(1, uuid.toString()); stmt.setString(2, username); }, rs -> 1).isPresent();
    }

    public void markPasswordMigrated(UUID uuid, String passwordHash, Instant migratedAt) {
        int updated = this.executeUpdate("UPDATE starx_users SET password_hash = ?, password_migrated_at = ?, migration_state = ? WHERE uuid = ?", stmt -> {
            stmt.setString(1, passwordHash);
            stmt.setTimestamp(2, Timestamp.from(migratedAt));
            stmt.setString(3, "completed");
            stmt.setString(4, uuid.toString());
        });
        if (updated != 1) {
            throw new IllegalStateException("Password migration target account is missing: " + uuid);
        }
    }

    public int countByMigrationState(String migrationState) {
        return this.queryOne("SELECT COUNT(*) FROM starx_users WHERE migration_state = ?", stmt -> stmt.setString(1, migrationState), rs -> rs.getInt(1)).orElse(0);
    }

    public int countBySourceSystem(String sourceSystem) {
        return this.queryOne("SELECT COUNT(*) FROM starx_users WHERE source_system = ?", stmt -> stmt.setString(1, sourceSystem), rs -> rs.getInt(1)).orElse(0);
    }

    public int countBySourceSystemAndMigrationState(String sourceSystem, String migrationState) {
        return this.queryOne("SELECT COUNT(*) FROM starx_users WHERE source_system = ? AND migration_state = ?", stmt -> {
            stmt.setString(1, sourceSystem);
            stmt.setString(2, migrationState);
        }, rs -> rs.getInt(1)).orElse(0);
    }

    public int countAll() {
        return this.queryOne("SELECT COUNT(*) FROM starx_users", stmt -> {}, rs -> rs.getInt(1)).orElse(0);
    }

    public List<StarxUser> findBySourceSystem(String sourceSystem) {
        return this.queryList("SELECT uuid, username, email, password_hash, totp_secret, premium, created_at, last_login_at, external_user_id, trusted_devices, COALESCE(recovery_codes, '') as recovery_codes, source_system, migration_state, password_migrated_at, last_login_ip, last_login_isp, last_login_location, total_playtime, last_logout_at, welcome_message_shown FROM starx_users WHERE source_system = ?", stmt -> stmt.setString(1, sourceSystem), rs -> this.mapUser(rs));
    }

    public List<StarxUser> findByMigrationState(String migrationState) {
        return this.queryList("SELECT uuid, username, email, password_hash, totp_secret, premium, created_at, last_login_at, external_user_id, trusted_devices, COALESCE(recovery_codes, '') as recovery_codes, source_system, migration_state, password_migrated_at, last_login_ip, last_login_isp, last_login_location, total_playtime, last_logout_at, welcome_message_shown FROM starx_users WHERE migration_state = ?", stmt -> stmt.setString(1, migrationState), rs -> this.mapUser(rs));
    }

    private Optional<StarxUser> findFullByUuid(Connection conn, UUID uuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_BY_UUID);){
            Optional<StarxUser> optional;
            block12: {
                ps.setString(1, uuid.toString());
                ResultSet rs = ps.executeQuery();
                try {
                    Optional<StarxUser> optional2 = optional = rs.next() ? Optional.of(this.mapUser(rs)) : Optional.empty();
                    if (rs == null) break block12;
                }
                catch (Throwable throwable) {
                    if (rs != null) {
                        try {
                            rs.close();
                        }
                        catch (Throwable throwable2) {
                            throwable.addSuppressed(throwable2);
                        }
                    }
                    throw throwable;
                }
                rs.close();
            }
            return optional;
        }
    }

    private void insertFromDto(Connection conn, UserDto user) throws SQLException {
        Instant now = user.createdAt() != null ? user.createdAt() : Instant.now();
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO starx_users (uuid, username, email, password_hash, totp_secret, premium, created_at, last_login_at, external_user_id, trusted_devices) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");){
            ps.setString(1, user.uuid().toString());
            ps.setString(2, user.username());
            ps.setString(3, user.email());
            ps.setNull(4, 12);
            ps.setNull(5, 12);
            ps.setBoolean(6, user.premium());
            ps.setTimestamp(7, Timestamp.from(now));
            ps.setTimestamp(8, user.lastLoginAt() != null ? Timestamp.from(user.lastLoginAt()) : null);
            ps.setString(9, user.externalUserId());
            ps.setNull(10, 12);
            ps.executeUpdate();
        }
    }

    private void updateFromDto(Connection conn, UserDto user, String existingPasswordHash, String existingTotpSecret, List<String> existingTrustedDevices) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE starx_users SET username = ?, email = ?, password_hash = ?, totp_secret = ?, premium = ?, created_at = ?, last_login_at = ?, external_user_id = ?, trusted_devices = ? WHERE uuid = ?");){
            ps.setString(1, user.username());
            ps.setString(2, user.email());
            ps.setString(3, existingPasswordHash);
            ps.setString(4, existingTotpSecret);
            ps.setBoolean(5, user.premium());
            ps.setTimestamp(6, user.createdAt() != null ? Timestamp.from(user.createdAt()) : Timestamp.from(Instant.now()));
            ps.setTimestamp(7, user.lastLoginAt() != null ? Timestamp.from(user.lastLoginAt()) : null);
            ps.setString(8, user.externalUserId());
            ps.setString(9, this.toJson(existingTrustedDevices));
            ps.setString(10, user.uuid().toString());
            ps.executeUpdate();
        }
    }

    private void insert(Connection conn, StarxUser user) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO starx_users (uuid, username, email, password_hash, totp_secret, premium, created_at, last_login_at, external_user_id, trusted_devices, recovery_codes, source_system, migration_state, password_migrated_at, last_login_ip, last_login_isp, last_login_location, total_playtime, last_logout_at, welcome_message_shown) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");){
            ps.setString(1, user.uuid().toString());
            ps.setString(2, user.username());
            ps.setString(3, user.email());
            ps.setString(4, user.passwordHash());
            ps.setString(5, user.totpSecret());
            ps.setBoolean(6, user.premium());
            ps.setTimestamp(7, Timestamp.from(user.createdAt()));
            ps.setTimestamp(8, user.lastLoginAt() != null ? Timestamp.from(user.lastLoginAt()) : null);
            ps.setString(9, user.externalUserId());
            ps.setString(10, this.toJson(user.trustedDevices()));
            ps.setString(11, user.recoveryCodes());
            ps.setString(12, user.sourceSystem());
            ps.setString(13, user.migrationState());
            ps.setTimestamp(14, user.passwordMigratedAt() != null ? Timestamp.from(user.passwordMigratedAt()) : null);
            ps.setString(15, user.lastLoginIp());
            ps.setString(16, user.lastLoginIsp());
            ps.setString(17, user.lastLoginLocation());
            ps.setLong(18, user.totalPlaytime());
            ps.setTimestamp(19, user.lastLogoutAt() != null ? Timestamp.from(user.lastLogoutAt()) : null);
            ps.setBoolean(20, user.welcomeMessageShown());
            ps.executeUpdate();
        }
    }

    private void update(Connection conn, StarxUser user) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE starx_users SET username = ?, email = ?, password_hash = ?, totp_secret = ?, premium = ?, created_at = ?, last_login_at = ?, external_user_id = ?, trusted_devices = ?, recovery_codes = ?, source_system = ?, migration_state = ?, password_migrated_at = ?, last_login_ip = ?, last_login_isp = ?, last_login_location = ?, total_playtime = ?, last_logout_at = ?, welcome_message_shown = ? WHERE uuid = ?");){
            ps.setString(1, user.username());
            ps.setString(2, user.email());
            ps.setString(3, user.passwordHash());
            ps.setString(4, user.totpSecret());
            ps.setBoolean(5, user.premium());
            ps.setTimestamp(6, Timestamp.from(user.createdAt()));
            ps.setTimestamp(7, user.lastLoginAt() != null ? Timestamp.from(user.lastLoginAt()) : null);
            ps.setString(8, user.externalUserId());
            ps.setString(9, this.toJson(user.trustedDevices()));
            ps.setString(10, user.recoveryCodes());
            ps.setString(11, user.sourceSystem());
            ps.setString(12, user.migrationState());
            ps.setTimestamp(13, user.passwordMigratedAt() != null ? Timestamp.from(user.passwordMigratedAt()) : null);
            ps.setString(14, user.lastLoginIp());
            ps.setString(15, user.lastLoginIsp());
            ps.setString(16, user.lastLoginLocation());
            ps.setLong(17, user.totalPlaytime());
            ps.setTimestamp(18, user.lastLogoutAt() != null ? Timestamp.from(user.lastLogoutAt()) : null);
            ps.setBoolean(19, user.welcomeMessageShown());
            ps.setString(20, user.uuid().toString());
            ps.executeUpdate();
        }
    }


    private <T> Optional<T> queryOne(String sql, ParamBinder binder, RowMapper<T> mapper) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapper.map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Query failed: " + sql, e);
        }
    }

    private <T> List<T> queryList(String sql, ParamBinder binder, RowMapper<T> mapper) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> results = new java.util.ArrayList<>();
                while (rs.next()) {
                    results.add(mapper.map(rs));
                }
                return results;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Query failed: " + sql, e);
        }
    }


    private void execute(String sql, ParamBinder binder) {
        this.executeUpdate(sql, binder);
    }

    private int executeUpdate(String sql, ParamBinder binder) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Execute failed: " + sql, e);
        }
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

    private UserDto toDto(StarxUser user) {
        return UserDto.builder().uuid(user.uuid()).username(user.username()).email(user.email()).premium(user.premium()).createdAt(user.createdAt()).lastLoginAt(user.lastLoginAt()).externalUserId(user.externalUserId()).build();
    }

    private StarxUser mapUser(ResultSet rs) throws SQLException {
        Instant createdAt = readInstant(rs, "created_at");
        Instant lastLoginAt = readInstant(rs, "last_login_at");
        Instant passwordMigratedAt = readInstant(rs, "password_migrated_at");
        Instant lastLogoutAt = readInstant(rs, "last_logout_at");
        return new StarxUser(UUID.fromString(rs.getString("uuid")), rs.getString("username"), rs.getString("email"), rs.getString("password_hash"), rs.getString("totp_secret"), rs.getBoolean("premium"), createdAt, lastLoginAt, rs.getString("external_user_id"), this.parseTrustedDevices(rs.getString("trusted_devices")), rs.getString("recovery_codes"), rs.getString("source_system"), rs.getString("migration_state"), passwordMigratedAt, rs.getString("last_login_ip"), rs.getString("last_login_isp"), rs.getString("last_login_location"), rs.getLong("total_playtime"), lastLogoutAt, rs.getBoolean("welcome_message_shown"));
    }

    private static Instant readInstant(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return Timestamp.valueOf(localDateTime).toInstant();
        }
        if (value instanceof Number number) {
            return Instant.ofEpochMilli(number.longValue());
        }

        String text = value.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ignored) {
            // Legacy rows may use an offset rather than a trailing Z.
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException ignored) {
            // Continue with local and SQL timestamp formats.
        }
        try {
            return Timestamp.valueOf(LocalDateTime.parse(text)).toInstant();
        } catch (DateTimeParseException ignored) {
            // Continue with the JDBC SQL timestamp format.
        }
        try {
            return Timestamp.valueOf(text).toInstant();
        } catch (IllegalArgumentException ignored) {
            // Some SQLite migrations persisted epoch milliseconds as text.
        }
        try {
            return Instant.ofEpochMilli(Long.parseLong(text));
        } catch (NumberFormatException ignored) {
            throw new SQLException(
                "Unsupported timestamp format for column " + column + ": " + text);
        }
    }

    private String toJson(List<String> trustedDevices) {
        if (trustedDevices == null || trustedDevices.isEmpty()) {
            return null;
        }
        return this.gson.toJson(trustedDevices);
    }

    private List<String> parseTrustedDevices(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List parsed = (List)this.gson.fromJson(json, TRUSTED_DEVICES_TYPE);
        return parsed == null ? List.of() : List.copyOf(parsed);
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
        public void execute(Connection var1) throws SQLException;
    }
}
