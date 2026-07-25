/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.velocity.database;

import io.github.addxiaoyi.starx.common.config.DatabaseConfig;
import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import java.util.Objects;

public final class DatabaseManager {
    private final io.github.addxiaoyi.starx.common.database.DatabaseManager commonManager;
    private final JdbcUserRepository userRepository;
    private final io.github.addxiaoyi.starx.common.database.JdbcIpSessionRepository ipSessionRepository;
    private boolean closed;

    public DatabaseManager(DatabaseConfig config) {
        Objects.requireNonNull(config, "databaseConfig");
        this.commonManager = new io.github.addxiaoyi.starx.common.database.DatabaseManager(config);
        this.userRepository = new JdbcUserRepository(this.commonManager.getDataSource());
        this.ipSessionRepository = new io.github.addxiaoyi.starx.common.database.JdbcIpSessionRepository(this.commonManager.getDataSource());
        this.closed = false;
    }

    public io.github.addxiaoyi.starx.common.database.DatabaseManager commonManager() {
        return this.commonManager;
    }

    public JdbcUserRepository getUserRepository() {
        return this.userRepository;
    }

    public io.github.addxiaoyi.starx.common.database.JdbcIpSessionRepository getIpSessionRepository() {
        return this.ipSessionRepository;
    }

    public boolean isOpen() {
        return !this.closed;
    }

    public void close() {
        if (!this.closed) {
            this.closed = true;
            this.commonManager.close();
        }
    }
}
