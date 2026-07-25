package io.github.addxiaoyi.starx.velocity.module.uworld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import io.github.addxiaoyi.starx.chunk.BuiltInBiome;
import io.github.addxiaoyi.starx.chunk.VirtualBlock;
import io.github.addxiaoyi.starx.file.BuiltInWorldFileType;
import io.github.addxiaoyi.starx.uworld.UworldEnterResult;
import io.github.addxiaoyi.starx.uworld.UworldFlowHandler;
import io.github.addxiaoyi.starx.uworld.UworldFlowOptions;
import io.github.addxiaoyi.starx.uworld.UworldFlowSession;
import io.github.addxiaoyi.starx.uworld.UworldHandle;
import io.github.addxiaoyi.starx.uworld.UworldOutcome;
import io.github.addxiaoyi.starx.uworld.UworldOutcomeType;
import io.github.addxiaoyi.starx.uworld.UworldPhase;
import io.github.addxiaoyi.starx.uworld.UworldRuntime;
import io.github.addxiaoyi.starx.uworld.UworldSpec;
import io.github.addxiaoyi.starx.uworld.UworldWorldEditor;
import io.github.addxiaoyi.starx.uworld.UworldWorldGenerator;
import io.github.addxiaoyi.starx.velocity.config.UworldConfig;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

final class UworldDiagnosticsTest {

  @Test
  void statusIsRegisteredButStillRequiresPermissionWhenDiagnosticsAreDisabled() {
    Fixture fixture = new Fixture(false, true);
    PlayerProbe admin = new PlayerProbe("admin", true);
    PlayerProbe denied = new PlayerProbe("denied", false);
    fixture.module.onEnable();

    assertFalse(fixture.access.command.hasPermission(invocation(denied.player, "status")));
    fixture.execute(denied, "status");
    fixture.execute(admin, "status");

    assertTrue(denied.hasMessage("permission"));
    assertTrue(admin.hasMessage("runtime=ready"));
    assertTrue(admin.hasMessage("worlds=0"));
    assertTrue(admin.hasMessage("sessions=0"));
  }

  @Test
  void disabledTestAndLeaveNeverCreateOrTransfer() {
    Fixture fixture = new Fixture(false, true);
    PlayerProbe admin = new PlayerProbe("admin", true);
    fixture.module.onEnable();

    fixture.execute(admin, "test");
    fixture.execute(admin, "leave");

    assertEquals(0, fixture.runtime.createCalls);
    assertTrue(admin.messageCount("disabled") >= 2);
  }

  @Test
  void testLazilyCreatesOneWorldAndForwardsFlowCallbacks() {
    Fixture fixture = new Fixture(true, true);
    PlayerProbe first = new PlayerProbe("first", true);
    PlayerProbe second = new PlayerProbe("second", true);
    fixture.module.onEnable();

    fixture.execute(first, "test");
    fixture.execute(second, "test");
    fixture.runtime.handler(first.player).onChat(
        fixture.runtime.sessionProbe(first.player), "hello");
    fixture.runtime.handler(first.player).onMove(
        fixture.runtime.sessionProbe(first.player), 1.0, 101.0, 2.0);
    fixture.runtime.handler(first.player).onMove(
        fixture.runtime.sessionProbe(first.player), 2.0, 101.0, 3.0);

    assertEquals(1, fixture.runtime.createCalls);
    assertEquals("starx.diagnostics", fixture.runtime.owner);
    assertEquals("diagnostics", fixture.runtime.spec.name());
    assertEquals(25, fixture.runtime.editor.blocks);
    assertTrue(first.hasMessage("ready"));
    assertTrue(first.hasMessage("hello"));
    assertEquals(1, first.messageCount("Movement callback"));
  }

  @Test
  void announcesTheDiagnosticsTransferBeforeEnteringConfiguration() {
    Fixture fixture = new Fixture(true, true);
    PlayerProbe admin = new PlayerProbe("admin", true);
    fixture.runtime.beforeEnter = () -> assertTrue(admin.hasMessage("正在进入 Uworld"));
    fixture.module.onEnable();

    fixture.execute(admin, "test");

    assertTrue(admin.hasMessage("Diagnostics Uworld ready"));
  }

