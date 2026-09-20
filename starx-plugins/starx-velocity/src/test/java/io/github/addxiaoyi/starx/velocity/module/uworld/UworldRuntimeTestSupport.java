/*
 * Copyright (C) 2025 StarX Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.github.addxiaoyi.starx.velocity.module.uworld;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import io.github.addxiaoyi.starx.Limbo;
import io.github.addxiaoyi.starx.LimboFactory;
import io.github.addxiaoyi.starx.LimboSessionHandler;
import io.github.addxiaoyi.starx.chunk.VirtualBlock;
import io.github.addxiaoyi.starx.chunk.VirtualWorld;
import io.github.addxiaoyi.starx.player.LimboPlayer;
import io.github.addxiaoyi.starx.uworld.UworldFlowHandler;
import io.github.addxiaoyi.starx.uworld.UworldFlowSession;
import io.github.addxiaoyi.starx.uworld.UworldOutcome;
import io.github.addxiaoyi.starx.uworld.UworldTransferPlayer;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import net.kyori.adventure.text.Component;

final class UworldRuntimeTestSupport {

  private UworldRuntimeTestSupport() {
  }

  static final class CorePlayerProbe implements EmbeddedUworldRuntime.CorePlayerLifecycle {
    private final Set<Player> active = ConcurrentHashMap.newKeySet();
    private final AtomicInteger releases = new AtomicInteger();

    @Override
    public void prepare(Player player) {
      this.active.add(player);
    }

    @Override
    public void release(Player player) {
      if (this.active.remove(player)) {
        this.releases.incrementAndGet();
      }
    }

    boolean active(Player player) {
      return this.active.contains(player);
    }

    int releases() {
      return this.releases.get();
    }
  }

  static final class ManualScheduler implements EmbeddedUworldRuntime.TimeoutScheduler {
    private final List<ManualTask> tasks = new ArrayList<>();
    private final AtomicInteger scheduleCalls = new AtomicInteger();

    private volatile int failingCall = -1;
    private volatile RuntimeException scheduleFailure;

    @Override
    public EmbeddedUworldRuntime.Cancellable schedule(Duration delay, Runnable action) {
      if (this.scheduleCalls.incrementAndGet() == this.failingCall) {
        throw this.scheduleFailure;
      }
      ManualTask task = new ManualTask(delay, action);
      this.tasks.add(task);
      return task::cancel;
    }

    void failOnSchedule(int call, RuntimeException error) {
      this.scheduleFailure = java.util.Objects.requireNonNull(error, "error");
      this.failingCall = call;
    }

    boolean fireNext(Duration delay) {
      for (ManualTask task : this.tasks) {
        if (task.delay().equals(delay) && task.fire()) {
          return true;
        }
      }
      return false;
    }

    boolean fireEvenIfCancelled(int index) {
      return this.tasks.get(index).fireEvenIfCancelled();
    }

    long pending(Duration delay) {
      return this.tasks.stream()
          .filter(task -> task.delay().equals(delay) && task.isPending())
          .count();
    }
  }

  private static final class ManualTask {
    private final Duration delay;
    private final Runnable action;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean fired = new AtomicBoolean();

    private ManualTask(Duration delay, Runnable action) {
      this.delay = delay;
      this.action = action;
    }

    Duration delay() {
      return this.delay;
    }

    boolean isPending() {
      return !this.cancelled.get() && !this.fired.get();
    }

    void cancel() {
      this.cancelled.set(true);
    }

    boolean fire() {
      if (this.cancelled.get() || !this.fired.compareAndSet(false, true)) {
        return false;
      }
      this.action.run();
      return true;
    }

    boolean fireEvenIfCancelled() {
      if (!this.fired.compareAndSet(false, true)) {
        return false;
      }
      this.action.run();
      return true;
    }
  }

  static final class FactoryProbe implements InvocationHandler {
    private final List<String> order = new ArrayList<>();
    private final List<LimboProbe> limbos = new ArrayList<>();
    private final LimboFactory factory;
    private final VirtualBlock block = identityProxy(VirtualBlock.class, "block");
    private final AtomicReference<Boolean> editorSealedAtCreate = new AtomicReference<>();
    private volatile boolean autoSpawn = true;
    private volatile RuntimeException nextSpawnFailure;
    private volatile RuntimeException nextTransferFailure;
    private volatile java.util.concurrent.CompletionStage<Boolean> nextTransfer;
    private volatile java.util.concurrent.CompletionStage<UworldTransferPlayer.TransferResult>
        nextDetailedTransfer;
    private volatile CountDownLatch spawnEntered;
    private volatile CountDownLatch releaseSpawn;
    private volatile java.util.function.BooleanSupplier editorSealed;

    FactoryProbe() {
      this.factory = proxy(LimboFactory.class, this);
    }

    LimboFactory factory() {
      return this.factory;
    }

    List<String> order() {
      return List.copyOf(this.order);
    }

    void record(String step) {
      this.order.add(step);
    }

    List<LimboProbe> limbos() {
      return List.copyOf(this.limbos);
    }

    LimboProbe lastLimbo() {
      return this.limbos.getLast();
    }

    void autoSpawn(boolean value) {
      this.autoSpawn = value;
    }

    void failNextSpawn(RuntimeException error) {
      this.nextSpawnFailure = error;
    }

    void failNextTransfer(RuntimeException error) {
      this.nextTransferFailure = error;
    }

    void useNextTransfer(java.util.concurrent.CompletionStage<Boolean> transfer) {
      this.nextTransfer = transfer;
    }

    void kickNextTransfer(Component reason) {
      this.nextDetailedTransfer = java.util.concurrent.CompletableFuture.completedFuture(
          UworldTransferPlayer.TransferResult.kicked(reason));
    }

    void blockSpawn(CountDownLatch entered, CountDownLatch release) {
      this.spawnEntered = entered;
      this.releaseSpawn = release;
    }

    void observeEditor(java.util.function.BooleanSupplier sealed) {
      this.editorSealed = sealed;
    }

    Boolean editorSealedAtCreate() {
      return this.editorSealedAtCreate.get();
    }

    @Override
    public Object invoke(Object instance, Method method, Object[] args) {
      if (isObjectMethod(instance, method, args)) {
        return objectMethod(instance, method, args, "factory");
      }
      return switch (method.getName()) {
        case "createVirtualWorld" -> {
          this.order.add("virtual-world");
          yield identityProxy(VirtualWorld.class, "world-" + this.limbos.size());
        }
        case "createLimbo" -> {
          this.order.add("create-limbo");
          java.util.function.BooleanSupplier sealed = this.editorSealed;
          this.editorSealedAtCreate.set(sealed == null ? null : sealed.getAsBoolean());
          LimboProbe probe = new LimboProbe(this);
          this.limbos.add(probe);
          yield probe.limbo();
        }
        case "createSimpleBlock" -> this.block;
        default -> defaultValue(method.getReturnType());
      };
    }
  }

  static final class LimboProbe implements InvocationHandler {
    private final FactoryProbe factory;
    private final Limbo limbo;
    private final Map<String, Object> settings = new HashMap<>();
    private final AtomicInteger disposeCount = new AtomicInteger();
    private final AtomicInteger lowLevelDisconnects = new AtomicInteger();
    private volatile RuntimeException nextDisposeFailure;
    private volatile Player player;
    private volatile LimboSessionHandler handler;
    private volatile RegisteredServer disconnectTarget;

    LimboProbe(FactoryProbe factory) {
      this.factory = factory;
      this.limbo = proxy(Limbo.class, this);
    }

    Limbo limbo() {
      return this.limbo;
    }

    Map<String, Object> settings() {
      return Map.copyOf(this.settings);
    }

    int disposeCount() {
      return this.disposeCount.get();
    }

    void failNextDispose(RuntimeException error) {
      this.nextDisposeFailure = java.util.Objects.requireNonNull(error, "error");
    }

    int lowLevelDisconnects() {
      return this.lowLevelDisconnects.get();
    }

    RegisteredServer disconnectTarget() {
      return this.disconnectTarget;
    }

    LimboSessionHandler handler() {
      return this.handler;
    }

    void spawn() {
      this.handler.onSpawn(this.limbo, this.limboPlayer());
    }

    @Override
    public Object invoke(Object instance, Method method, Object[] args) {
      if (isObjectMethod(instance, method, args)) {
        return objectMethod(instance, method, args, "limbo");
      }
      return switch (method.getName()) {
        case "spawnPlayer" -> {
          RuntimeException failure = this.factory.nextSpawnFailure;
          this.factory.nextSpawnFailure = null;
          if (failure != null) {
            throw failure;
          }
          this.player = (Player) args[0];
          this.handler = (LimboSessionHandler) args[1];
          CountDownLatch entered = this.factory.spawnEntered;
          CountDownLatch release = this.factory.releaseSpawn;
          if (entered != null && release != null) {
            entered.countDown();
            try {
              release.await();
            } catch (InterruptedException error) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException("Spawn probe interrupted", error);
            }
          }
          if (this.factory.autoSpawn) {
            this.spawn();
          }
          yield null;
        }
        case "dispose" -> {
          this.disposeCount.incrementAndGet();
          RuntimeException failure = this.nextDisposeFailure;
          this.nextDisposeFailure = null;
          if (failure != null) {
            throw failure;
          }
          yield null;
        }
        case "setName", "setReadTimeout", "setWorldTime", "setGameMode",
             "setViewDistance", "setSimulationDistance" -> {
          this.settings.put(method.getName(), args[0]);
          yield this.limbo;
        }
        default -> defaultValue(method.getReturnType());
      };
    }

    private LimboPlayer limboPlayer() {
      return (LimboPlayer) Proxy.newProxyInstance(
          LimboPlayer.class.getClassLoader(),
          new Class<?>[]{LimboPlayer.class, UworldTransferPlayer.class},
          (instance, method, args) -> {
        if (isObjectMethod(instance, method, args)) {
          return objectMethod(instance, method, args, "limbo-player");
        }
        if (method.getName().equals("disconnect")) {
          this.lowLevelDisconnects.incrementAndGet();
          if (args != null && args.length == 1) {
            this.disconnectTarget = (RegisteredServer) args[0];
          }
          return null;
        }
        if (method.getName().equals("transferTo")) {
          this.lowLevelDisconnects.incrementAndGet();
          this.disconnectTarget = (RegisteredServer) args[0];
          java.util.concurrent.CompletionStage<Boolean> transfer = this.factory.nextTransfer;
          this.factory.nextTransfer = null;
          if (transfer != null) {
            return transfer;
          }
          RuntimeException failure = this.factory.nextTransferFailure;
          this.factory.nextTransferFailure = null;
          return failure == null
              ? java.util.concurrent.CompletableFuture.completedFuture(true)
              : java.util.concurrent.CompletableFuture.failedFuture(failure);
        }
        if (method.getName().equals("transferResultTo")) {
          this.lowLevelDisconnects.incrementAndGet();
          this.disconnectTarget = (RegisteredServer) args[0];
          java.util.concurrent.CompletionStage<UworldTransferPlayer.TransferResult> transfer =
              this.factory.nextDetailedTransfer;
          this.factory.nextDetailedTransfer = null;
          if (transfer != null) {
            return transfer;
          }
          RuntimeException failure = this.factory.nextTransferFailure;
          this.factory.nextTransferFailure = null;
          if (failure != null) {
            return java.util.concurrent.CompletableFuture.failedFuture(failure);
          }
          java.util.concurrent.CompletionStage<Boolean> legacy = this.factory.nextTransfer;
          this.factory.nextTransfer = null;
          if (legacy != null) {
            return legacy.thenApply(started -> Boolean.TRUE.equals(started)
                ? UworldTransferPlayer.TransferResult.started()
                : UworldTransferPlayer.TransferResult.failed());
          }
          return java.util.concurrent.CompletableFuture.completedFuture(
              UworldTransferPlayer.TransferResult.started());
        }
        if (method.getName().equals("getProxyPlayer")) {
          return this.player;
        }
        return defaultValue(method.getReturnType());
          });
    }
  }

  static final class PlayerProbe implements InvocationHandler {
    private final String name;
    private final Player player;
    private final AtomicInteger disconnects = new AtomicInteger();
    private volatile boolean autoDisconnect = true;
    private volatile Runnable disconnectCallback;
    private volatile RuntimeException disconnectFailure;
    private volatile Component reason;

    PlayerProbe(String name) {
      this.name = name;
      this.player = proxy(Player.class, this);
    }

    Player player() {
      return this.player;
    }

    int disconnects() {
      return this.disconnects.get();
    }

    Component reason() {
      return this.reason;
    }

    void autoDisconnect(boolean value) {
      this.autoDisconnect = value;
    }

    void finishDisconnect() {
      Runnable callback = this.disconnectCallback;
      if (callback != null) {
        callback.run();
      }
    }

    void failDisconnect(RuntimeException error) {
      this.disconnectFailure = error;
    }

    void onDisconnect(Runnable callback) {
      this.disconnectCallback = callback;
    }

    void routeDisconnectsTo(EmbeddedUworldRuntime runtime) {
      this.onDisconnect(() -> runtime.onDisconnect(this.player));
    }

    @Override
    public Object invoke(Object instance, Method method, Object[] args) {
      if (isObjectMethod(instance, method, args)) {
        return objectMethod(instance, method, args, this.name);
      }
      return switch (method.getName()) {
        case "getUsername" -> this.name;
        case "disconnect" -> {
          this.reason = (Component) args[0];
          this.disconnects.incrementAndGet();
          RuntimeException failure = this.disconnectFailure;
          this.disconnectFailure = null;
          if (failure != null) {
            throw failure;
          }
          if (this.autoDisconnect) {
            this.finishDisconnect();
          }
          yield null;
        }
        default -> defaultValue(method.getReturnType());
      };
    }
  }

  static final class HandlerProbe implements UworldFlowHandler {
    private final AtomicInteger ready = new AtomicInteger();
    private final AtomicInteger moves = new AtomicInteger();
    private final AtomicInteger rotations = new AtomicInteger();
    private final AtomicInteger outcomes = new AtomicInteger();
    private final List<String> chats = new ArrayList<>();
    private final List<Boolean> grounds = new ArrayList<>();
    private final List<Integer> teleports = new ArrayList<>();
    private final List<Object> generics = new ArrayList<>();
    private volatile UworldOutcome outcome;

    @Override
    public void onReady(UworldFlowSession session) {
      this.ready.incrementAndGet();
    }

    @Override
    public void onChat(UworldFlowSession session, String message) {
      this.chats.add(message);
    }

    @Override
    public void onMove(UworldFlowSession session, double x, double y, double z) {
      this.moves.incrementAndGet();
    }

    @Override
    public void onRotate(UworldFlowSession session, float yaw, float pitch) {
      this.rotations.incrementAndGet();
    }

    @Override
    public void onGround(UworldFlowSession session, boolean onGround) {
      this.grounds.add(onGround);
    }

    @Override
    public void onTeleport(UworldFlowSession session, int teleportId) {
      this.teleports.add(teleportId);
    }

    @Override
    public void onGeneric(UworldFlowSession session, Object packet) {
      this.generics.add(packet);
    }

    @Override
    public void onOutcome(UworldFlowSession session, UworldOutcome outcome) {
      this.outcome = outcome;
      this.outcomes.incrementAndGet();
    }

    int ready() {
      return this.ready.get();
    }

    int moves() {
      return this.moves.get();
    }

    int rotations() {
      return this.rotations.get();
    }

    int outcomes() {
      return this.outcomes.get();
    }

    List<String> chats() {
      return List.copyOf(this.chats);
    }

    List<Boolean> grounds() {
      return List.copyOf(this.grounds);
    }

    List<Integer> teleports() {
      return List.copyOf(this.teleports);
    }

    List<Object> generics() {
      return List.copyOf(this.generics);
    }

    UworldOutcome outcome() {
      return this.outcome;
    }
  }

  static RegisteredServer server(String name) {
    com.velocitypowered.api.proxy.server.ServerInfo info =
        new com.velocitypowered.api.proxy.server.ServerInfo(
            name, new java.net.InetSocketAddress("127.0.0.1", 25565));
    return proxy(RegisteredServer.class, (instance, method, args) -> {
      if (isObjectMethod(instance, method, args)) {
        return objectMethod(instance, method, args, name);
      }
      if (method.getName().equals("getServerInfo")) {
        return info;
      }
      return defaultValue(method.getReturnType());
    });
  }

  static <T> T identityProxy(Class<T> type, String name) {
    return proxy(type, (instance, method, args) -> {
      if (isObjectMethod(instance, method, args)) {
        return objectMethod(instance, method, args, name);
      }
      return defaultValue(method.getReturnType());
    });
  }

  private static boolean isObjectMethod(Object instance, Method method, Object[] args) {
    return method.getDeclaringClass() == Object.class;
  }

  private static Object objectMethod(Object instance, Method method, Object[] args, String name) {
    return switch (method.getName()) {
      case "hashCode" -> System.identityHashCode(instance);
      case "equals" -> instance == args[0];
      case "toString" -> name;
      default -> throw new UnsupportedOperationException(method.getName());
    };
  }

  private static Object defaultValue(Class<?> type) {
    if (type == java.util.Optional.class) {
      return java.util.Optional.empty();
    }
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

  private static <T> T proxy(Class<T> type, InvocationHandler handler) {
    return type.cast(Proxy.newProxyInstance(
        type.getClassLoader(),
        new Class<?>[]{type},
        handler));
  }
}
