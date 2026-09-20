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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import io.github.addxiaoyi.starx.LimboSessionHandler;
import io.github.addxiaoyi.starx.uworld.UworldEnterResult;
import io.github.addxiaoyi.starx.uworld.UworldFlowOptions;
import io.github.addxiaoyi.starx.uworld.UworldFlowSession;
import io.github.addxiaoyi.starx.uworld.UworldHandle;
import io.github.addxiaoyi.starx.uworld.UworldOutcome;
import io.github.addxiaoyi.starx.uworld.UworldOutcomeType;
import io.github.addxiaoyi.starx.uworld.UworldPhase;
import io.github.addxiaoyi.starx.uworld.UworldSpec;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

final class ManagedUworldSessionTest {

  @Test
  void normalizesFullMoveCallbacksWithoutDuplicates() {
    SessionFixture fixture = new SessionFixture();
    fixture.factory.autoSpawn(false);
    UworldRuntimeTestSupport.HandlerProbe handler = new UworldRuntimeTestSupport.HandlerProbe();
    UworldFlowSession session = fixture.enter(handler);
    LimboSessionHandler lowLevel = fixture.factory.lastLimbo().handler();

    assertEquals(UworldPhase.ENTERING, session.phase());
    fixture.factory.lastLimbo().spawn();
    Object packet = new Object();
    lowLevel.onConfig(fixture.factory.lastLimbo().limbo(), null);
    lowLevel.onChat("hello");
    lowLevel.onMove(4.0, 5.0, 6.0);
    lowLevel.onMove(4.0, 5.0, 6.0, 45.0f, 10.0f);
    lowLevel.onRotate(45.0f, 10.0f);
    lowLevel.onGround(true);
    lowLevel.onTeleport(17);
    lowLevel.onGeneric(packet);
    AtomicInteger executed = new AtomicInteger();
    session.execute(executed::incrementAndGet);

    assertEquals(UworldPhase.ACTIVE, session.phase());
    assertEquals(1, handler.ready());
    assertEquals(java.util.List.of("hello"), handler.chats());
    assertEquals(1, handler.moves());
    assertEquals(1, handler.rotations());
    assertEquals(java.util.List.of(true), handler.grounds());
    assertEquals(java.util.List.of(17), handler.teleports());
    assertEquals(java.util.List.of(packet), handler.generics());
    assertEquals(1, executed.get());
  }

  @Test
  void exactTargetSurvivesLowLevelDisconnectAndCompletesOnce() {
    SessionFixture fixture = new SessionFixture();
    UworldRuntimeTestSupport.HandlerProbe handler = new UworldRuntimeTestSupport.HandlerProbe();
    UworldFlowSession session = fixture.enter(handler);
    RegisteredServer target = UworldRuntimeTestSupport.server("lobby");
    RegisteredServer other = UworldRuntimeTestSupport.server("other");

    assertTrue(fixture.corePlayers.active(fixture.player.player()));
    assertTrue(session.complete(target));
    assertSame(target, fixture.factory.lastLimbo().disconnectTarget());
    assertTrue(fixture.runtime.allowsBackend(fixture.player.player(), target));
    assertFalse(fixture.runtime.allowsBackend(fixture.player.player(), other));
    fixture.factory.lastLimbo().handler().onDisconnect();
    assertTrue(fixture.runtime.session(fixture.player.player()).isPresent());
    assertTrue(fixture.corePlayers.active(fixture.player.player()));
    assertEquals(0, fixture.corePlayers.releases());

    fixture.runtime.onConnected(fixture.player.player(), target);
    fixture.runtime.onConnected(fixture.player.player(), other);
    fixture.runtime.onDisconnect(fixture.player.player());

    assertEquals(UworldOutcomeType.TRANSFERRED,
        session.completion().toCompletableFuture().join().type());
    assertEquals(1, handler.outcomes());
    assertTrue(fixture.runtime.session(fixture.player.player()).isEmpty());
    assertFalse(fixture.corePlayers.active(fixture.player.player()));
    assertEquals(1, fixture.corePlayers.releases());
  }

  @Test
  void equivalentRegisteredServerCompletesTransfer() {
    SessionFixture fixture = new SessionFixture();
    UworldFlowSession session = fixture.enter(new UworldRuntimeTestSupport.HandlerProbe());

    assertTrue(session.complete(UworldRuntimeTestSupport.server("factions")));
    fixture.factory.lastLimbo().handler().onDisconnect();
    fixture.runtime.onConnected(
        fixture.player.player(), UworldRuntimeTestSupport.server("factions"));

    assertEquals(UworldOutcomeType.TRANSFERRED,
        session.completion().toCompletableFuture().join().type());
    assertEquals(0, fixture.player.disconnects());
    assertEquals(0, fixture.scheduler.pending(Duration.ofSeconds(15)));
  }