  @Test
  void leaveUsesPreviousServerThenConfiguredFallback() {
    Fixture fixture = new Fixture(true, true);
    PlayerProbe returning = new PlayerProbe("returning", true);
    PlayerProbe newArrival = new PlayerProbe("new-arrival", true);
    RegisteredServer previous = server("previous");
    fixture.access.current.put(returning.player, previous);
    fixture.access.register(previous);
    fixture.module.onEnable();

    fixture.execute(returning, "test");
    fixture.runtime.handler(returning.player).onChat(
        fixture.runtime.sessionProbe(returning.player), "/uworld leave");
    fixture.execute(newArrival, "test");
    fixture.runtime.handler(newArrival.player).onChat(
        fixture.runtime.sessionProbe(newArrival.player), "/sxworld leave");

    assertSame(previous, fixture.runtime.sessionProbe(returning.player).target);
    assertSame(fixture.access.fallback, fixture.runtime.sessionProbe(newArrival.player).target);
  }

  @Test
  void sameNameFallbackReplacementFailsBeforeTransfer() {
    Fixture fixture = new Fixture(true, true);
    fixture.access.register(server("lobby"));
    PlayerProbe admin = new PlayerProbe("admin", true);
    fixture.module.onEnable();

    fixture.execute(admin, "test");
    SessionProbe session = fixture.runtime.sessionProbe(admin.player);
    fixture.execute(admin, "leave");

    assertNull(session.target);
    assertEquals(1, session.failCalls);
    assertNull(fixture.runtime.sessionProbe(admin.player));
    assertFalse(fixture.module.state().owns(admin.player, session));
    assertTrue(admin.hasMessage("unavailable"));
  }

  @Test
  void unregisteredCapturedFallbackFailsBeforeTransfer() {
    Fixture fixture = new Fixture(true, true);
    fixture.access.unregisterServer("lobby");
    PlayerProbe admin = new PlayerProbe("admin", true);
    fixture.module.onEnable();

    fixture.execute(admin, "test");
    SessionProbe session = fixture.runtime.sessionProbe(admin.player);
    fixture.execute(admin, "leave");

    assertNull(session.target);
    assertEquals(1, session.failCalls);
    assertNull(fixture.runtime.sessionProbe(admin.player));
    assertFalse(fixture.module.state().owns(admin.player, session));
    assertTrue(admin.hasMessage("unavailable"));
  }

  @Test
  void unavailableFallbackFailsAndCleansUpTheSession() {
    Fixture fixture = new Fixture(true, false);
    PlayerProbe admin = new PlayerProbe("admin", true);
    fixture.module.onEnable();

    fixture.execute(admin, "test");
    SessionProbe session = fixture.runtime.sessionProbe(admin.player);
    fixture.execute(admin, "leave");

    assertEquals(1, session.failCalls);
    assertTrue(session.failureReason.toString().contains("unavailable"));
    assertNull(fixture.runtime.sessionProbe(admin.player));
    assertFalse(fixture.module.state().owns(admin.player, session));
    assertTrue(admin.hasMessage("unavailable"));
  }

  @Test
  void unregisteredPreviousServerFailsBeforeTransfer() {
    Fixture fixture = new Fixture(true, false);
    PlayerProbe admin = new PlayerProbe("admin", true);
    RegisteredServer previous = server("previous");
    fixture.access.current.put(admin.player, previous);
    fixture.module.onEnable();

    fixture.execute(admin, "test");
    SessionProbe session = fixture.runtime.sessionProbe(admin.player);
    fixture.execute(admin, "leave");

    assertNull(session.target);
    assertEquals(1, session.failCalls);
    assertNull(fixture.runtime.sessionProbe(admin.player));
    assertFalse(fixture.module.state().owns(admin.player, session));
    assertTrue(admin.hasMessage("unavailable"));
  }

