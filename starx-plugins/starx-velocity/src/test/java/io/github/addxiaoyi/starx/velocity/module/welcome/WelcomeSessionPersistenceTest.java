package io.github.addxiaoyi.starx.velocity.module.welcome;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WelcomeSessionPersistenceTest {
  @Test
  void subSecondSessionStillUpdatesLastLogout() {
    TrackingUsers users = new TrackingUsers();
    WelcomeModule module = new WelcomeModule(null, users);
    UUID player = UUID.randomUUID();
    Instant login = Instant.parse("2026-07-23T10:00:00Z");
    Instant logout = login.plusMillis(500);

    module.recordSession(player, login, logout);

    assertEquals(0, users.playtime);
    assertEquals(logout, users.lastLogout);
  }

  @Test
  void completeSecondsAreAddedToPlaytime() {
    TrackingUsers users = new TrackingUsers();
    WelcomeModule module = new WelcomeModule(null, users);
    UUID player = UUID.randomUUID();
    Instant login = Instant.parse("2026-07-23T10:00:00Z");
    Instant logout = login.plusMillis(2_900);

    module.recordSession(player, login, logout);

    assertEquals(2, users.playtime);
    assertEquals(logout, users.lastLogout);
  }

  private static final class TrackingUsers extends JdbcUserRepository {
    private long playtime;
    private Instant lastLogout;

    private TrackingUsers() {
      super(null);
    }

    @Override
    public void updateTotalPlaytime(UUID uuid, long seconds) {
      playtime += seconds;
    }

    @Override
    public void updateLastLogout(UUID uuid, Instant logout) {
      lastLogout = logout;
    }
  }
}
