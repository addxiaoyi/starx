package io.github.addxiaoyi.starx.common.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

final class JdbcTrustedDeviceRepositoryTest {
  @TempDir Path tempDir;

  private SQLiteDataSource source;
  private JdbcTrustedDeviceRepository devices;
  private final UUID playerId = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");

  @BeforeEach
  void setUp() throws Exception {
    this.source = new SQLiteDataSource();
    this.source.setUrl("jdbc:sqlite:" + this.tempDir.resolve("devices.db").toAbsolutePath());
    try (Connection connection = this.source.getConnection(); Statement statement = connection.createStatement()) {
      statement.execute(JdbcTrustedDeviceRepository.CREATE_TABLE_SQL);
    }
    this.devices = new JdbcTrustedDeviceRepository(this.source);
  }

  @Test
  void hashesFingerprintsNormalizesRegionsAndExpiresTrust() throws Exception {
    Instant now = Instant.parse("2026-07-22T00:00:00Z");
    this.devices.observe(playerId, "raw-device-fingerprint", " CN / Shanghai ", "Desktop",
        now.plus(Duration.ofDays(30)), now);

    assertTrue(this.devices.isTrusted(playerId, "raw-device-fingerprint", "cn/shanghai", now));
    assertFalse(this.devices.isTrusted(playerId, "raw-device-fingerprint", "cn/beijing", now));
    assertFalse(this.devices.isTrusted(playerId, "raw-device-fingerprint", "cn/shanghai",
        now.plus(Duration.ofDays(31))));
    assertEquals("cn/shanghai", this.devices.listActive(playerId, now).getFirst().regionKey());

    try (Connection connection = this.source.getConnection();
         Statement statement = connection.createStatement();
         ResultSet rows = statement.executeQuery("SELECT fingerprint_hash FROM starx_trusted_devices")) {
      assertTrue(rows.next());
      String stored = rows.getString(1);
      assertFalse(stored.contains("raw-device-fingerprint"));
      assertEquals(64, stored.length());
    }
  }

  @Test
  void capsEachPlayerAtTenDevicesAndSupportsScopedRevocation() {
    Instant now = Instant.parse("2026-07-22T00:00:00Z");
    for (int index = 0; index < 12; index++) {
      this.devices.observe(playerId, "device-" + index, "cn/shanghai", "Device " + index,
          now.plus(Duration.ofDays(30)), now.plusSeconds(index));
    }

    var active = this.devices.listActive(playerId, now.plusSeconds(20));
    assertEquals(10, active.size());
    assertFalse(this.devices.isTrusted(playerId, "device-0", "cn/shanghai", now.plusSeconds(20)));

    UUID keepId = active.getFirst().id();
    this.devices.revokeAllExcept(playerId, keepId, now.plusSeconds(21));
    assertEquals(1, this.devices.listActive(playerId, now.plusSeconds(22)).size());
    assertTrue(this.devices.revoke(playerId, keepId, now.plusSeconds(23)));
    assertTrue(this.devices.listActive(playerId, now.plusSeconds(24)).isEmpty());
  }

  @Test
  void recognizesARegionOnlyFromAnActiveNonRevokedDevice() {
    Instant now = Instant.parse("2026-07-22T00:00:00Z");
    var shanghai = this.devices.observe(
        playerId, "desktop", " CN / Shanghai ", "Desktop",
        now.plus(Duration.ofDays(30)), now);

    assertTrue(this.devices.hasFamiliarRegion(playerId, "cn/shanghai", now.plusSeconds(1)));
    assertFalse(this.devices.hasFamiliarRegion(playerId, "cn/beijing", now.plusSeconds(1)));

    assertTrue(this.devices.revoke(playerId, shanghai.id(), now.plusSeconds(2)));
    assertFalse(this.devices.hasFamiliarRegion(playerId, "cn/shanghai", now.plusSeconds(3)));
  }
}
