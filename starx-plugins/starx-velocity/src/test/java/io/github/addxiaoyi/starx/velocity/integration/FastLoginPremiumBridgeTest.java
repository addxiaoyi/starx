package io.github.addxiaoyi.starx.velocity.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.proxy.Player;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

final class FastLoginPremiumBridgeTest {

  private static final InetSocketAddress ADDRESS = new InetSocketAddress("127.0.0.1", 25565);
  private static final UUID PREMIUM_UUID = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

  @Test
  void reportsUnavailableWhenFastLoginIsMissing() {
    FastLoginPremiumBridge bridge = new FastLoginPremiumBridge(
        new FakeProxy(new FakePluginManager(Optional.empty())),
        Logger.getLogger("test"));

    assertFalse(bridge.isAvailable());
    assertFalse(bridge.isVerified(player("Notch", PREMIUM_UUID)));
  }

  @Test
  void reportsAvailableOnceFastLoginIsPresent() {
    FastLoginPremiumBridge bridge = new FastLoginPremiumBridge(
        new FakeProxy(new FakePluginManager(Optional.of(new FakeContainer(new FakeFastLogin(Map.of()))))),
        Logger.getLogger("test"));

    assertTrue(bridge.isAvailable());
  }

  @Test
  void requiresAPremiumProfileMatchingTheConnection() {
    Map<InetSocketAddress, Object> sessions = new ConcurrentHashMap<>();
    sessions.put(ADDRESS, new FakeSession(new FakeProfile(true, PREMIUM_UUID, "Notch")));
    FastLoginPremiumBridge bridge = new FastLoginPremiumBridge(
        new FakeProxy(new FakePluginManager(Optional.of(new FakeContainer(new FakeFastLogin(sessions))))),
        Logger.getLogger("test"));

    assertTrue(bridge.isVerified(player("Notch", PREMIUM_UUID)));
    assertFalse(bridge.isVerified(player("Notch", UUID.randomUUID())));
    assertFalse(bridge.isVerified(player("Impostor", PREMIUM_UUID)));
  }

  @Test
  void refusesCrackedProfilesAndUnknownAddresses() {
    Map<InetSocketAddress, Object> cracked = new ConcurrentHashMap<>();
    cracked.put(ADDRESS, new FakeSession(new FakeProfile(false, PREMIUM_UUID, "Notch")));
    FastLoginPremiumBridge crackedBridge = new FastLoginPremiumBridge(
        new FakeProxy(new FakePluginManager(Optional.of(new FakeContainer(new FakeFastLogin(cracked))))),
        Logger.getLogger("test"));

    assertFalse(crackedBridge.isVerified(player("Notch", PREMIUM_UUID)));
    assertFalse(new FastLoginPremiumBridge(
        new FakeProxy(new FakePluginManager(Optional.of(new FakeContainer(new FakeFastLogin(Map.of()))))),
        Logger.getLogger("test")).isVerified(player("Notch", PREMIUM_UUID)));
  }

  private static Player player(String username, UUID uuid) {
    return (Player) Proxy.newProxyInstance(
        FastLoginPremiumBridgeTest.class.getClassLoader(),
        new Class<?>[] {Player.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getRemoteAddress" -> ADDRESS;
          case "getUniqueId" -> uuid;
          case "getUsername" -> username;
          default -> method.getReturnType().isPrimitive() ? false : null;
        });
  }

  public static final class FakeProxy {
    private final FakePluginManager manager;

    FakeProxy(FakePluginManager manager) {
      this.manager = manager;
    }

    public FakePluginManager getPluginManager() {
      return this.manager;
    }
  }

  public static final class FakePluginManager {
    private final Optional<?> plugin;

    FakePluginManager(Optional<?> plugin) {
      this.plugin = plugin;
    }

    public Optional<?> getPlugin(String id) {
      return "fastlogin".equals(id) ? this.plugin : Optional.empty();
    }
  }

  /** Velocity hands back a plugin container, not the plugin instance itself. */
  public static final class FakeContainer {
    private final Object instance;

    FakeContainer(Object instance) {
      this.instance = instance;
    }

    public Object getInstance() {
      return this.instance;
    }
  }
  public static final class FakeFastLogin {
    private final Map<InetSocketAddress, Object> sessions;

    FakeFastLogin(Map<InetSocketAddress, Object> sessions) {
      this.sessions = sessions;
    }

    public Map<InetSocketAddress, Object> getSession() {
      return this.sessions;
    }
  }

  public static final class FakeSession {
    private final FakeProfile profile;

    FakeSession(FakeProfile profile) {
      this.profile = profile;
    }

    public FakeProfile getProfile() {
      return this.profile;
    }
  }

  public static final class FakeProfile {
    private final boolean premium;
    private final UUID id;
    private final String name;

    FakeProfile(boolean premium, UUID id, String name) {
      this.premium = premium;
      this.id = id;
      this.name = name;
    }

    public boolean isPremium() {
      return this.premium;
    }

    public UUID getId() {
      return this.id;
    }

    public String getName() {
      return this.name;
    }
  }
}