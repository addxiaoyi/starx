package io.github.addxiaoyi.starx.common.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.common.model.IpSession;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class JdbcIdentityAliasRepositoryTest {

  @Test
  void ipSessionsCanBeReadAndDeletedThroughKnownUuidAliases(@TempDir Path tempDir) throws Exception {
    SQLiteDataSource source = source(tempDir, "ip.db");
    UUID legacy = UUID.randomUUID();
    UUID current = UUID.randomUUID();
    JdbcIpSessionRepository repository = new JdbcIpSessionRepository(source);
    repository.save(new IpSession(legacy, "192.0.2.1", null, null,
        System.currentTimeMillis(), "local", "device"));

    assertTrue(repository.findByUuidAndIp(Set.of(current, legacy), "192.0.2.1").isPresent());
    assertEquals(1, repository.findRecentSessions(Set.of(current, legacy), 1).size());
    repository.deleteByUuid(Set.of(current, legacy));
    assertTrue(repository.findByUuidAndIp(legacy, "192.0.2.1").isEmpty());
  }

  @Test
  void trustedDevicesUseTheSameAliasSet(@TempDir Path tempDir) throws Exception {
    SQLiteDataSource source = source(tempDir, "trusted.db");
    UUID legacy = UUID.randomUUID();
    UUID current = UUID.randomUUID();
    Instant now = Instant.parse("2026-08-15T00:00:00Z");
    JdbcTrustedDeviceRepository repository = new JdbcTrustedDeviceRepository(source);
    repository.observe(legacy, "device-fingerprint", "CN/Shanghai", "Laptop",
        now.plusSeconds(3600), now);

    assertTrue(repository.isTrusted(Set.of(current, legacy), "device-fingerprint",
        "cn/shanghai", now));
    assertTrue(repository.hasFamiliarRegion(Set.of(current, legacy), "cn/shanghai", now));
    assertEquals(1, repository.listActive(Set.of(current, legacy), now).size());
    assertEquals(1, repository.revokeAll(Set.of(current, legacy), now));
  }

  private static SQLiteDataSource source(Path tempDir, String name) throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve(name).toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("CREATE TABLE starx_ip_sessions (player_uuid TEXT NOT NULL, ip_address TEXT NOT NULL, isp TEXT, location TEXT, login_time BIGINT NOT NULL, source TEXT NOT NULL, device_fingerprint TEXT, PRIMARY KEY (player_uuid, ip_address))");
      sql.execute(JdbcTrustedDeviceRepository.CREATE_TABLE_SQL);
    }
    return source;
  }
}
