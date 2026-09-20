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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.uworld.UworldCreationException;
import io.github.addxiaoyi.starx.uworld.UworldEnterResult;
import io.github.addxiaoyi.starx.uworld.UworldEnterStatus;
import io.github.addxiaoyi.starx.uworld.UworldFlowOptions;
import io.github.addxiaoyi.starx.uworld.UworldFlowSession;
import io.github.addxiaoyi.starx.uworld.UworldHandle;
import io.github.addxiaoyi.starx.uworld.UworldOutcomeType;
import io.github.addxiaoyi.starx.uworld.UworldSpec;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

final class EmbeddedUworldRuntimeTest {

  @Test
  void createsVirtualWorldThenGeneratesSealsAndCreatesLimbo() {
    RuntimeFixture fixture = new RuntimeFixture();
    AtomicReference<io.github.addxiaoyi.starx.uworld.UworldWorldEditor> editor =
        new AtomicReference<>();
    fixture.factory.observeEditor(() -> editor.get().isSealed());

    UworldHandle world = fixture.runtime.createWorld(
        "starx.auth",
        UworldSpec.defaults("auth"),
        current -> {
          fixture.factory.record("generate");
          editor.set(current);
          assertFalse(current.isSealed());
        });

    assertEquals("auth", world.name());
    assertTrue(world.isOpen());
    assertEquals(
        java.util.List.of("virtual-world", "generate", "create-limbo"),
        fixture.factory.order());
    assertTrue(editor.get().isSealed());
    assertEquals(Boolean.TRUE, fixture.factory.editorSealedAtCreate());
    assertEquals("auth", fixture.factory.lastLimbo().settings().get("setName"));
    assertEquals(30_000, fixture.factory.lastLimbo().settings().get("setReadTimeout"));
  }

  @Test
  void generatorFailureSealsTheEditorAndDoesNotPublishTheWorld() {
    RuntimeFixture fixture = new RuntimeFixture();
    AtomicReference<io.github.addxiaoyi.starx.uworld.UworldWorldEditor> editor =
        new AtomicReference<>();

    assertThrows(UworldCreationException.class, () -> fixture.runtime.createWorld(
        "starx.auth",
        UworldSpec.defaults("auth"),
        current -> {
          editor.set(current);
          throw new IllegalStateException("broken schematic");
        }));

    assertTrue(editor.get().isSealed());
    assertTrue(fixture.factory.limbos().isEmpty());
    assertTrue(fixture.runtime.createWorld(
        "starx.auth", UworldSpec.defaults("auth"), current -> { }).isOpen());
  }

  @Test
  void duplicateWorldReportsOwnersAndDisposesTheUnpublishedLimbo() {
    RuntimeFixture fixture = new RuntimeFixture();
    fixture.runtime.createWorld("starx.auth", UworldSpec.defaults("shared"), current -> { });

    UworldCreationException error = assertThrows(UworldCreationException.class,
        () -> fixture.runtime.createWorld(
            "starx.diagnostics", UworldSpec.defaults("shared"), current -> { }));

    assertTrue(error.getMessage().contains("starx.auth"));
    assertTrue(error.getMessage().contains("starx.diagnostics"));
    assertEquals(2, fixture.factory.limbos().size());
    assertEquals(0, fixture.factory.limbos().get(0).disposeCount());
    assertEquals(1, fixture.factory.limbos().get(1).disposeCount());
  }

  @Test
  void onePlayerCannotReplaceAnExistingSession() {
    RuntimeFixture fixture = new RuntimeFixture();
    UworldHandle auth = fixture.runtime.createWorld(
        "starx.auth", UworldSpec.defaults("auth"), current -> { });
    UworldHandle diagnostics = fixture.runtime.createWorld(
        "starx.diagnostics", UworldSpec.defaults("diagnostics"), current -> { });
    UworldRuntimeTestSupport.PlayerProbe player =
        new UworldRuntimeTestSupport.PlayerProbe("same-player");

    assertInstanceOf(UworldEnterResult.Accepted.class,
        auth.enter(player.player(), UworldFlowOptions.defaults(), new UworldRuntimeTestSupport.HandlerProbe()));
    UworldEnterResult.Rejected rejected = assertInstanceOf(UworldEnterResult.Rejected.class,
        diagnostics.enter(
            player.player(), UworldFlowOptions.defaults(), new UworldRuntimeTestSupport.HandlerProbe()));

    assertEquals(UworldEnterStatus.PLAYER_BUSY, rejected.status());
  }