  @Test
  void sameNameReplacementCannotBecomeTheReturnTarget() {
    Fixture fixture = new Fixture(true, true);
    PlayerProbe admin = new PlayerProbe("admin", true);
    RegisteredServer previous = server("previous");
    fixture.access.current.put(admin.player, previous);
    fixture.access.register(server("previous"));
    fixture.module.onEnable();

    fixture.execute(admin, "test");
    SessionProbe session = fixture.runtime.sessionProbe(admin.player);
    fixture.execute(admin, "leave");

    assertNull(session.target);
    assertEquals(1, session.failCalls);
    assertNull(fixture.runtime.sessionProbe(admin.player));
    assertFalse(fixture.module.state().owns(admin.player, session));
    assertTrue(admin.hasMessage("unavailable"));
  }

  @Test
  void unavailableFallbackReportsAnAlreadyTerminalSession() {
    Fixture fixture = new Fixture(true, false);
    PlayerProbe admin = new PlayerProbe("admin", true);
    fixture.module.onEnable();
    fixture.execute(admin, "test");
    SessionProbe session = fixture.runtime.sessionProbe(admin.player);
    session.acceptFailure = false;

    fixture.execute(admin, "leave");

    assertEquals(1, session.failCalls);
    assertFalse(fixture.module.state().owns(admin.player, session));
    assertTrue(admin.hasMessage("no longer active"));
  }

  @Test
  void leaveDoesNotOperateOnAForeignUworldSession() {
    Fixture fixture = new Fixture(true, true);
    PlayerProbe admin = new PlayerProbe("admin", true);
    fixture.module.onEnable();
    fixture.runtime.installForeignSession(admin.player);

    fixture.execute(admin, "leave");

    assertNull(fixture.runtime.sessionProbe(admin.player).target);
    assertTrue(admin.hasMessage("not a diagnostics"));
  }

  @Test
  void rejectedLeaveKeepsThePreviousServerForARetry() {
    Fixture fixture = new Fixture(true, true);
    PlayerProbe admin = new PlayerProbe("admin", true);
    RegisteredServer previous = server("previous");
    fixture.access.current.put(admin.player, previous);
    fixture.access.register(previous);
    fixture.module.onEnable();
    fixture.execute(admin, "test");
    SessionProbe session = fixture.runtime.sessionProbe(admin.player);
    session.acceptTransfer = false;

    fixture.execute(admin, "leave");
    session.acceptTransfer = true;
    fixture.execute(admin, "leave");

    assertSame(previous, session.target);
  }

  @Test
  void disableUnregistersCommandClosesWorldAndClearsPreviousServer() {
    Fixture fixture = new Fixture(true, true);
    PlayerProbe admin = new PlayerProbe("admin", true);
    fixture.access.current.put(admin.player, server("previous"));
    fixture.module.onEnable();
    fixture.execute(admin, "test");

    fixture.module.onDisable();
    fixture.module.onDisable();

    assertTrue(fixture.access.unregistered);
    assertTrue(fixture.runtime.handle.closed);
    assertEquals(1, fixture.access.unregisterCalls);
    assertEquals(1, fixture.runtime.handle.closeCalls);
    assertSame(
        fixture.access.fallback,
        fixture.module.state().returnTarget(admin.player, fixture.access.fallback));
  }

  @Test
  void failedWorldCloseKeepsTheHandleForDisableRetry() {
    RealCloseFixture fixture = new RealCloseFixture();
    fixture.module.onEnable();
    PlayerProbe admin = new PlayerProbe("admin", true);
    admin.routeDisconnectsTo(fixture.runtime);
    fixture.execute(admin, "test");
    UworldRuntimeTestSupport.LimboProbe limbo = fixture.factory.lastLimbo();
    limbo.failNextDispose(new IllegalStateException("diagnostics close rejected"));

    IllegalStateException failure = assertThrows(
        IllegalStateException.class,
        fixture.module::onDisable);

    assertEquals("Unable to close the diagnostics Uworld", failure.getMessage());
    assertEquals(1, limbo.disposeCount());
    assertEquals(1, fixture.runtime.worldCount());

    fixture.module.onDisable();
    fixture.module.onDisable();

    assertEquals(2, limbo.disposeCount());
    assertEquals(0, fixture.runtime.worldCount());
    assertEquals(1, fixture.access.unregisterCalls);
  }

