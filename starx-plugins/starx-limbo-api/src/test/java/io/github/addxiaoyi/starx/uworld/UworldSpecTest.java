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

import io.github.addxiaoyi.starx.chunk.Dimension;
import io.github.addxiaoyi.starx.player.GameMode;
import org.junit.jupiter.api.Test;

final class UworldSpecTest {

  @Test
  void defaultsUseTheSupportedRuntimeValues() {
    UworldSpec spec = UworldSpec.defaults(" tutorial ");

    assertAll(
        () -> assertEquals("tutorial", spec.name()),
        () -> assertEquals(Dimension.OVERWORLD, spec.dimension()),
        () -> assertEquals(0.5, spec.spawnX()),
        () -> assertEquals(100.0, spec.spawnY()),
        () -> assertEquals(0.5, spec.spawnZ()),
        () -> assertEquals(0.0f, spec.yaw()),
        () -> assertEquals(0.0f, spec.pitch()),
        () -> assertEquals(GameMode.SURVIVAL, spec.gameMode()),
        () -> assertEquals(4, spec.viewDistance()),
        () -> assertEquals(4, spec.simulationDistance()),
        () -> assertEquals(30_000, spec.readTimeoutMillis()),
        () -> assertEquals(6_000L, spec.worldTime()));
  }

  @Test
  void rejectsMissingOrBlankNames() {
    assertAll(
        () -> assertThrows(NullPointerException.class, () -> UworldSpec.defaults(null)),
        () -> assertThrows(IllegalArgumentException.class, () -> UworldSpec.defaults("")),
        () -> assertThrows(IllegalArgumentException.class, () -> UworldSpec.defaults(" \t ")));
  }

  @Test
  void rejectsMissingEnums() {
    UworldSpec base = UworldSpec.defaults("tutorial");

    assertAll(
        () -> assertThrows(NullPointerException.class, () -> new UworldSpec(
            base.name(), null,
            base.spawnX(), base.spawnY(), base.spawnZ(), base.yaw(), base.pitch(),
            base.gameMode(), base.viewDistance(), base.simulationDistance(),
            base.readTimeoutMillis(), base.worldTime())),
        () -> assertThrows(NullPointerException.class, () -> new UworldSpec(
            base.name(), base.dimension(),
            base.spawnX(), base.spawnY(), base.spawnZ(), base.yaw(), base.pitch(),
            null, base.viewDistance(), base.simulationDistance(),
            base.readTimeoutMillis(), base.worldTime())));
  }

  @Test
  void rejectsNonFiniteCoordinatesAndAngles() {
    assertAll(
        () -> assertThrows(IllegalArgumentException.class,
            () -> specWithPose(Double.NaN, 100.0, 0.5, 0.0f, 0.0f)),
        () -> assertThrows(IllegalArgumentException.class,
            () -> specWithPose(0.5, Double.POSITIVE_INFINITY, 0.5, 0.0f, 0.0f)),
        () -> assertThrows(IllegalArgumentException.class,
            () -> specWithPose(0.5, 100.0, Double.NEGATIVE_INFINITY, 0.0f, 0.0f)),
        () -> assertThrows(IllegalArgumentException.class,
            () -> specWithPose(0.5, 100.0, 0.5, Float.NaN, 0.0f)),
        () -> assertThrows(IllegalArgumentException.class,
            () -> specWithPose(0.5, 100.0, 0.5, 0.0f, Float.POSITIVE_INFINITY)));
  }

  @Test
  void acceptsPitchLimitsAndRejectsValuesOutsideThem() {
    assertAll(
        () -> assertDoesNotThrow(() -> specWithPose(0.5, 100.0, 0.5, 0.0f, -90.0f)),
        () -> assertDoesNotThrow(() -> specWithPose(0.5, 100.0, 0.5, 0.0f, 90.0f)),
        () -> assertThrows(IllegalArgumentException.class,
            () -> specWithPose(0.5, 100.0, 0.5, 0.0f, -90.01f)),
        () -> assertThrows(IllegalArgumentException.class,
            () -> specWithPose(0.5, 100.0, 0.5, 0.0f, 90.01f)));
  }

  @Test
  void validatesDistancesAndReadTimeout() {
    UworldSpec base = UworldSpec.defaults("tutorial");

    assertAll(
        () -> assertDoesNotThrow(() -> base.withViewDistance(1)),
        () -> assertDoesNotThrow(() -> base.withViewDistance(32)),
        () -> assertThrows(IllegalArgumentException.class, () -> base.withViewDistance(0)),
        () -> assertThrows(IllegalArgumentException.class, () -> base.withViewDistance(33)),
        () -> assertDoesNotThrow(() -> base.withSimulationDistance(1)),
        () -> assertDoesNotThrow(() -> base.withSimulationDistance(32)),
        () -> assertThrows(IllegalArgumentException.class, () -> base.withSimulationDistance(0)),
        () -> assertThrows(IllegalArgumentException.class, () -> base.withSimulationDistance(33)),
        () -> assertThrows(IllegalArgumentException.class, () -> specWithTiming(0, 6_000L)),
        () -> assertThrows(IllegalArgumentException.class, () -> specWithTiming(-1, 6_000L)));
  }

  @Test
  void rejectsNegativeWorldTime() {
    assertThrows(IllegalArgumentException.class, () -> specWithTiming(30_000, -1L));
  }

  private static UworldSpec specWithPose(
      double spawnX,
      double spawnY,
      double spawnZ,
      float yaw,
      float pitch
  ) {
    UworldSpec base = UworldSpec.defaults("tutorial");
    return new UworldSpec(
        base.name(), base.dimension(), spawnX, spawnY, spawnZ, yaw, pitch,
        base.gameMode(), base.viewDistance(), base.simulationDistance(),
        base.readTimeoutMillis(), base.worldTime());
  }

  private static UworldSpec specWithTiming(int readTimeoutMillis, long worldTime) {
    UworldSpec base = UworldSpec.defaults("tutorial");
    return new UworldSpec(
        base.name(), base.dimension(),
        base.spawnX(), base.spawnY(), base.spawnZ(), base.yaw(), base.pitch(),
        base.gameMode(), base.viewDistance(), base.simulationDistance(),
        readTimeoutMillis, worldTime);
  }
}