  @Test
  void synchronousSpawnFailureRollsBackTheExactPlayerClaim() {
    RuntimeFixture fixture = new RuntimeFixture();
    UworldHandle world = fixture.runtime.createWorld(
        "starx.auth", UworldSpec.defaults("auth"), current -> { });
    UworldRuntimeTestSupport.PlayerProbe player =
        new UworldRuntimeTestSupport.PlayerProbe("spawn-failure");
    UworldRuntimeTestSupport.HandlerProbe handler = new UworldRuntimeTestSupport.HandlerProbe();
    fixture.factory.failNextSpawn(new IllegalStateException("spawn rejected"));

    UworldEnterResult.Rejected rejected = assertInstanceOf(UworldEnterResult.Rejected.class,
        world.enter(player.player(), UworldFlowOptions.defaults(), handler));

    assertEquals(UworldEnterStatus.SPAWN_REJECTED, rejected.status());
    assertTrue(fixture.runtime.session(player.player()).isEmpty());
    assertEquals(UworldOutcomeType.SPAWN_REJECTED, handler.outcome().type());
    assertInstanceOf(UworldEnterResult.Accepted.class,
        world.enter(player.player(), UworldFlowOptions.defaults(), new UworldRuntimeTestSupport.HandlerProbe()));
  }

  @Test
  void synchronousDisconnectFailureReleasesSessionAndAllowsReentry() {
    UworldRuntimeTestSupport.FactoryProbe factory = new UworldRuntimeTestSupport.FactoryProbe();
    UworldRuntimeTestSupport.CorePlayerProbe corePlayers =
        new UworldRuntimeTestSupport.CorePlayerProbe();
    EmbeddedUworldRuntime runtime = new EmbeddedUworldRuntime(
        factory.factory(),
        new UworldRuntimeTestSupport.ManualScheduler(),
        (player, action) -> action.run(),
        corePlayers,
        () -> { });
    UworldHandle world = runtime.createWorld(
        "starx.auth", UworldSpec.defaults("auth"), current -> { });
    UworldRuntimeTestSupport.PlayerProbe player =
        new UworldRuntimeTestSupport.PlayerProbe("disconnect-reentry");
    player.routeDisconnectsTo(runtime);
    IllegalStateException disconnectFailure = new IllegalStateException("disconnect rejected");
    player.failDisconnect(disconnectFailure);

    UworldFlowSession first = accepted(world.enter(
        player.player(),
        UworldFlowOptions.defaults(),
        new UworldRuntimeTestSupport.HandlerProbe()));
    assertTrue(first.cancel(Component.text("first")));
    CompletionException thrown = assertThrows(
        CompletionException.class,
        () -> first.completion().toCompletableFuture().join());

    assertSame(disconnectFailure, thrown.getCause());
    assertEquals(0, runtime.sessionCount());
    assertTrue(runtime.session(player.player()).isEmpty());
    assertFalse(corePlayers.active(player.player()));
    assertEquals(1, corePlayers.releases());

    UworldFlowSession second = accepted(world.enter(
        player.player(),
        UworldFlowOptions.defaults(),
        new UworldRuntimeTestSupport.HandlerProbe()));
    assertTrue(second.cancel(Component.text("cleanup")));
    assertEquals(
        UworldOutcomeType.CANCELLED,
        second.completion().toCompletableFuture().join().type());
    assertEquals(0, runtime.sessionCount());
    assertFalse(corePlayers.active(player.player()));
    assertEquals(2, corePlayers.releases());
  }

  @Test
  void timeoutSchedulingFailureRejectsEntryAndRollsBackTheExactPlayerClaim() {
    UworldRuntimeTestSupport.FactoryProbe factory = new UworldRuntimeTestSupport.FactoryProbe();
    EmbeddedUworldRuntime runtime = new EmbeddedUworldRuntime(
        factory.factory(),
        (delay, action) -> {
          throw new IllegalStateException("scheduler stopped");
        },
        (player, action) -> action.run(),
        () -> { });
    UworldHandle world = runtime.createWorld(
        "starx.auth", UworldSpec.defaults("auth"), current -> { });
    UworldRuntimeTestSupport.PlayerProbe player =
        new UworldRuntimeTestSupport.PlayerProbe("schedule-failure");

    UworldEnterResult.Rejected rejected = assertInstanceOf(
        UworldEnterResult.Rejected.class,
        world.enter(
            player.player(),
            UworldFlowOptions.defaults(),
            new UworldRuntimeTestSupport.HandlerProbe()));

    assertEquals(UworldEnterStatus.SPAWN_REJECTED, rejected.status());
    assertEquals(0, runtime.sessionCount());
    assertTrue(runtime.session(player.player()).isEmpty());
  }