  @Test
  void combinedDisableFailuresAreRetriedAndBecomeIdempotentAfterSuccess() {
    RealCloseFixture fixture = new RealCloseFixture();
    fixture.module.onEnable();
    PlayerProbe admin = new PlayerProbe("admin", true);
    admin.routeDisconnectsTo(fixture.runtime);
    fixture.execute(admin, "test");
    UworldRuntimeTestSupport.LimboProbe limbo = fixture.factory.lastLimbo();
    fixture.access.rejectUnregister = true;
    limbo.failNextDispose(new IllegalStateException("diagnostics close rejected"));

    IllegalStateException failure = assertThrows(
        IllegalStateException.class,
        fixture.module::onDisable);

    assertEquals("Unable to unregister Uworld diagnostics", failure.getMessage());
    assertEquals(1, failure.getSuppressed().length);
    assertEquals(
        "Unable to close the diagnostics Uworld",
        failure.getSuppressed()[0].getMessage());
    assertEquals(1, fixture.access.unregisterCalls);
    assertEquals(1, limbo.disposeCount());

    fixture.access.rejectUnregister = false;
    fixture.module.onDisable();
    fixture.module.onDisable();

    assertEquals(2, fixture.access.unregisterCalls);
    assertEquals(2, limbo.disposeCount());
    assertTrue(fixture.access.unregistered);
    assertEquals(0, fixture.runtime.worldCount());
  }

  private static SimpleCommand.Invocation invocation(CommandSource source, String... args) {
    return (SimpleCommand.Invocation) Proxy.newProxyInstance(
        SimpleCommand.Invocation.class.getClassLoader(),
        new Class<?>[]{SimpleCommand.Invocation.class},
        (proxy, method, values) -> switch (method.getName()) {
          case "source" -> source;
          case "arguments" -> args;
          case "alias" -> "uworld";
          default -> defaultValue(method.getReturnType());
        });
  }

