package io.github.addxiaoyi.starx.velocity.module.auth;

import io.github.addxiaoyi.starx.api.event.EventBus;
import io.github.addxiaoyi.starx.common.auth.uniauth.UniAuthClient;
import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import io.github.addxiaoyi.starx.common.model.StarxUser;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.Locale;

/**
 * Migration module for importing users from other auth systems.
 */
public final class MigrationModule implements VelocityModule {
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private final StarxVelocityPlugin plugin;
    private final EventBus eventBus;
    private final Config config;
    private final JdbcUserRepository userRepository;
    private final UniAuthClient uniAuthClient;

    public MigrationModule(StarxVelocityPlugin plugin, EventBus eventBus, Config config,
                         JdbcUserRepository userRepository, UniAuthClient uniAuthClient) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.config = Objects.requireNonNull(config, "config");
        this.userRepository = userRepository;
        this.uniAuthClient = uniAuthClient;
    }

    public MigrationModule(StarxVelocityPlugin plugin, EventBus eventBus, Config config) {
        this(plugin, eventBus, config, null, null);
    }

    @Override
    public String name() {
        return "starx.auth.migration";
    }

    @Override
    public void onEnable() {
        plugin.logger().log(Level.INFO, "MigrationModule enabled, source: {0}", config.source());
    }

    @Override
    public void onDisable() {
        // An active import owns the guard until its finally block completes.
    }

    public static boolean isRunning() {
        return RUNNING.get();
    }

    /**
     * Import metadata from StarVC.
     */
    public MigrationResult importStarVCMeta(boolean dryRun) {
        if (!RUNNING.compareAndSet(false, true)) {
            throw new IllegalStateException("Migration is already running.");
        }
        if (userRepository == null) {
            RUNNING.set(false);
            throw new IllegalStateException("userRepository is not available");
        }

        long start = System.currentTimeMillis();
        int total = 0;
        int imported = 0;
        int skippedExisting = 0;
        int skippedInvalid = 0;
        int errors = 0;

        try (Connection sourceConn = getSourceConnection()) {
            String schemaMode = normalizeSchemaMode(config.schemaMode());
            String tablePrefix = config.tablePrefix();
            plugin.logger().log(Level.INFO,
                "Starting StarVC metadata import (schema={0}, prefix={1}, dryRun={2})",
                new Object[]{schemaMode, tablePrefix, dryRun});

            String query = buildStarVCQuery(schemaMode, tablePrefix);
            plugin.logger().log(Level.FINE, "Executing query: {0}", query);

            try (PreparedStatement st = sourceConn.prepareStatement(query);
                 ResultSet rs = st.executeQuery()) {

                while (rs.next()) {
                    total++;
                    try {
                        StarVCUserEntry entry = parseStarVCUserEntry(rs, schemaMode);
                        if (entry.username() == null || entry.username().isBlank()) {
                            skippedInvalid++;
                            continue;
                        }

                        UUID uuid = entry.uuid();
                        if (userRepository.existsByUuid(uuid) || userRepository.existsByUsername(entry.username())) {
                            skippedExisting++;
                            continue;
                        }

                        if (!dryRun) {
                            StarxUser user = new StarxUser(
                                uuid, entry.username(), entry.email(), null, null,
                                entry.premium(), Instant.now(), null, null,
                                List.of(), null, "starvc", "pending", null, null,
                                null, null, 0L, null, false);
                            userRepository.create(user);
                        }
                        imported++;

                    } catch (Exception e) {
                        errors++;
                        plugin.logger().log(Level.WARNING, "Import user #{0} failed: {1}",
                            new Object[]{total, e.getMessage()});
                    }
                }
            }

            if (total == 0) {
                plugin.logger().log(Level.WARNING, "No user data found");
            }

            plugin.logger().log(Level.INFO,
                "StarVC import complete: total={0}, imported={1}, skippedExisting={2}, skippedInvalid={3}, errors={4}",
                new Object[]{total, imported, skippedExisting, skippedInvalid, errors});

        } catch (Exception e) {
            plugin.logger().log(Level.SEVERE, "StarVC import failed: " + e.getMessage(), e);
            errors++;
        } finally {
            RUNNING.set(false);
        }

        long duration = System.currentTimeMillis() - start;
        return new MigrationResult(total, imported, skippedExisting, skippedInvalid, errors, duration, dryRun);
    }

    static String buildStarVCQuery(String schemaMode, String tablePrefix) {
        String mode = normalizeSchemaMode(schemaMode);
        String prefix = validateTablePrefix(tablePrefix);
        String tableName = prefix + switch (mode) {
            case "authme" -> "authme";
            case "authlib" -> "users";
            case "luckperms" -> "luckperms_players";
            case "starx.starvc" -> "starvc_users";
            default -> throw new IllegalArgumentException("Unsupported migration schema mode: " + schemaMode);
        };

        return switch (mode) {
            case "authme" -> String.format(
                "SELECT realname AS username, uuid, email, is_premium AS premium FROM %s", tableName);
            case "authlib" -> String.format(
                "SELECT username, uuid, email, premium FROM %s", tableName);
            case "luckperms" -> String.format(
                "SELECT username, uuid FROM %s", tableName);
            case "starx.starvc" -> String.format(
                "SELECT uuid, username, email, premium FROM %s", tableName);
            default -> throw new IllegalArgumentException("Unsupported migration schema mode: " + schemaMode);
        };
    }

    static String normalizeSchemaMode(String schemaMode) {
        if (schemaMode == null || schemaMode.isBlank()) {
            throw new IllegalArgumentException("Migration schema mode must not be blank");
        }
        return schemaMode.trim().toLowerCase(Locale.ROOT);
    }

    private static String validateTablePrefix(String tablePrefix) {
        if (tablePrefix == null || tablePrefix.isEmpty()) {
            return "";
        }
        if (!tablePrefix.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Migration table prefix must be a simple SQL identifier prefix");
        }
        return tablePrefix;
    }

    private StarVCUserEntry parseStarVCUserEntry(ResultSet rs, String schemaMode) throws Exception {
        String uuidStr;
        String username;
        String email = null;
        boolean premium = false;

        switch (schemaMode.toLowerCase()) {
            case "authme" -> {
                username = rs.getString("username");
                uuidStr = rs.getString("uuid");
                email = rs.getString("email");
                premium = rs.getBoolean("premium");
            }
            case "authlib" -> {
                username = rs.getString("username");
                uuidStr = rs.getString("uuid");
                email = rs.getString("email");
                premium = rs.getBoolean("premium");
            }
            case "luckperms" -> {
                username = rs.getString("username");
                uuidStr = rs.getString("uuid");
            }
            default -> {
                uuidStr = rs.getString("uuid");
                username = rs.getString("username");
                email = rs.getString("email");
                premium = rs.getBoolean("premium");
            }
        }

        UUID uuid;
        try {
            uuid = UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            // Try to convert from 32-char format
            if (uuidStr != null && uuidStr.length() == 32) {
                uuid = UUID.fromString(
                    uuidStr.substring(0, 8) + "-" +
                    uuidStr.substring(8, 12) + "-" +
                    uuidStr.substring(12, 16) + "-" +
                    uuidStr.substring(16, 20) + "-" +
                    uuidStr.substring(20));
            } else {
                throw e;
            }
        }

        return new StarVCUserEntry(uuidStr, uuid, username, email, premium);
    }

    private Connection getSourceConnection() throws Exception {
        String backend = config.backend().toLowerCase();
        Map<String, Object> conn = config.connection();

        return switch (backend) {
            case "mysql" -> {
                String host = (String) conn.getOrDefault("host", "localhost");
                int port = (Integer) conn.getOrDefault("port", 3306);
                String database = (String) conn.getOrDefault("database", "multilogin");
                String username = (String) conn.getOrDefault("username", "root");
                String password = (String) conn.getOrDefault("password", "");
                String jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + database;
                yield DriverManager.getConnection(jdbcUrl, username, password);
            }
            case "sqlite" -> {
                String path = (String) conn.getOrDefault("path", "");
                String jdbcUrl = "jdbc:sqlite:" + path;
                yield DriverManager.getConnection(jdbcUrl);
            }
            case "h2" -> {
                String path = (String) conn.getOrDefault("path", "");
                String username = (String) conn.getOrDefault("username", "sa");
                String password = (String) conn.getOrDefault("password", "");
                String jdbcUrl = "jdbc:h2:" + path;
                yield DriverManager.getConnection(jdbcUrl, username, password);
            }
            default -> throw new IllegalArgumentException("Unsupported backend: " + backend);
        };
    }

    public interface Config {
        boolean enabled();
        String source();
        String backend();
        Map<String, Object> connection();

        default String tablePrefix() { return ""; }
        default String schemaMode() { return "starx.starvc"; }

        static Config defaultConfig() {
            return new Config() {
                @Override public boolean enabled() { return false; }
                @Override public String source() { return "starx.starvc"; }
                @Override public String backend() { return "starx.sqlite"; }
                @Override public Map<String, Object> connection() {
                    return Map.of("path", "plugins/StarVC/starvc.db");
                }
            };
        }
    }

    public record MigrationResult(
        int total, int imported, int skippedExisting,
        int skippedInvalid, int errors, long durationMs, boolean dryRun) {}

    private record StarVCUserEntry(
        String uuidStr, UUID uuid, String username, String email, boolean premium) {}
}