  @Test
  void closingOneWorldDoesNotAffectAnotherWorld() {
    RuntimeFixture fixture = new RuntimeFixture();
    UworldHandle auth = fixture.runtime.createWorld(
        "starx.auth", UworldSpec.defaults("auth"), current -> { });
    UworldHandle diagnostics = fixture.runtime.createWorld(
        "starx.diagnostics", UworldSpec.defaults("diagnostics"), current -> { });
    UworldRuntimeTestSupport.PlayerProbe authPlayer =
        new UworldRuntimeTestSupport.PlayerProbe("auth-player");
    UworldRuntimeTestSupport.PlayerProbe diagnosticsPlayer =
        new UworldRuntimeTestSupport.PlayerProbe("diagnostics-player");
    authPlayer.routeDisconnectsTo(fixture.runtime);
    UworldFlowSession authSession = accepted(auth.enter(
        authPlayer.player(), UworldFlowOptions.defaults(), new UworldRuntimeTestSupport.HandlerProbe()));
    UworldFlowSession diagnosticsSession = accepted(diagnostics.enter(
        diagnosticsPlayer.player(), UworldFlowOptions.defaults(), new UworldRuntimeTestSupport.HandlerProbe()));

    auth.closeAsync(Component.text("auth stopped")).toCompletableFuture().join();
    auth.closeAsync(Component.text("again")).toCompletableFuture().join();

    assertEquals(UworldOutcomeType.WORLD_CLOSED,
        authSession.completion().toCompletableFuture().join().type());
    assertFalse(diagnosticsSession.completion().toCompletableFuture().isDone());
    assertTrue(diagnostics.isOpen());
    assertEquals(1, fixture.factory.limbos().get(0).disposeCount());
    assertEquals(0, fixture.factory.limbos().get(1).disposeCount());
  }

  @Test
  void closingWorldDoesNotReleaseCoreStateForAReplacementSession() {
    UworldRuntimeTestSupport.FactoryProbe factory = new UworldRuntimeTestSupport.FactoryProbe();
    UworldRuntimeTestSupport.CorePlayerProbe corePlayers =
        new UworldRuntimeTestSupport.CorePlayerProbe();
    EmbeddedUworldRuntime runtime = new EmbeddedUworldRuntime(
        factory.factory(),
        new UworldRuntimeTestSupport.ManualScheduler(),
        (player, action) -> action.run(),
        corePlayers,
        () -> { });
    UworldHandle first = runtime.createWorld(
        "starx.first", UworldSpec.defaults("first"), current -> { });
    UworldHandle second = runtime.createWorld(
        "starx.second", UworldSpec.defaults("second"), current -> { });
    UworldRuntimeTestSupport.PlayerProbe player =
        new UworldRuntimeTestSupport.PlayerProbe("replacement-session");
    player.routeDisconnectsTo(runtime);
    AtomicReference<UworldEnterResult> replacement = new AtomicReference<>();
    io.github.addxiaoyi.starx.uworld.UworldFlowHandler handler =
        new io.github.addxiaoyi.starx.uworld.UworldFlowHandler() {
          @Override
          public void onOutcome(
              UworldFlowSession session,
              io.github.addxiaoyi.starx.uworld.UworldOutcome outcome
          ) {
            replacement.set(second.enter(
                player.player(),
                UworldFlowOptions.defaults(),
                new UworldRuntimeTestSupport.HandlerProbe()));
          }
        };
    accepted(first.enter(player.player(), UworldFlowOptions.defaults(), handler));

    first.closeAsync(Component.text("replace")).toCompletableFuture().join();

    UworldFlowSession current = assertInstanceOf(
        UworldEnterResult.Accepted.class, replacement.get()).session();
    assertSame(second, current.world());
    assertSame(current, runtime.session(player.player()).orElseThrow());
    assertTrue(corePlayers.active(player.player()));
    assertEquals(1, corePlayers.releases());
  }

  @Test
  void concurrentWorldCloseCallsShareTheSamePendingCompletion() throws Exception {
    UworldRuntimeTestSupport.FactoryProbe factory = new UworldRuntimeTestSupport.FactoryProbe();
    UworldRuntimeTestSupport.ManualScheduler scheduler =
        new UworldRuntimeTestSupport.ManualScheduler();
    ArrayDeque<Runnable> eventLoop = new ArrayDeque<>();
    EmbeddedUworldRuntime runtime = new EmbeddedUworldRuntime(
        factory.factory(), scheduler, (player, action) -> eventLoop.add(action), () -> { });
    UworldHandle world = runtime.createWorld(
        "starx.auth", UworldSpec.defaults("auth"), current -> { });
    UworldRuntimeTestSupport.PlayerProbe player =
        new UworldRuntimeTestSupport.PlayerProbe("concurrent-close");
    player.routeDisconnectsTo(runtime);
    accepted(world.enter(
        player.player(),
        UworldFlowOptions.defaults(),
        new UworldRuntimeTestSupport.HandlerProbe()));
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);

