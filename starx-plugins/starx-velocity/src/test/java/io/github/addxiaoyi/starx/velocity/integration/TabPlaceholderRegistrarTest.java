package io.github.addxiaoyi.starx.velocity.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.velocity.variable.StarxVariableService;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

final class TabPlaceholderRegistrarTest {

  @Test
  void registersAndUnregistersEveryBuiltInVariableAgainstTabApiShape() {
    UUID playerId = UUID.randomUUID();
    FakePlaceholderManager manager = new FakePlaceholderManager();
    StarxVariableService variables = new StarxVariableService(ZoneId.of("Asia/Shanghai"));
    TabPlaceholderRegistrar registrar = new TabPlaceholderRegistrar(
        manager,
        variables,
        id -> id.equals(playerId)
            ? Optional.of(StarxVariableService.PlayerContext.guest("Alex", 3))
            : Optional.empty(),
        1_000);

    registrar.registerAll();

    assertEquals(variables.keys().size(), manager.placeholders.size());
    assertEquals(
        "待登录",
        manager.placeholders.get("%starx_auth_status%").apply(new FakeTabPlayer(playerId)));

    registrar.unregisterAll();
    assertTrue(manager.placeholders.isEmpty());
  }

  public static final class FakePlaceholderManager {
    private final Map<String, Function<Object, String>> placeholders = new HashMap<>();

    public void registerPlayerPlaceholder(
        String identifier,
        int refreshMillis,
        Function<Object, String> resolver) {
      this.placeholders.put(identifier, resolver);
    }

    public void unregisterPlaceholder(String identifier) {
      this.placeholders.remove(identifier);
    }
  }

  public static final class FakeTabPlayer {
    private final UUID uniqueId;

    private FakeTabPlayer(UUID uniqueId) {
      this.uniqueId = uniqueId;
    }

    public UUID getUniqueId() {
      return this.uniqueId;
    }
  }
}
