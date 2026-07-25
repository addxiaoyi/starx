/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.common.config;

public record DatabaseConfig(String type, String host, int port, String database, String username, String password, String url, int poolMaxSize, long connectionTimeoutMs) {
    public DatabaseConfig {
        type = type == null || type.isBlank() ? "sqlite" : type;
        host = host == null ? "" : host;
        port = port <= 0 ? 3306 : port;
        database = database == null || database.isBlank() ? "plugins/starx/data.db" : database;
        username = username == null ? "starx" : username;
        password = password == null ? "" : password;
        url = url == null ? "" : url;
        poolMaxSize = poolMaxSize <= 0 ? 2 : poolMaxSize;
        connectionTimeoutMs = connectionTimeoutMs <= 0L ? 30000L : connectionTimeoutMs;
    }

    public static DatabaseConfig defaults() {
        return new DatabaseConfig("sqlite", "", 3306, "plugins/starx/data.db", "starx", "", "", 2, 30000L);
    }

    public boolean hasUrl() {
        return !this.url.isBlank();
    }

    public boolean isSqlite() {
        return "sqlite".equalsIgnoreCase(this.type);
    }

    public String jdbcUrl() {
        if (this.hasUrl()) {
            return this.url;
        }
        return switch (this.type.toLowerCase()) {
            case "h2" -> "jdbc:h2:mem:" + this.database;
            case "mysql" -> "jdbc:mysql://" + this.host + ":" + this.port + "/" + this.database + "?useSSL=false&serverTimezone=UTC";
            case "postgresql" -> "jdbc:postgresql://" + this.host + ":" + this.port + "/" + this.database;
            case "sqlite" -> "jdbc:sqlite:" + this.database;
            default -> throw new IllegalArgumentException("Unsupported database type: " + this.type);
        };
    }
}