    try {
      Future<CompletionStage<Void>> firstCall = executor.submit(() -> {
        ready.countDown();
        start.await();
        return world.closeAsync(Component.text("first"));
      });
      Future<CompletionStage<Void>> secondCall = executor.submit(() -> {
        ready.countDown();
        start.await();
        return world.closeAsync(Component.text("second"));
      });
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();

      CompletionStage<Void> first = firstCall.get(5, TimeUnit.SECONDS);
      CompletionStage<Void> second = secondCall.get(5, TimeUnit.SECONDS);

      assertSame(first, second);
      assertFalse(first.toCompletableFuture().isDone());
      eventLoop.removeFirst().run();
      first.toCompletableFuture().join();
      assertEquals(1, factory.lastLimbo().disposeCount());
    } finally {
      start.countDown();
      while (!eventLoop.isEmpty()) {
        eventLoop.removeFirst().run();
      }
      executor.shutdownNow();
    }
  }

  @Test
  void failedWorldDisposeCanBeRetriedAndKeepsTheWorldRegisteredUntilSuccess() {
    RuntimeFixture fixture = new RuntimeFixture();
    UworldHandle world = fixture.runtime.createWorld(
        "starx.auth", UworldSpec.defaults("auth"), current -> { });
    UworldRuntimeTestSupport.LimboProbe limbo = fixture.factory.lastLimbo();
    limbo.failNextDispose(new IllegalStateException("dispose rejected"));

    CompletionStage<Void> first = world.closeAsync(Component.text("first"));

    assertThrows(java.util.concurrent.CompletionException.class,
        () -> first.toCompletableFuture().join());
    assertEquals(1, limbo.disposeCount());
    assertEquals(1, fixture.runtime.worldCount());

    CompletionStage<Void> retry = world.closeAsync(Component.text("retry"));
    retry.toCompletableFuture().join();

    assertEquals(2, limbo.disposeCount());
    assertEquals(0, fixture.runtime.worldCount());
    assertSame(retry, world.closeAsync(Component.text("already closed")));
  }

  @Test
  void runtimeCloseIsIdempotentAndRejectsNewWork() {
    RuntimeFixture fixture = new RuntimeFixture();
    UworldHandle first = fixture.runtime.createWorld(
        "starx.auth", UworldSpec.defaults("auth"), current -> { });
    UworldHandle second = fixture.runtime.createWorld(
        "starx.diagnostics", UworldSpec.defaults("diagnostics"), current -> { });

    fixture.runtime.closeAsync(Component.text("shutdown")).toCompletableFuture().join();
    fixture.runtime.closeAsync(Component.text("again")).toCompletableFuture().join();

    assertFalse(fixture.runtime.isReady());
    assertFalse(first.isOpen());
    assertFalse(second.isOpen());
    assertEquals(1, fixture.factory.limbos().get(0).disposeCount());
    assertEquals(1, fixture.factory.limbos().get(1).disposeCount());
    assertEquals(1, fixture.coreCloses.get());
    assertThrows(UworldCreationException.class, () -> fixture.runtime.createWorld(
        "starx.extra", UworldSpec.defaults("extra"), current -> { }));
  }

  @Test
  void runtimeCloseRetriesAWorldThatFailedToDispose() {
    RuntimeFixture fixture = new RuntimeFixture();
    fixture.runtime.createWorld(
        "starx.auth", UworldSpec.defaults("auth"), current -> { });
    UworldRuntimeTestSupport.LimboProbe limbo = fixture.factory.lastLimbo();
    limbo.failNextDispose(new IllegalStateException("dispose rejected"));

    assertThrows(java.util.concurrent.CompletionException.class,
        () -> fixture.runtime.closeAsync(Component.text("first"))
            .toCompletableFuture()
            .join());

    assertEquals(1, fixture.runtime.worldCount());
    assertEquals(1, limbo.disposeCount());
    assertEquals(0, fixture.coreCloses.get());

    fixture.runtime.closeAsync(Component.text("retry")).toCompletableFuture().join();

    assertEquals(0, fixture.runtime.worldCount());
    assertEquals(2, limbo.disposeCount());
    assertEquals(1, fixture.coreCloses.get());
  }

  @Test
  void runtimeCloseRetriesCoreCloseAfterItThrows() {
    UworldRuntimeTestSupport.FactoryProbe factory = new UworldRuntimeTestSupport.FactoryProbe();
    AtomicInteger coreCloseAttempts = new AtomicInteger();
    EmbeddedUworldRuntime runtime = new EmbeddedUworldRuntime(
        factory.factory(),
        new UworldRuntimeTestSupport.ManualScheduler(),
        (player, action) -> action.run(),
        () -> {
          if (coreCloseAttempts.incrementAndGet() == 1) {
            throw new IllegalStateException("core close rejected");
          }
        });

    assertThrows(java.util.concurrent.CompletionException.class,
        () -> runtime.closeAsync(Component.text("first")).toCompletableFuture().join());
    runtime.closeAsync(Component.text("retry")).toCompletableFuture().join();
    runtime.closeAsync(Component.text("already closed")).toCompletableFuture().join();

    assertEquals(2, coreCloseAttempts.get());
  }

  @Test
  void runtimeClosePublishesRuntimeStoppingForActiveSessions() {
    RuntimeFixture fixture = new RuntimeFixture();
    UworldHandle world = fixture.runtime.createWorld(
        "starx.auth", UworldSpec.defaults("auth"), current -> { });
    UworldRuntimeTestSupport.PlayerProbe player =
        new UworldRuntimeTestSupport.PlayerProbe("runtime-stopping");
    player.routeDisconnectsTo(fixture.runtime);
    UworldFlowSession session = accepted(world.enter(
        player.player(), UworldFlowOptions.defaults(), new UworldRuntimeTestSupport.HandlerProbe()));

    fixture.runtime.closeAsync(Component.text("shutdown")).toCompletableFuture().join();

    assertEquals(UworldOutcomeType.RUNTIME_STOPPING,
        session.completion().toCompletableFuture().join().type());
    assertTrue(fixture.runtime.session(player.player()).isEmpty());
  }

  @Test
  void runtimeCloseWaitsForProxyDisconnectBeforeDisposal() throws Exception {
    UworldRuntimeTestSupport.FactoryProbe factory = new UworldRuntimeTestSupport.FactoryProbe();
    ArrayDeque<Runnable> eventLoop = new ArrayDeque<>();
    AtomicInteger coreCloses = new AtomicInteger();
    EmbeddedUworldRuntime runtime = new EmbeddedUworldRuntime(
        factory.factory(),
        new UworldRuntimeTestSupport.ManualScheduler(),
        (player, action) -> eventLoop.add(action),
        coreCloses::incrementAndGet);
    UworldHandle world = runtime.createWorld(
        "starx.auth", UworldSpec.defaults("auth"), current -> { });
    UworldRuntimeTestSupport.PlayerProbe player =
        new UworldRuntimeTestSupport.PlayerProbe("delayed-low-level-disconnect");
    player.autoDisconnect(false);
    UworldFlowSession session = accepted(world.enter(
        player.player(), UworldFlowOptions.defaults(), new UworldRuntimeTestSupport.HandlerProbe()));

    CompletableFuture<Void> closing = runtime.closeAsync(Component.text("shutdown"))
        .toCompletableFuture();
    eventLoop.removeFirst().run();

    assertFalse(closing.isDone());
    assertFalse(session.completion().toCompletableFuture().isDone());
    assertEquals(0, factory.lastLimbo().disposeCount());
    assertEquals(0, coreCloses.get());

    runtime.onDisconnect(player.player());
    closing.get(5, TimeUnit.SECONDS);

    assertEquals(UworldOutcomeType.RUNTIME_STOPPING,
        session.completion().toCompletableFuture().join().type());
    assertEquals(1, factory.lastLimbo().disposeCount());
    assertEquals(1, coreCloses.get());
  }

  @Test
  void runtimeCloseHandlesDisconnectBeforeQueuedTerminalPublish() throws Exception {
    UworldRuntimeTestSupport.FactoryProbe factory = new UworldRuntimeTestSupport.FactoryProbe();
    ArrayDeque<Runnable> eventLoop = new ArrayDeque<>();
    AtomicInteger coreCloses = new AtomicInteger();
    EmbeddedUworldRuntime runtime = new EmbeddedUworldRuntime(
        factory.factory(),
        new UworldRuntimeTestSupport.ManualScheduler(),
        (player, action) -> eventLoop.add(action),
        coreCloses::incrementAndGet);
    UworldHandle world = runtime.createWorld(
        "starx.auth", UworldSpec.defaults("auth"), current -> { });
    UworldRuntimeTestSupport.PlayerProbe player =
        new UworldRuntimeTestSupport.PlayerProbe("detach-before-publish");
    player.autoDisconnect(false);
    UworldFlowSession session = accepted(world.enter(
        player.player(), UworldFlowOptions.defaults(), new UworldRuntimeTestSupport.HandlerProbe()));

    CompletableFuture<Void> closing = runtime.closeAsync(Component.text("shutdown"))
        .toCompletableFuture();
    runtime.onDisconnect(player.player());
    eventLoop.removeFirst().run();

    closing.get(5, TimeUnit.SECONDS);
    assertEquals(UworldOutcomeType.RUNTIME_STOPPING,
        session.completion().toCompletableFuture().join().type());
    assertEquals(1, factory.lastLimbo().disposeCount());
    assertEquals(1, coreCloses.get());
  }

  @Test
  void failedSessionCompletionStillReleasesPlayerAndWorldResources() {
    UworldRuntimeTestSupport.FactoryProbe factory = new UworldRuntimeTestSupport.FactoryProbe();
    UworldRuntimeTestSupport.CorePlayerProbe corePlayers =
        new UworldRuntimeTestSupport.CorePlayerProbe();
    AtomicInteger coreCloses = new AtomicInteger();
    EmbeddedUworldRuntime runtime = new EmbeddedUworldRuntime(
        factory.factory(),
        new UworldRuntimeTestSupport.ManualScheduler(),
        (player, action) -> action.run(),
        corePlayers,
        coreCloses::incrementAndGet);
    UworldHandle world = runtime.createWorld(
        "starx.auth", UworldSpec.defaults("auth"), current -> { });
    UworldRuntimeTestSupport.PlayerProbe player =
        new UworldRuntimeTestSupport.PlayerProbe("disconnect-failure");
    player.failDisconnect(new IllegalStateException("disconnect rejected"));
    UworldFlowSession session = accepted(world.enter(
        player.player(), UworldFlowOptions.defaults(), new UworldRuntimeTestSupport.HandlerProbe()));

    CompletableFuture<Void> closing = runtime.closeAsync(Component.text("shutdown"))
        .toCompletableFuture();

    assertThrows(java.util.concurrent.CompletionException.class, closing::join);
    assertTrue(session.completion().toCompletableFuture().isCompletedExceptionally());
    assertTrue(runtime.session(player.player()).isEmpty());
    assertFalse(corePlayers.active(player.player()));
    assertEquals(1, corePlayers.releases());
    assertEquals(1, factory.lastLimbo().disposeCount());
    assertEquals(0, runtime.worldCount());
    assertEquals(0, coreCloses.get());

    runtime.closeAsync(Component.text("retry")).toCompletableFuture().join();
    assertEquals(1, coreCloses.get());
  }

  @Test
  void normalCompletionCoreReleaseFailureIsReportedAndRetried() {
    UworldRuntimeTestSupport.FactoryProbe factory = new UworldRuntimeTestSupport.FactoryProbe();
    AtomicInteger releaseAttempts = new AtomicInteger();
    EmbeddedUworldRuntime.CorePlayerLifecycle corePlayers =
        new EmbeddedUworldRuntime.CorePlayerLifecycle() {
          @Override
          public void prepare(com.velocitypowered.api.proxy.Player player) {
          }

          @Override
          public void release(com.velocitypowered.api.proxy.Player player) {
            if (releaseAttempts.incrementAndGet() == 1) {
              throw new IllegalStateException("core player release rejected");
            }
          }
        };
    EmbeddedUworldRuntime runtime = new EmbeddedUworldRuntime(
        factory.factory(),
        new UworldRuntimeTestSupport.ManualScheduler(),
        (player, action) -> action.run(),
        corePlayers,
        () -> { });
    UworldHandle world = runtime.createWorld(
        "starx.auth", UworldSpec.defaults("auth"), current -> { });
    UworldRuntimeTestSupport.PlayerProbe player =
        new UworldRuntimeTestSupport.PlayerProbe("normal-release-failure");
    player.routeDisconnectsTo(runtime);
    UworldFlowSession session = accepted(world.enter(
        player.player(), UworldFlowOptions.defaults(), new UworldRuntimeTestSupport.HandlerProbe()));

    assertThrows(java.util.concurrent.CompletionException.class,
        () -> world.closeAsync(Component.text("first")).toCompletableFuture().join());

    assertEquals(UworldOutcomeType.WORLD_CLOSED,
        session.completion().toCompletableFuture().join().type());
    assertSame(session, runtime.session(player.player()).orElseThrow());
    assertEquals(1, runtime.worldCount());
    assertEquals(1, factory.lastLimbo().disposeCount());
    assertEquals(1, releaseAttempts.get());

    world.closeAsync(Component.text("retry")).toCompletableFuture().join();

    assertTrue(runtime.session(player.player()).isEmpty());
    assertEquals(0, runtime.worldCount());
    assertEquals(1, factory.lastLimbo().disposeCount());
    assertEquals(2, releaseAttempts.get());
  }

  @Test
  void worldCloseAggregatesSessionReleaseAndDisposeFailuresBeforeRetry() {
    UworldRuntimeTestSupport.FactoryProbe factory = new UworldRuntimeTestSupport.FactoryProbe();
    AtomicInteger releaseAttempts = new AtomicInteger();
    AtomicInteger coreCloses = new AtomicInteger();
    EmbeddedUworldRuntime.CorePlayerLifecycle corePlayers =
        new EmbeddedUworldRuntime.CorePlayerLifecycle() {
          @Override
          public void prepare(com.velocitypowered.api.proxy.Player player) {
          }

          @Override
          public void release(com.velocitypowered.api.proxy.Player player) {
            if (releaseAttempts.incrementAndGet() == 1) {
              throw new IllegalStateException("core player release rejected");
            }
          }
        };
    EmbeddedUworldRuntime runtime = new EmbeddedUworldRuntime(
        factory.factory(),
        new UworldRuntimeTestSupport.ManualScheduler(),
        (player, action) -> action.run(),
        corePlayers,
        coreCloses::incrementAndGet);
    UworldHandle world = runtime.createWorld(
        "starx.auth", UworldSpec.defaults("auth"), current -> { });
    UworldRuntimeTestSupport.PlayerProbe player =
        new UworldRuntimeTestSupport.PlayerProbe("aggregate-close-failure");
    player.failDisconnect(new IllegalStateException("disconnect rejected"));
    UworldFlowSession session = accepted(world.enter(
        player.player(), UworldFlowOptions.defaults(), new UworldRuntimeTestSupport.HandlerProbe()));
    factory.lastLimbo().failNextDispose(new IllegalStateException("dispose rejected"));

    java.util.concurrent.CompletionException thrown = assertThrows(
        java.util.concurrent.CompletionException.class,
        () -> runtime.closeAsync(Component.text("shutdown")).toCompletableFuture().join());

    Throwable runtimeFailure = thrown.getCause();
    assertEquals("Unable to close Uworld runtime", runtimeFailure.getMessage());
    assertEquals(1, runtimeFailure.getSuppressed().length);
    Throwable worldFailure = runtimeFailure.getSuppressed()[0].getCause();
    assertEquals("Unable to close Uworld auth", worldFailure.getMessage());
    assertEquals(3, worldFailure.getSuppressed().length);
    assertEquals("Uworld session completion failed",
        worldFailure.getSuppressed()[0].getMessage());
    assertEquals("Unable to release Uworld core player",
        worldFailure.getSuppressed()[1].getMessage());
    assertEquals("Unable to dispose Uworld", worldFailure.getSuppressed()[2].getMessage());
    assertSame(session, runtime.session(player.player()).orElseThrow());
    assertEquals(1, releaseAttempts.get());
    assertEquals(1, factory.lastLimbo().disposeCount());
    assertEquals(1, runtime.worldCount());

    runtime.closeAsync(Component.text("retry")).toCompletableFuture().join();

    assertEquals(2, factory.lastLimbo().disposeCount());
    assertEquals(0, runtime.worldCount());
    assertEquals(2, releaseAttempts.get());
    assertEquals(1, coreCloses.get());
  }

  @Test
  void disconnectFailureCompletesOutsideTerminalLock() throws Exception {
    UworldRuntimeTestSupport.FactoryProbe factory = new UworldRuntimeTestSupport.FactoryProbe();
    EmbeddedUworldRuntime runtime = new EmbeddedUworldRuntime(
        factory.factory(),
        new UworldRuntimeTestSupport.ManualScheduler(),
        (player, action) -> action.run(),
        () -> { });
    UworldHandle world = runtime.createWorld(
        "starx.auth", UworldSpec.defaults("auth"), current -> { });
    UworldRuntimeTestSupport.PlayerProbe player =
        new UworldRuntimeTestSupport.PlayerProbe("disconnect-callback-lock");
    player.failDisconnect(new IllegalStateException("disconnect rejected"));
    UworldFlowSession session = accepted(world.enter(
        player.player(), UworldFlowOptions.defaults(), new UworldRuntimeTestSupport.HandlerProbe()));
    ExecutorService executor = Executors.newSingleThreadExecutor();
    AtomicReference<Throwable> callbackFailure = new AtomicReference<>();

    try {
      CompletableFuture<Void> callback = session.completion().handle((outcome, error) -> {
        Future<?> disconnect = executor.submit(() -> runtime.onDisconnect(player.player()));
        try {
          disconnect.get(1, TimeUnit.SECONDS);
        } catch (Throwable callbackError) {
          callbackFailure.set(callbackError);
        }
        return (Void) null;
      }).toCompletableFuture();

      CompletableFuture<Void> closing = runtime.closeAsync(Component.text("shutdown"))
          .toCompletableFuture();

      assertThrows(ExecutionException.class, () -> closing.get(5, TimeUnit.SECONDS));
      callback.get(5, TimeUnit.SECONDS);
      assertNull(callbackFailure.get());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void closeWaitsForAnEnterThatAlreadyOwnsTheWorldLock() throws Exception {
    RuntimeFixture fixture = new RuntimeFixture();
    fixture.factory.autoSpawn(false);
    CountDownLatch spawnEntered = new CountDownLatch(1);
    CountDownLatch releaseSpawn = new CountDownLatch(1);
    fixture.factory.blockSpawn(spawnEntered, releaseSpawn);
    UworldHandle world = fixture.runtime.createWorld(
        "starx.auth", UworldSpec.defaults("auth"), current -> { });
    UworldRuntimeTestSupport.PlayerProbe player =
        new UworldRuntimeTestSupport.PlayerProbe("enter-close-race");
    player.routeDisconnectsTo(fixture.runtime);
    ExecutorService executor = Executors.newFixedThreadPool(2);

    try {
      Future<UworldEnterResult> enterCall = executor.submit(() -> world.enter(
          player.player(),
          UworldFlowOptions.defaults(),
          new UworldRuntimeTestSupport.HandlerProbe()));
      assertTrue(spawnEntered.await(5, TimeUnit.SECONDS));
      Future<java.util.concurrent.CompletionStage<Void>> closeCall = executor.submit(
          () -> world.closeAsync(Component.text("closing")));

      assertThrows(TimeoutException.class,
          () -> closeCall.get(100, TimeUnit.MILLISECONDS));
      releaseSpawn.countDown();
      UworldFlowSession session = assertInstanceOf(
          UworldEnterResult.Accepted.class,
          enterCall.get(5, TimeUnit.SECONDS)).session();
      closeCall.get(5, TimeUnit.SECONDS).toCompletableFuture().join();

      assertEquals(UworldOutcomeType.WORLD_CLOSED,
          session.completion().toCompletableFuture().join().type());
      assertTrue(fixture.runtime.session(player.player()).isEmpty());
      assertEquals(1, fixture.factory.lastLimbo().disposeCount());
    } finally {
      releaseSpawn.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void runtimeCloseWaitsForInFlightCreationAndNeverPublishesThatWorld() throws Exception {
    RuntimeFixture fixture = new RuntimeFixture();
    CountDownLatch generationEntered = new CountDownLatch(1);
    CountDownLatch releaseGeneration = new CountDownLatch(1);
    ExecutorService executor = Executors.newSingleThreadExecutor();

    try {
      Future<UworldHandle> creation = executor.submit(() -> fixture.runtime.createWorld(
          "starx.racing",
          UworldSpec.defaults("racing"),
          current -> {
            generationEntered.countDown();
            if (!releaseGeneration.await(5, TimeUnit.SECONDS)) {
              throw new IllegalStateException("test did not release world generation");
            }
          }));
      assertTrue(generationEntered.await(5, TimeUnit.SECONDS));

      Future<Void> closing = fixture.runtime.closeAsync(Component.text("shutdown"))
          .toCompletableFuture();
      assertThrows(TimeoutException.class, () -> closing.get(100, TimeUnit.MILLISECONDS));
      assertEquals(0, fixture.coreCloses.get());

      releaseGeneration.countDown();
      ExecutionException creationError = assertThrows(
          ExecutionException.class,
          () -> creation.get(5, TimeUnit.SECONDS));
      assertInstanceOf(UworldCreationException.class, creationError.getCause());
      closing.get(5, TimeUnit.SECONDS);

      assertEquals(0, fixture.runtime.worldCount());
      assertEquals(1, fixture.coreCloses.get());
      assertEquals(1, fixture.factory.lastLimbo().disposeCount());
    } finally {
      releaseGeneration.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void timeoutPublishesOutcomeOnThePlayerEventLoop() {
    UworldRuntimeTestSupport.FactoryProbe factory = new UworldRuntimeTestSupport.FactoryProbe();
    UworldRuntimeTestSupport.ManualScheduler scheduler =
        new UworldRuntimeTestSupport.ManualScheduler();
    ArrayDeque<Runnable> eventLoop = new ArrayDeque<>();
    EmbeddedUworldRuntime runtime = new EmbeddedUworldRuntime(
        factory.factory(),
        scheduler,
        (player, action) -> eventLoop.add(action),
        () -> { });
    UworldHandle world = runtime.createWorld(
        "starx.auth", UworldSpec.defaults("auth"), current -> { });
    UworldRuntimeTestSupport.PlayerProbe player =
        new UworldRuntimeTestSupport.PlayerProbe("event-loop-timeout");
    player.routeDisconnectsTo(runtime);
    UworldFlowSession session = assertInstanceOf(
        UworldEnterResult.Accepted.class,
        world.enter(
            player.player(),
            new UworldFlowOptions(Duration.ofSeconds(3), Duration.ofSeconds(2)),
            new UworldRuntimeTestSupport.HandlerProbe())).session();

    assertTrue(scheduler.fireNext(Duration.ofSeconds(3)));
    assertFalse(session.completion().toCompletableFuture().isDone());
    assertEquals(0, player.disconnects());

    eventLoop.removeFirst().run();

    assertEquals(UworldOutcomeType.TIMED_OUT,
        session.completion().toCompletableFuture().join().type());
    assertEquals(1, player.disconnects());
  }

  private static UworldFlowSession accepted(UworldEnterResult result) {
    return assertInstanceOf(UworldEnterResult.Accepted.class, result).session();
  }

  private static final class RuntimeFixture {
    private final UworldRuntimeTestSupport.FactoryProbe factory =
        new UworldRuntimeTestSupport.FactoryProbe();
    private final UworldRuntimeTestSupport.ManualScheduler scheduler =
        new UworldRuntimeTestSupport.ManualScheduler();
    private final AtomicInteger coreCloses = new AtomicInteger();
    private final EmbeddedUworldRuntime runtime = new EmbeddedUworldRuntime(
        this.factory.factory(),
        this.scheduler,
        (player, action) -> action.run(),
        this.coreCloses::incrementAndGet);
  }
}
