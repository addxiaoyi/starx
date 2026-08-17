/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.common.database;

import io.github.addxiaoyi.starx.common.config.DatabaseConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.addxiaoyi.starx.common.identity.JdbcAccountIdentityRepository;
import io.github.addxiaoyi.starx.common.binding.JdbcBindingChallengeRepository;
import io.github.addxiaoyi.starx.common.account.JdbcAccountDeletionRepository;
import io.github.addxiaoyi.starx.common.session.JdbcPlayerSessionRepository;

public final class DatabaseManager
implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(DatabaseManager.class);
    private final HikariDataSource dataSource;

    public DatabaseManager(DatabaseConfig config) {
        DatabaseManager.loadJdbcDrivers();
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.jdbcUrl());
        hikariConfig.setUsername(config.username());
        hikariConfig.setPassword(config.password());
        hikariConfig.setConnectionTimeout(config.connectionTimeoutMs());
        boolean isH2 = config.jdbcUrl().startsWith("jdbc:h2:");
        boolean isSqlite = config.isSqlite();
        if (isH2) {
            hikariConfig.setMaximumPoolSize(Math.min(config.poolMaxSize(), 3));
            hikariConfig.setMinimumIdle(1);
        } else if (isSqlite) {
            hikariConfig.setMaximumPoolSize(Math.min(config.poolMaxSize(), 2));
            hikariConfig.setMinimumIdle(1);
            hikariConfig.setConnectionInitSql("PRAGMA busy_timeout = 5000");
        } else {
            hikariConfig.setMaximumPoolSize(config.poolMaxSize());
            hikariConfig.setMinimumIdle(2);
        }
        hikariConfig.setPoolName("starx-common-pool");
        this.dataSource = new HikariDataSource(hikariConfig);
        if (isSqlite) {
            this.configureSqlite();
        }
        this.ensureTables();
    }

    private void ensureTables() {
        try (Connection conn = this.dataSource.getConnection();
             Statement stmt = conn.createStatement();){
            stmt.execute("CREATE TABLE IF NOT EXISTS starx_schema_migrations (version VARCHAR(96) PRIMARY KEY, applied_at BIGINT NOT NULL)");
            stmt.execute("CREATE TABLE IF NOT EXISTS starx_users (uuid VARCHAR(36) PRIMARY KEY, username VARCHAR(255) NOT NULL, email VARCHAR(255), password_hash VARCHAR(255), totp_secret VARCHAR(255), premium BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMP NOT NULL, last_login_at TIMESTAMP, external_user_id VARCHAR(255), trusted_devices TEXT, recovery_codes VARCHAR(512) DEFAULT NULL, source_system VARCHAR(50), migration_state VARCHAR(20), password_migrated_at TIMESTAMP, last_login_ip VARCHAR(255), last_login_isp VARCHAR(255), last_login_location VARCHAR(255), total_playtime BIGINT DEFAULT 0, last_logout_at TIMESTAMP, welcome_message_shown BOOLEAN DEFAULT FALSE)");
            stmt.execute("CREATE TABLE IF NOT EXISTS starx_website_bindings (player_uuid VARCHAR(36) PRIMARY KEY, username VARCHAR(16) NOT NULL, external_user_id VARCHAR(100) NOT NULL, verified BOOLEAN NOT NULL DEFAULT FALSE, updated_at BIGINT NOT NULL)");
            stmt.execute("UPDATE starx_users SET external_user_id = NULL WHERE TRIM(COALESCE(external_user_id, '')) = ''");
            stmt.execute("DELETE FROM starx_website_bindings WHERE TRIM(COALESCE(external_user_id, '')) = ''");
            stmt.execute("UPDATE starx_website_bindings SET external_user_id = TRIM(external_user_id) WHERE external_user_id IS NOT NULL");
            stmt.execute(JdbcAccountIdentityRepository.CREATE_ACCOUNTS_SQL);
            stmt.execute(JdbcAccountIdentityRepository.CREATE_IDENTITIES_SQL);
            stmt.execute(JdbcPlayerSessionRepository.CREATE_SESSIONS_SQL);
            stmt.execute(JdbcPlayerSessionRepository.CREATE_SEGMENTS_SQL);
            stmt.execute(JdbcBindingChallengeRepository.CREATE_TABLE_SQL);
            JdbcSchema.addColumnIfMissing(
                conn, "starx_binding_challenges", "payload", "VARCHAR(512)");
            JdbcSchema.addColumnIfMissing(
                conn, "starx_binding_challenges", "execution_owner", "VARCHAR(36)");
            JdbcSchema.addColumnIfMissing(
                conn, "starx_binding_challenges", "execution_lease_until", "BIGINT");
            stmt.execute(JdbcAccountDeletionRepository.CREATE_TABLE_SQL);
            JdbcSchema.addColumnIfMissing(
                conn, "starx_account_deletions", "claim_token", "VARCHAR(36)");
            JdbcSchema.addColumnIfMissing(
                conn, "starx_account_deletions", "completed_at", "BIGINT");
            stmt.execute(JdbcRuntimeSettingRepository.CREATE_TABLE_SQL);
            stmt.execute(JdbcTutorialProgressRepository.CREATE_TABLE_SQL);
            JdbcSchema.createIndex(conn, "starx_binding_challenges", "idx_starx_binding_challenges_account", false, "account_id, state");
            try (java.sql.PreparedStatement migration = conn.prepareStatement(
                    "INSERT INTO starx_schema_migrations (version, applied_at) VALUES (?, ?)")) {
                migration.setString(1, "2026-07-p0-identity-session-binding");
                migration.setLong(2, System.currentTimeMillis());
                try {
                    migration.executeUpdate();
                } catch (java.sql.SQLException error) {
                    if (!JdbcSchema.isDuplicateConstraint(error)) throw error;
                }
                migration.setString(1, "2026-07-p1-account-erasure-executor");
                migration.setLong(2, System.currentTimeMillis());
                try {
                    migration.executeUpdate();
                } catch (java.sql.SQLException error) {
                    if (!JdbcSchema.isDuplicateConstraint(error)) throw error;
                }
            }
            JdbcSchema.createIndex(conn, "starx_users", "idx_starx_users_username", true, "username");
            JdbcSchema.createIndex(conn, "starx_users", "idx_starx_users_username_ci", true, "LOWER(username)");
            JdbcSchema.createIndex(conn, "starx_users", "idx_starx_users_email", true, "email");
            JdbcSchema.createIndex(conn, "starx_users", "idx_starx_users_email_ci", true, "LOWER(email)");
            stmt.execute("CREATE TABLE IF NOT EXISTS starx_punishments (id VARCHAR(36) PRIMARY KEY, target_uuid VARCHAR(36) NOT NULL, target_name VARCHAR(16) NOT NULL, type VARCHAR(16) NOT NULL, reason VARCHAR(512), staff_uuid VARCHAR(36) NOT NULL, staff_name VARCHAR(16) NOT NULL, created_at BIGINT NOT NULL, expires_at BIGINT, active BOOLEAN NOT NULL DEFAULT TRUE)");
            stmt.execute("CREATE TABLE IF NOT EXISTS starx_staff_notes (id VARCHAR(36) PRIMARY KEY, target_uuid VARCHAR(36) NOT NULL, note VARCHAR(1024) NOT NULL, severity VARCHAR(16) NOT NULL, staff_uuid VARCHAR(36) NOT NULL, created_at BIGINT NOT NULL)");
            stmt.execute("CREATE TABLE IF NOT EXISTS starx_reports (id VARCHAR(36) PRIMARY KEY, reporter_uuid VARCHAR(36) NOT NULL, target_uuid VARCHAR(36) NOT NULL, category VARCHAR(32) NOT NULL, details VARCHAR(512), status VARCHAR(16) NOT NULL DEFAULT 'PENDING', resolved_by VARCHAR(36), resolved_at BIGINT)");
            stmt.execute("CREATE TABLE IF NOT EXISTS starx_announcements (id VARCHAR(36) PRIMARY KEY, title VARCHAR(128) NOT NULL, content VARCHAR(2048) NOT NULL, created_by VARCHAR(36) NOT NULL, created_at BIGINT NOT NULL, expires_at BIGINT)");
            stmt.execute("CREATE TABLE IF NOT EXISTS starx_announcement_reads (announcement_id VARCHAR(36) NOT NULL, player_uuid VARCHAR(36) NOT NULL, read_at BIGINT NOT NULL, PRIMARY KEY (announcement_id, player_uuid))");
            stmt.execute("CREATE TABLE IF NOT EXISTS starx_player_bindings (player_uuid VARCHAR(36) PRIMARY KEY, qq_id VARCHAR(64), discord_id VARCHAR(64), created_at BIGINT NOT NULL)");
            BindingUniquenessGuard.verify(conn);
            JdbcSchema.createIndex(conn, "starx_player_bindings", "idx_starx_bindings_qq", true, "qq_id");
            JdbcSchema.createIndex(conn, "starx_player_bindings", "idx_starx_bindings_discord", true, "discord_id");
            JdbcSchema.createIndex(conn, "starx_users", "idx_starx_users_external_user_id", true, "external_user_id");
            JdbcSchema.createIndex(conn, "starx_website_bindings", "idx_starx_website_bindings_external_user_id", true, "external_user_id");
            stmt.execute(JdbcBindingRepository.CREATE_AUDIT_TABLE_SQL);
            stmt.execute("CREATE TABLE IF NOT EXISTS starx_staff_votes (id VARCHAR(36) PRIMARY KEY, target_uuid VARCHAR(36) NOT NULL, target_name VARCHAR(16) NOT NULL, reason VARCHAR(512), vote_type VARCHAR(32) NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', initiator_uuid VARCHAR(36) NOT NULL, initiator_name VARCHAR(16) NOT NULL, yes_votes INT DEFAULT 0, no_votes INT DEFAULT 0, required_yes INT DEFAULT 3, expires_at BIGINT NOT NULL, created_at BIGINT NOT NULL, resolved_at BIGINT)");
            stmt.execute("CREATE TABLE IF NOT EXISTS starx_staff_vote_records (vote_id VARCHAR(36) NOT NULL, voter_uuid VARCHAR(36) NOT NULL, vote VARCHAR(4) NOT NULL, voted_at BIGINT NOT NULL, PRIMARY KEY (vote_id, voter_uuid))");
            stmt.execute("CREATE TABLE IF NOT EXISTS starx_ip_sessions (player_uuid VARCHAR(36) NOT NULL, ip_address VARCHAR(45) NOT NULL, isp VARCHAR(255), location VARCHAR(255), login_time BIGINT NOT NULL, source VARCHAR(16) NOT NULL, device_fingerprint VARCHAR(512), PRIMARY KEY (player_uuid, ip_address))");
            JdbcSchema.addColumnIfMissing(
                conn, "starx_ip_sessions", "device_fingerprint", "VARCHAR(512)");
            JdbcSchema.createIndex(conn, "starx_ip_sessions", "idx_starx_ip_sessions_player", false, "player_uuid");
            stmt.execute(JdbcTrustedDeviceRepository.CREATE_TABLE_SQL);
            JdbcSchema.createIndex(conn, "starx_trusted_devices", "idx_starx_trusted_devices_player", false, "player_uuid");
            LOG.info("Database tables verified/created");
        }
        catch (Exception e) {
            LOG.error("Failed to ensure database tables", e);
            throw new RuntimeException("Failed to initialize database tables", e);
        }
    }

    private static void loadJdbcDrivers() {
        for (String driver : new String[]{"org.sqlite.JDBC", "com.mysql.cj.jdbc.Driver", "org.postgresql.Driver"}) {
            try {
                Class.forName(driver);
            }
            catch (ClassNotFoundException ignored) {
                LOG.debug("JDBC driver is not installed: {}", driver);
            }
        }
    }

    private void configureSqlite() {
        try (Connection conn = this.dataSource.getConnection();
             Statement stmt = conn.createStatement();){
            stmt.execute("PRAGMA journal_mode = WAL");
            stmt.execute("PRAGMA synchronous = NORMAL");
            stmt.execute("PRAGMA foreign_keys = ON");
            stmt.execute("PRAGMA busy_timeout = 5000");
        }
        catch (Exception e) {
            throw new RuntimeException("Failed to configure SQLite", e);
        }
    }

    public DataSource getDataSource() {
        return this.dataSource;
    }

    @Override
    public void close() {
        this.dataSource.close();
    }
}
