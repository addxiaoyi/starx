package io.github.addxiaoyi.starx.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.common.crypto.PasswordHasher;
import io.github.addxiaoyi.starx.common.crypto.TotpGenerator;
import io.github.addxiaoyi.starx.common.database.JdbcTrustedDeviceRepository;
import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import io.github.addxiaoyi.starx.common.event.LocalEventBus;
import io.github.addxiaoyi.starx.common.model.StarxUser;
import java.net.InetAddress;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

final class AuthServicePersistentTrustedDeviceTest {
  @TempDir Path tempDir;

  @Test
  void successfulTotpTrustsTheDeviceForTheNextLogin() throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + this.tempDir.resolve("auth.db").toAbsolutePath());
    try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
      statement.execute("""
          CREATE TABLE starx_users (
            uuid VARCHAR(36) PRIMARY KEY, username VARCHAR(255), email VARCHAR(255),
            password_hash VARCHAR(255), totp_secret VARCHAR(255), premium BOOLEAN,
            created_at TIMESTAMP, last_login_at TIMESTAMP, external_user_id VARCHAR(255),
            trusted_devices TEXT, recovery_codes VARCHAR(512), source_system VARCHAR(50),
            migration_state VARCHAR(20), password_migrated_at TIMESTAMP, last_login_ip VARCHAR(255),
            last_login_isp VARCHAR(255), last_login_location VARCHAR(255), total_playtime BIGINT,
            last_logout_at TIMESTAMP, welcome_message_shown BOOLEAN)
          """);
      statement.execute(JdbcTrustedDeviceRepository.CREATE_TABLE_SQL);
    }

    UUID playerId = UUID.randomUUID();
    String password = "ValidPassword_123";
    String secret = TotpGenerator.generateSecret();
    JdbcUserRepository users = new JdbcUserRepository(source);
    users.create(new StarxUser(playerId, "Alex", null, PasswordHasher.hash(password), secret,
        false, Instant.now(), null, null, List.of(), null, "local", "completed", null,
        null, null, null, 0L, null, false));
    JdbcTrustedDeviceRepository devices = new JdbcTrustedDeviceRepository(source);
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthService auth = new AuthService(users, new LocalEventBus(), sessions, devices);
    InetAddress address = InetAddress.getByName("203.0.113.9");
    String deviceId = "net=203.0.113/24|proto=770|online=false|host=star-mc.top";
    try {
      AuthLease firstLease = AuthLease.create();
      auth.openConnection(firstLease, playerId, "Alex", address, deviceId);
      AuthResult passwordResult = auth.login(
          firstLease, playerId, "Alex", password, null, address, deviceId);
      assertEquals(AuthSession.State.AUTHENTICATING, passwordResult.state());
      assertTrue(auth.verifyTotp(
          firstLease, playerId, TotpGenerator.generate(secret, Instant.now())).success());

      auth.closeConnection(playerId, firstLease);
      AuthLease secondLease = AuthLease.create();
      auth.openConnection(secondLease, playerId, "Alex", address, deviceId);
      AuthResult trustedResult = auth.login(
          secondLease, playerId, "Alex", password, null, address, deviceId);

      assertEquals(AuthSession.State.AUTHENTICATED, trustedResult.state());
      assertEquals(1, devices.listActive(playerId, Instant.now()).size());

      auth.closeConnection(playerId, secondLease);
      AuthLease familiarRegionLease = AuthLease.create();
      auth.openConnection(familiarRegionLease, playerId, "Alex", address);
      AuthResult familiarRegionResult = auth.login(
          familiarRegionLease, playerId, "Alex", password, null, address, "new-device");
      assertEquals(AuthSession.State.AUTHENTICATED, familiarRegionResult.state());

      auth.closeConnection(playerId, familiarRegionLease);
      auth.bruteForceProtector().recordFailure(playerId);
      Thread.sleep(1_100L);
      AuthLease suspiciousLease = AuthLease.create();
      auth.openConnection(suspiciousLease, playerId, "Alex", address, "another-device");
      AuthResult suspiciousResult = auth.login(
          suspiciousLease, playerId, "Alex", password, null, address, "another-device");
      assertEquals(AuthSession.State.AUTHENTICATING, suspiciousResult.state());
      assertTrue(auth.verifyTotp(
          suspiciousLease, playerId, TotpGenerator.generate(secret, Instant.now())).success());
      assertEquals(0, auth.bruteForceProtector().getAttemptCount(playerId));

      auth.closeConnection(playerId, suspiciousLease);
      InetAddress newRegion = InetAddress.getByName("198.51.100.9");
      AuthLease riskyLease = AuthLease.create();
      auth.openConnection(riskyLease, playerId, "Alex", newRegion);
      AuthResult riskyResult = auth.login(
          riskyLease, playerId, "Alex", password, null, newRegion, "new-device");
      assertEquals(AuthSession.State.AUTHENTICATING, riskyResult.state());
    } finally {
      sessions.shutdown();
    }
  }
}
