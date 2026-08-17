package io.github.addxiaoyi.starx.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import io.github.addxiaoyi.starx.common.event.LocalEventBus;
import io.github.addxiaoyi.starx.common.model.StarxUser;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AuthServiceDeleteUserContractTest {
  @Test
  void erasureFailureReturnsUserFacingFailureAndKeepsSession() {
    UUID accountUuid = UUID.randomUUID();
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthLease lease = AuthLease.create();
    try {
      assertTrue(sessions.open(accountUuid, "DeleteMe", null, "device", lease) != null);
      AuthService auth = service(sessions, accountUuid);
      auth.bindAccountErasure(ignored -> { throw new IllegalStateException("database unavailable"); });

      AuthResult result = auth.deleteUser("DeleteMe");

      assertFalse(result.success());
      assertEquals("用户删除失败，请稍后重试", result.message());
      assertTrue(sessions.get(accountUuid, lease).isPresent());
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void successfulErasureRemovesAllMatchingSessions() {
    UUID accountUuid = UUID.randomUUID();
    UUID secondSession = UUID.randomUUID();
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AuthLease firstLease = AuthLease.create();
    AuthLease secondLease = AuthLease.create();
    AtomicReference<UUID> erased = new AtomicReference<>();
    try {
      assertTrue(sessions.open(accountUuid, "DeleteMe", null, "device-a", firstLease) != null);
      assertTrue(sessions.open(secondSession, "DeleteMe", null, "device-b", secondLease) != null);
      AuthService auth = service(sessions, accountUuid);
      auth.bindAccountErasure(erased::set);

      AuthResult result = auth.deleteUser("DeleteMe");

      assertTrue(result.success());
      assertEquals(accountUuid, erased.get());
      assertTrue(sessions.get(accountUuid, firstLease).isEmpty());
      assertTrue(sessions.get(secondSession, secondLease).isEmpty());
    } finally {
      sessions.shutdown();
    }
  }

  @Test
  void sessionIdentityResolutionFailureDoesNotEraseTheAccount() {
    UUID accountUuid = UUID.randomUUID();
    SessionManager sessions = new SessionManager(Duration.ofMinutes(5), Instant::now);
    AtomicBoolean erased = new AtomicBoolean();
    try {
      AuthService auth = service(sessions, accountUuid);
      auth.bindMinecraftIdentityResolver(ignored -> {
        throw new IllegalStateException("identity lookup unavailable");
      });
      auth.bindAccountErasure(ignored -> erased.set(true));

      AuthResult result = auth.deleteUser("DeleteMe");

      assertFalse(result.success());
      assertEquals("用户删除失败，请稍后重试", result.message());
      assertFalse(erased.get());
    } finally {
      sessions.shutdown();
    }
  }

  private static AuthService service(SessionManager sessions, UUID accountUuid) {
    JdbcUserRepository users = new JdbcUserRepository(null);
    AuthService auth = new AuthService(users, new LocalEventBus(), sessions);
    StarxUser user = new StarxUser(
        accountUuid, "DeleteMe", null, null, null, false, Instant.now(), null, null,
        java.util.List.of(), null, null, null, null, null, null, null, 0L, null, false);
    auth.bindUsernameResolver(ignored -> Optional.of(user));
    return auth;
  }
}