  @Test
  void connectionFutureFailureImmediatelyFailsTheManagedTransfer() {
    SessionFixture fixture = new SessionFixture();
    UworldFlowSession session = fixture.enter(new UworldRuntimeTestSupport.HandlerProbe());
    fixture.factory.failNextTransfer(new IllegalStateException("connection refused"));

    assertTrue(session.complete(UworldRuntimeTestSupport.server("offline")));

    assertTrue(session.completion().toCompletableFuture().isDone());
    assertEquals(UworldOutcomeType.FAILED,
        session.completion().toCompletableFuture().join().type());
    assertEquals(0, fixture.scheduler.pending(Duration.ofSeconds(15)));
    assertEquals(1, fixture.player.disconnects());
  }

  @Test
  void connectionFutureFailureAfterLowLevelDetachCompletesOnProxyDisconnect() throws Exception {
    SessionFixture fixture = new SessionFixture();
    UworldFlowSession session = fixture.enter(new UworldRuntimeTestSupport.HandlerProbe());
    CompletableFuture<Boolean> transfer = new CompletableFuture<>();
    fixture.factory.useNextTransfer(transfer);

    assertTrue(session.complete(UworldRuntimeTestSupport.server("offline")));
    fixture.factory.lastLimbo().handler().onDisconnect();

    assertTrue(fixture.runtime.session(fixture.player.player()).isPresent());
    assertFalse(session.completion().toCompletableFuture().isDone());

    transfer.completeExceptionally(new IllegalStateException("connection refused"));

    assertEquals(UworldOutcomeType.FAILED,
        session.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).type());
    assertTrue(fixture.runtime.session(fixture.player.player()).isEmpty());
  }

  @Test
  void backendDisconnectResultPreservesKickReason() {
    SessionFixture fixture = new SessionFixture();
    UworldFlowSession session = fixture.enter(new UworldRuntimeTestSupport.HandlerProbe());
    fixture.factory.kickNextTransfer(Component.text("maintenance window"));

    assertTrue(session.complete(UworldRuntimeTestSupport.server("lobby")));

    UworldOutcome outcome = session.completion().toCompletableFuture().join();
    assertEquals(UworldOutcomeType.KICKED, outcome.type());
    assertEquals(Component.text("maintenance window"), outcome.reason());
    assertTrue(fixture.runtime.session(fixture.player.player()).isEmpty());
  }

  @Test
  void preConnectGateDeniesActiveAndWrongTargets() {
    SessionFixture fixture = new SessionFixture();
    UworldFlowSession session = fixture.enter(new UworldRuntimeTestSupport.HandlerProbe());
    RegisteredServer target = UworldRuntimeTestSupport.server("lobby");
    RegisteredServer other = UworldRuntimeTestSupport.server("other");

    ServerPreConnectEvent active = new ServerPreConnectEvent(fixture.player.player(), target);
    fixture.runtime.onPreConnect(active);
    assertFalse(active.getResult().isAllowed());

    assertTrue(session.complete(target));
    ServerPreConnectEvent exact = new ServerPreConnectEvent(fixture.player.player(), target);
    fixture.runtime.onPreConnect(exact);
    assertTrue(exact.getResult().isAllowed());
    assertSame(target, exact.getResult().getServer().orElseThrow());

    ServerPreConnectEvent wrong = new ServerPreConnectEvent(fixture.player.player(), other);
    fixture.runtime.onPreConnect(wrong);
    assertFalse(wrong.getResult().isAllowed());

    UworldRuntimeTestSupport.PlayerProbe outsider =
        new UworldRuntimeTestSupport.PlayerProbe("outsider");
    ServerPreConnectEvent untouched = new ServerPreConnectEvent(outsider.player(), other);
    fixture.runtime.onPreConnect(untouched);
    assertTrue(untouched.getResult().isAllowed());
  }

  @Test
  void wrongTargetDisconnectsAndPublishesOneOutcome() {
    SessionFixture fixture = new SessionFixture();
    UworldRuntimeTestSupport.HandlerProbe handler = new UworldRuntimeTestSupport.HandlerProbe();
    UworldFlowSession session = fixture.enter(handler);
    RegisteredServer target = UworldRuntimeTestSupport.server("lobby");

    assertTrue(session.complete(target));
    fixture.runtime.onConnected(
        fixture.player.player(), UworldRuntimeTestSupport.server("unexpected"));
    fixture.runtime.onKick(fixture.player.player(), net.kyori.adventure.text.Component.text("late"));

    assertEquals(UworldOutcomeType.WRONG_TARGET,
        session.completion().toCompletableFuture().join().type());
    assertEquals(1, fixture.player.disconnects());
    assertEquals(1, handler.outcomes());
  }

  @Test
  void activeAndTransferTimeoutsUseTheirOwnDurations() {
    SessionFixture active = new SessionFixture();
    UworldFlowSession activeSession = active.enter(new UworldRuntimeTestSupport.HandlerProbe());

    assertEquals(1, active.scheduler.pending(Duration.ofMinutes(5)));
    assertTrue(active.scheduler.fireNext(Duration.ofMinutes(5)));
    assertEquals(UworldOutcomeType.TIMED_OUT,
        activeSession.completion().toCompletableFuture().join().type());
    assertEquals(1, active.player.disconnects());

    SessionFixture transfer = new SessionFixture();
    UworldFlowSession transferSession = transfer.enter(new UworldRuntimeTestSupport.HandlerProbe());
    assertTrue(transferSession.complete(UworldRuntimeTestSupport.server("lobby")));

    assertEquals(0, transfer.scheduler.pending(Duration.ofMinutes(5)));
    assertEquals(1, transfer.scheduler.pending(Duration.ofSeconds(15)));
    assertTrue(transfer.scheduler.fireNext(Duration.ofSeconds(15)));
    assertEquals(UworldOutcomeType.TIMED_OUT,
        transferSession.completion().toCompletableFuture().join().type());
    assertEquals(1, transfer.player.disconnects());
  }

  @Test
  void enteringSessionTimesOutWhenSpawnNeverCallsBack() {
    SessionFixture fixture = new SessionFixture();
    fixture.factory.autoSpawn(false);
    UworldFlowSession session = fixture.enter(new UworldRuntimeTestSupport.HandlerProbe());

    assertEquals(UworldPhase.ENTERING, session.phase());
    assertEquals(1, fixture.scheduler.pending(Duration.ofMinutes(5)));
    assertTrue(fixture.scheduler.fireNext(Duration.ofMinutes(5)));
    assertEquals(UworldOutcomeType.TIMED_OUT,
        session.completion().toCompletableFuture().join().type());
    assertTrue(fixture.runtime.session(fixture.player.player()).isEmpty());
  }

  @Test
  void cancelledEnteringTimeoutCannotCloseTheActivePhase() {
    SessionFixture fixture = new SessionFixture();
    UworldFlowSession session = fixture.enter(new UworldRuntimeTestSupport.HandlerProbe());

    assertEquals(UworldPhase.ACTIVE, session.phase());
    assertTrue(fixture.scheduler.fireEvenIfCancelled(0));

    assertEquals(UworldPhase.ACTIVE, session.phase());
    assertFalse(session.completion().toCompletableFuture().isDone());
    assertTrue(fixture.runtime.session(fixture.player.player()).isPresent());
    assertEquals(0, fixture.player.disconnects());
  }

  @Test
  void phaseBoundTerminationCannotCloseALaterPhase() {
    SessionFixture fixture = new SessionFixture();
    ManagedUworldSession session = (ManagedUworldSession) fixture.enter(
        new UworldRuntimeTestSupport.HandlerProbe());
    java.lang.reflect.Method terminateInPhase = assertDoesNotThrow(
        () -> EmbeddedUworldRuntime.class.getDeclaredMethod(
            "terminate",
            ManagedUworldSession.class,
            UworldPhase.class,
            UworldOutcomeType.class,
            net.kyori.adventure.text.Component.class,
            RegisteredServer.class,
            boolean.class));

    boolean terminated = assertDoesNotThrow(() -> (boolean) terminateInPhase.invoke(
        fixture.runtime,
        session,
        UworldPhase.ENTERING,
        UworldOutcomeType.TIMED_OUT,
        net.kyori.adventure.text.Component.text("late entry timeout"),
        null,
        true));

    assertFalse(terminated);
    assertEquals(UworldPhase.ACTIVE, session.phase());
    assertFalse(session.completion().toCompletableFuture().isDone());
    assertTrue(session.cancel(net.kyori.adventure.text.Component.text("test cleanup")));
  }

  @Test
  void activeTimeoutSchedulingFailureTerminatesTheSession() {
    SessionFixture fixture = new SessionFixture();
    fixture.factory.autoSpawn(false);
    fixture.scheduler.failOnSchedule(2, new IllegalStateException("scheduler stopped"));
    UworldFlowSession session = fixture.enter(new UworldRuntimeTestSupport.HandlerProbe());

    assertDoesNotThrow(() -> fixture.factory.lastLimbo().spawn());

    assertEquals(UworldOutcomeType.FAILED,
        session.completion().toCompletableFuture().join().type());
    assertEquals(UworldPhase.CLOSED, session.phase());
    assertTrue(fixture.runtime.session(fixture.player.player()).isEmpty());
    assertEquals(1, fixture.player.disconnects());
  }

  @Test
  void transferTimeoutSchedulingFailureTerminatesTheSession() {
    SessionFixture fixture = new SessionFixture();
    fixture.scheduler.failOnSchedule(3, new IllegalStateException("scheduler stopped"));
    UworldFlowSession session = fixture.enter(new UworldRuntimeTestSupport.HandlerProbe());

    boolean started = assertDoesNotThrow(
        () -> session.complete(UworldRuntimeTestSupport.server("lobby")));

    assertFalse(started);
    assertEquals(UworldOutcomeType.FAILED,
        session.completion().toCompletableFuture().join().type());
    assertEquals(UworldPhase.CLOSED, session.phase());
    assertTrue(fixture.runtime.session(fixture.player.player()).isEmpty());
    assertEquals(1, fixture.player.disconnects());
  }

  @Test
  void kickAndDisconnectHaveDistinctSingleOutcomes() {
    SessionFixture kicked = new SessionFixture();
    UworldRuntimeTestSupport.HandlerProbe kickHandler = new UworldRuntimeTestSupport.HandlerProbe();
    UworldFlowSession kickSession = kicked.enter(kickHandler);
    kicked.runtime.onKick(
        kicked.player.player(), net.kyori.adventure.text.Component.text("backend kick"));
    kicked.runtime.onDisconnect(kicked.player.player());

    assertEquals(UworldOutcomeType.KICKED,
        kickSession.completion().toCompletableFuture().join().type());
    assertEquals(1, kicked.player.disconnects());
    assertEquals(1, kickHandler.outcomes());

    SessionFixture disconnected = new SessionFixture();
    UworldFlowSession disconnectSession = disconnected.enter(
        new UworldRuntimeTestSupport.HandlerProbe());
    disconnected.runtime.onDisconnect(disconnected.player.player());

    assertEquals(UworldOutcomeType.DISCONNECTED,
        disconnectSession.completion().toCompletableFuture().join().type());
    assertEquals(0, disconnected.player.disconnects());
  }

  @Test
  void failAndCancelAreIdempotentTerminalOperations() {
    SessionFixture failed = new SessionFixture();
    UworldFlowSession failedSession = failed.enter(new UworldRuntimeTestSupport.HandlerProbe());
    assertTrue(failedSession.fail(net.kyori.adventure.text.Component.text("failed")));
    assertFalse(failedSession.cancel(net.kyori.adventure.text.Component.text("late")));
    assertEquals(UworldOutcomeType.FAILED,
        failedSession.completion().toCompletableFuture().join().type());

    SessionFixture cancelled = new SessionFixture();
    UworldFlowSession cancelledSession = cancelled.enter(
        new UworldRuntimeTestSupport.HandlerProbe());
    assertTrue(cancelledSession.cancel(net.kyori.adventure.text.Component.text("cancelled")));
    assertFalse(cancelledSession.fail(net.kyori.adventure.text.Component.text("late")));
    assertEquals(UworldOutcomeType.CANCELLED,
        cancelledSession.completion().toCompletableFuture().join().type());
  }

  private static final class SessionFixture {
    private final UworldRuntimeTestSupport.FactoryProbe factory =
        new UworldRuntimeTestSupport.FactoryProbe();
    private final UworldRuntimeTestSupport.ManualScheduler scheduler =
        new UworldRuntimeTestSupport.ManualScheduler();
    private final UworldRuntimeTestSupport.PlayerProbe player =
        new UworldRuntimeTestSupport.PlayerProbe("flow-player");
    private final UworldRuntimeTestSupport.CorePlayerProbe corePlayers =
        new UworldRuntimeTestSupport.CorePlayerProbe();
    private final EmbeddedUworldRuntime runtime = new EmbeddedUworldRuntime(
        this.factory.factory(),
        this.scheduler,
        (current, action) -> action.run(),
        this.corePlayers,
        () -> { });
    private final UworldHandle world = this.runtime.createWorld(
        "starx.test", UworldSpec.defaults("test"), current -> { });

    SessionFixture() {
      this.player.routeDisconnectsTo(this.runtime);
    }

    UworldFlowSession enter(UworldRuntimeTestSupport.HandlerProbe handler) {
      return assertInstanceOf(UworldEnterResult.Accepted.class,
          this.world.enter(this.player.player(), UworldFlowOptions.defaults(), handler)).session();
    }
  }
}
