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

import io.github.addxiaoyi.starx.chunk.Dimension;
import io.github.addxiaoyi.starx.player.GameMode;
import java.util.Objects;

public record UworldSpec(
    String name,
    Dimension dimension,
    double spawnX,
    double spawnY,
    double spawnZ,
    float yaw,
    float pitch,
    GameMode gameMode,
    int viewDistance,
    int simulationDistance,
    int readTimeoutMillis,
    long worldTime
) {

  private static final int MIN_DISTANCE = 1;
  private static final int MAX_DISTANCE = 32;
  private static final float MIN_PITCH = -90.0f;
  private static final float MAX_PITCH = 90.0f;

  public UworldSpec {
    name = Objects.requireNonNull(name, "name").trim();
    if (name.isEmpty()) {
      throw new IllegalArgumentException("Uworld name is blank");
    }

    Objects.requireNonNull(dimension, "dimension");
    Objects.requireNonNull(gameMode, "gameMode");
    requireFinite(spawnX, "spawnX");
    requireFinite(spawnY, "spawnY");
    requireFinite(spawnZ, "spawnZ");
    requireFinite(yaw, "yaw");
    requireFinite(pitch, "pitch");

    if (pitch < MIN_PITCH || pitch > MAX_PITCH) {
      throw new IllegalArgumentException("pitch must be -90..90");
    }
    requireDistance(viewDistance, "viewDistance");
    requireDistance(simulationDistance, "simulationDistance");
    if (readTimeoutMillis <= 0) {
      throw new IllegalArgumentException("readTimeoutMillis must be positive");
    }
    if (worldTime < 0) {
      throw new IllegalArgumentException("worldTime must be non-negative");
    }
  }

  public static UworldSpec defaults(String name) {
    return new UworldSpec(
        name,
        Dimension.OVERWORLD,
        0.5,
        100.0,
        0.5,
        0.0f,
        0.0f,
        GameMode.ADVENTURE,
        4,
        4,
        30_000,
        6_000L);
  }

  public UworldSpec withViewDistance(int value) {
    return new UworldSpec(
        name,
        dimension,
        spawnX,
        spawnY,
        spawnZ,
        yaw,
        pitch,
        gameMode,
        value,
        simulationDistance,
        readTimeoutMillis,
        worldTime);
  }

  public UworldSpec withSimulationDistance(int value) {
    return new UworldSpec(
        name,
        dimension,
        spawnX,
        spawnY,
        spawnZ,
        yaw,
        pitch,
        gameMode,
        viewDistance,
        value,
        readTimeoutMillis,
        worldTime);
  }

  private static void requireDistance(int value, String field) {
    if (value < MIN_DISTANCE || value > MAX_DISTANCE) {
      throw new IllegalArgumentException(field + " must be 1..32");
    }
  }

  private static void requireFinite(double value, String field) {
    if (!Double.isFinite(value)) {
      throw new IllegalArgumentException(field + " must be finite");
    }
  }
}