  private static RegisteredServer server(String name) {
    ServerInfo info = new ServerInfo(name, new InetSocketAddress("127.0.0.1", 25565));
    return (RegisteredServer) Proxy.newProxyInstance(
        RegisteredServer.class.getClassLoader(),
        new Class<?>[]{RegisteredServer.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "toString" -> name;
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          case "getServerInfo" -> info;
          default -> defaultValue(method.getReturnType());
        });
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive()) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == char.class) {
      return '\0';
    }
    return 0;
  }

  private static final class Fixture {
    private final RuntimeProbe runtime = new RuntimeProbe();
    private final AccessProbe access;
    private final UworldDiagnostics module;

    private Fixture(boolean diagnosticsEnabled, boolean fallbackAvailable) {
      UworldConfig defaults = UworldConfig.defaults();
      UworldConfig config = new UworldConfig(
          true,
          defaults.transferTimeoutSeconds(),
          defaults.auth(),
          new UworldConfig.Diagnostics(diagnosticsEnabled, 30, 2));
      this.access = new AccessProbe(fallbackAvailable);
      this.module = new UworldDiagnostics(
          this.runtime,
          config,
          this.access,
          Logger.getLogger(UworldDiagnosticsTest.class.getName()));
    }

    private void execute(PlayerProbe player, String... args) {
      this.access.command.execute(invocation(player.player, args));
    }
  }

  private static final class RealCloseFixture {
    private final UworldRuntimeTestSupport.FactoryProbe factory =
        new UworldRuntimeTestSupport.FactoryProbe();
    private final EmbeddedUworldRuntime runtime = new EmbeddedUworldRuntime(
        this.factory.factory(),
        new UworldRuntimeTestSupport.ManualScheduler(),
        (player, action) -> action.run(),
        () -> { });
    private final AccessProbe access = new AccessProbe(true);
    private final UworldDiagnostics module;

    private RealCloseFixture() {
      UworldConfig defaults = UworldConfig.defaults();
      UworldConfig config = new UworldConfig(
          true,
          defaults.transferTimeoutSeconds(),
          defaults.auth(),
          new UworldConfig.Diagnostics(true, 30, 2));
      this.module = new UworldDiagnostics(
          this.runtime,
          config,
          this.access,
          Logger.getLogger(UworldDiagnosticsTest.class.getName()));
    }

    private void execute(PlayerProbe player, String... args) {
      this.access.command.execute(invocation(player.player, args));
    }
  }

  private static final class AccessProbe implements UworldDiagnostics.CommandAccess {
    private final RegisteredServer fallback = server("lobby");
    private final Map<Player, RegisteredServer> current = new IdentityHashMap<>();
    private final Map<String, RegisteredServer> registered = new java.util.HashMap<>();
    private SimpleCommand command;
    private boolean unregistered;
    private boolean rejectUnregister;
    private int unregisterCalls;

    private AccessProbe(boolean fallbackAvailable) {
      if (fallbackAvailable) {
        this.register(this.fallback);
      }
    }

    private void register(RegisteredServer server) {
      this.registered.put(server.getServerInfo().getName(), server);
    }

    private void unregisterServer(String name) {
      this.registered.remove(name);
    }

    @Override
    public void register(SimpleCommand command) {
      this.command = command;
    }

    @Override
    public void unregister() {
      if (this.command == null) {
        return;
      }
      this.unregisterCalls++;
      if (this.rejectUnregister) {
        throw new IllegalStateException("command unregister rejected");
      }
      this.command = null;
      this.unregistered = true;
    }

    @Override
    public Optional<RegisteredServer> resolve(String name) {
      return Optional.ofNullable(this.registered.get(name));
    }

    @Override
    public Optional<RegisteredServer> currentServer(Player player) {
      return Optional.ofNullable(this.current.get(player));
    }
  }

  private static final class RuntimeProbe implements UworldRuntime {
    private final Map<Player, SessionProbe> sessions = new IdentityHashMap<>();
    private final EditorProbe editor = new EditorProbe();
    private int createCalls;
    private String owner;
    private UworldSpec spec;
    private HandleProbe handle;
    private Runnable beforeEnter = () -> { };

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public UworldHandle createWorld(
        String owner,
        UworldSpec spec,
        UworldWorldGenerator generator
    ) {
      this.createCalls++;
      this.owner = owner;
      this.spec = spec;
      try {
        generator.generate(this.editor);
      } catch (Exception error) {
        throw new AssertionError(error);
      }
      this.handle = new HandleProbe(this);
      return this.handle;
    }

    @Override
    public Optional<UworldFlowSession> session(Player player) {
      return Optional.ofNullable(this.sessions.get(player));
    }

    private SessionProbe sessionProbe(Player player) {
      return this.sessions.get(player);
    }

    private UworldFlowHandler handler(Player player) {
      return this.sessions.get(player).handler;
    }

    private void installForeignSession(Player player) {
      this.sessions.put(player, new SessionProbe(
          this,
          player,
          new ForeignHandle(),
          new UworldFlowHandler() { }));
    }

    @Override
    public int worldCount() {
      return this.handle == null || this.handle.closed ? 0 : 1;
    }

    @Override
    public int sessionCount() {
      return this.sessions.size();
    }
  }

  private static final class ForeignHandle implements UworldHandle {
    @Override
    public String name() {
      return "foreign";
    }

    @Override
    public boolean isOpen() {
      return true;
    }

    @Override
    public UworldEnterResult enter(
        Player player,
        UworldFlowOptions options,
        UworldFlowHandler handler
    ) {
      throw new UnsupportedOperationException("foreign test handle");
    }

    @Override
    public CompletionStage<Void> closeAsync(Component reason) {
      return CompletableFuture.completedFuture(null);
    }
  }

  private static final class HandleProbe implements UworldHandle {
    private final RuntimeProbe runtime;
    private boolean closed;
    private int closeCalls;

    private HandleProbe(RuntimeProbe runtime) {
      this.runtime = runtime;
    }

    @Override
    public String name() {
      return "diagnostics";
    }

    @Override
    public boolean isOpen() {
      return !this.closed;
    }

    @Override
    public UworldEnterResult enter(
        Player player,
        UworldFlowOptions options,
        UworldFlowHandler handler
    ) {
      this.runtime.beforeEnter.run();
      SessionProbe session = new SessionProbe(this.runtime, player, this, handler);
      this.runtime.sessions.put(player, session);
      handler.onReady(session);
      return new UworldEnterResult.Accepted(session);
    }

    @Override
    public CompletionStage<Void> closeAsync(Component reason) {
      this.closeCalls++;
      this.closed = true;
      return CompletableFuture.completedFuture(null);
    }
  }

  private static final class SessionProbe implements UworldFlowSession {
    private final RuntimeProbe runtime;
    private final Player player;
    private final UworldHandle world;
    private final UworldFlowHandler handler;
    private final CompletableFuture<UworldOutcome> completion = new CompletableFuture<>();
    private RegisteredServer target;
    private Component failureReason;
    private int failCalls;
    private boolean acceptTransfer = true;
    private boolean acceptFailure = true;

    private SessionProbe(
        RuntimeProbe runtime,
        Player player,
        UworldHandle world,
        UworldFlowHandler handler
    ) {
      this.runtime = runtime;
      this.player = player;
      this.world = world;
      this.handler = handler;
    }

    @Override
    public Player player() {
      return this.player;
    }

    @Override
    public UworldHandle world() {
      return this.world;
    }

    @Override
    public UworldPhase phase() {
      return UworldPhase.ACTIVE;
    }

    @Override
    public boolean complete(RegisteredServer target) {
      if (!this.acceptTransfer) {
        return false;
      }
      this.target = target;
      return true;
    }

    @Override
    public boolean fail(Component reason) {
      this.failCalls++;
      this.failureReason = reason;
      if (!this.acceptFailure) {
        return false;
      }
      this.runtime.sessions.remove(this.player, this);
      UworldOutcome outcome = new UworldOutcome(
          UworldOutcomeType.FAILED,
          reason,
          Optional.empty());
      this.completion.complete(outcome);
      this.handler.onOutcome(this, outcome);
      return true;
    }

    @Override
    public boolean cancel(Component reason) {
      return true;
    }

    @Override
    public CompletionStage<UworldOutcome> completion() {
      return this.completion;
    }

    @Override
    public void execute(Runnable action) {
      action.run();
    }
  }

  private static final class EditorProbe implements UworldWorldEditor {
    private int blocks;

    @Override
    public VirtualBlock createBlock(String modernId) {
      return null;
    }

    @Override
    public void setBlock(int x, int y, int z, VirtualBlock block) {
      this.blocks++;
    }

    @Override
    public void setBiome(int x, int y, int z, BuiltInBiome biome) {
    }

    @Override
    public void fillSkyLight(int level) {
    }

    @Override
    public void fillBlockLight(int level) {
    }

    @Override
    public void load(
        BuiltInWorldFileType type,
        Path path,
        int offsetX,
        int offsetY,
        int offsetZ
    ) {
    }

    @Override
    public boolean isSealed() {
      return false;
    }
  }

  private static final class PlayerProbe implements InvocationHandler {
    private final String name;
    private final boolean permitted;
    private final List<Component> messages = new ArrayList<>();
    private final Player player;
    private Runnable disconnectCallback;

    private PlayerProbe(String name, boolean permitted) {
      this.name = name;
      this.permitted = permitted;
      this.player = (Player) Proxy.newProxyInstance(
          Player.class.getClassLoader(),
          new Class<?>[]{Player.class},
          this);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
      return switch (method.getName()) {
        case "getUsername" -> this.name;
        case "getUniqueId" -> UUID.nameUUIDFromBytes(this.name.getBytes());
        case "hasPermission" -> this.permitted;
        case "sendMessage" -> {
          if (args != null) {
            for (Object value : args) {
              if (value instanceof Component component) {
                this.messages.add(component);
              }
            }
          }
          yield null;
        }
        case "disconnect" -> {
          if (this.disconnectCallback != null) {
            this.disconnectCallback.run();
          }
          yield null;
        }
        case "hashCode" -> System.identityHashCode(proxy);
        case "equals" -> proxy == args[0];
        case "toString" -> "PlayerProbe[" + this.name + "]";
        default -> defaultValue(method.getReturnType());
      };
    }

    private boolean hasMessage(String text) {
      return this.messages.stream().anyMatch(message -> message.toString().contains(text));
    }

    private int messageCount(String text) {
      return (int) this.messages.stream()
          .filter(message -> message.toString().contains(text))
          .count();
    }

    private void routeDisconnectsTo(EmbeddedUworldRuntime runtime) {
      this.disconnectCallback = () -> runtime.onDisconnect(this.player);
    }
  }
}
