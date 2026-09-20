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

package io.github.addxiaoyi.starx.uworld;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

final class UworldValueTypesTest {

  @Test
  void flowDefaultsUseTheAuthenticationAndTransferLimits() {
    UworldFlowOptions options = UworldFlowOptions.defaults();

    assertAll(
        () -> assertEquals(Duration.ofMinutes(5), options.activeTimeout()),
        () -> assertEquals(Duration.ofSeconds(15), options.transferTimeout()));
  }

  @Test
  void flowTimeoutsMustBePresentAndPositive() {
    Duration second = Duration.ofSeconds(1);

    assertAll(
        () -> assertThrows(NullPointerException.class,
            () -> new UworldFlowOptions(null, second)),
        () -> assertThrows(NullPointerException.class,
            () -> new UworldFlowOptions(second, null)),
        () -> assertThrows(IllegalArgumentException.class,
            () -> new UworldFlowOptions(Duration.ZERO, second)),
        () -> assertThrows(IllegalArgumentException.class,
            () -> new UworldFlowOptions(second, Duration.ofNanos(-1))));
  }

  @Test
  void enterResultsRejectMissingMembers() {
    assertAll(
        () -> assertThrows(NullPointerException.class,
            () -> new UworldEnterResult.Accepted(null)),
        () -> assertThrows(NullPointerException.class,
            () -> new UworldEnterResult.Rejected(null, Component.empty())),
        () -> assertThrows(NullPointerException.class,
            () -> new UworldEnterResult.Rejected(UworldEnterStatus.PLAYER_BUSY, null)));
  }

  @Test
  void outcomesRejectMissingMembers() {
    assertAll(
        () -> assertThrows(NullPointerException.class,
            () -> new UworldOutcome(null, Component.empty(), Optional.empty())),
        () -> assertThrows(NullPointerException.class,
            () -> new UworldOutcome(UworldOutcomeType.CANCELLED, null, Optional.empty())),
        () -> assertThrows(NullPointerException.class,
            () -> new UworldOutcome(UworldOutcomeType.CANCELLED, Component.empty(), null)));
  }

  @Test
  void defaultHandlerCallbacksAreNoOps() {
    UworldFlowHandler handler = new UworldFlowHandler() {};

    assertAll(
        () -> assertDoesNotThrow(() -> handler.onReady(null)),
        () -> assertDoesNotThrow(() -> handler.onChat(null, "hello")),
        () -> assertDoesNotThrow(() -> handler.onMove(null, 1.0, 2.0, 3.0)),
        () -> assertDoesNotThrow(() -> handler.onRotate(null, 45.0f, 10.0f)),
        () -> assertDoesNotThrow(() -> handler.onGround(null, true)),
        () -> assertDoesNotThrow(() -> handler.onTeleport(null, 7)),
        () -> assertDoesNotThrow(() -> handler.onGeneric(null, new Object())),
        () -> assertDoesNotThrow(() -> handler.onOutcome(null, null)));
  }

  @Test
  void synchronousCloseDelegatesToAsyncClose() {
    AtomicInteger closes = new AtomicInteger();
    UworldHandle handle = new UworldHandle() {
      @Override
      public String name() {
        return "tutorial";
      }

      @Override
      public boolean isOpen() {
        return true;
      }

      @Override
      public UworldEnterResult enter(
          com.velocitypowered.api.proxy.Player player,
          UworldFlowOptions options,
          UworldFlowHandler handler
      ) {
        throw new UnsupportedOperationException();
      }

      @Override
      public CompletionStage<Void> closeAsync(Component reason) {
        assertEquals(Component.text("Uworld closed"), reason);
        closes.incrementAndGet();
        return CompletableFuture.completedFuture(null);
      }
    };

    handle.close();

    assertEquals(1, closes.get());
  }

  @Test
  void creationErrorsCarryOwnerAndWorldContext() {
    UworldCreationException error =
        new UworldCreationException("auth", "login", "already registered");

    assertTrue(error.getMessage().contains("owner=auth"));
    assertTrue(error.getMessage().contains("world=login"));
    assertTrue(error.getMessage().contains("already registered"));
  }
}
