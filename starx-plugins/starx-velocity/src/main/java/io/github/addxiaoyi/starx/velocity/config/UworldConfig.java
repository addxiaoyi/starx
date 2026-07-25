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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package io.github.addxiaoyi.starx.velocity.config;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record UworldConfig(
    boolean enabled,
    int transferTimeoutSeconds,
    Auth auth,
    Diagnostics diagnostics
) {

  private static final int DEFAULT_TRANSFER_TIMEOUT_SECONDS = 15;

  public UworldConfig {
    requirePositive(transferTimeoutSeconds, "transferTimeoutSeconds");
    auth = Objects.requireNonNull(auth, "auth");
    diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
  }

  public static UworldConfig defaults() {
    return new UworldConfig(
        true,
        DEFAULT_TRANSFER_TIMEOUT_SECONDS,
        Auth.defaults(),
        Diagnostics.defaults());
  }

  public record Auth(int timeoutSeconds, String targetServer, World world) {

    private static final int DEFAULT_TIMEOUT_SECONDS = 300;

    public Auth {
      requirePositive(timeoutSeconds, "auth.timeoutSeconds");
      targetServer = targetServer == null ? "lobby" : targetServer.trim();
      if (targetServer.isEmpty()) {
        targetServer = "lobby";
      }
      world = Objects.requireNonNull(world, "world");
    }

    public static Auth defaults() {
      return new Auth(DEFAULT_TIMEOUT_SECONDS, "lobby", World.defaults());
    }
  }

  public record World(
      String dimension,
      double spawnX,
      double spawnY,
      double spawnZ,
      float spawnYaw,
      float spawnPitch,
      String gameMode,
      String loaderType,
      String fileName,
      int offsetX,
      int offsetY,
      int offsetZ,
      int viewDistance,
      int simulationDistance,
      int platformRadius
  ) {

    private static final int MIN_DISTANCE = 1;
    private static final int MAX_DISTANCE = 32;
    private static final int MIN_PLATFORM_RADIUS = 1;
    private static final int MAX_PLATFORM_RADIUS = 64;
    private static final Set<String> LOADERS = Set.of(
        "AUTO",
        "VOID",
        "SCHEMATIC",
        "WORLDEDIT_SCHEM",
        "STRUCTURE");

    public World {
      dimension = normalizeName(dimension, "dimension");
      gameMode = normalizeName(gameMode, "gameMode");
      loaderType = normalizeName(loaderType, "loaderType");
      fileName = normalizeFileName(fileName);

      if (!LOADERS.contains(loaderType)) {
        throw new IllegalArgumentException(
            "loaderType must be AUTO, VOID, SCHEMATIC, WORLDEDIT_SCHEM, or STRUCTURE");
      }
      requireFinite(spawnX, "spawnX");
      requireFinite(spawnY, "spawnY");
      requireFinite(spawnZ, "spawnZ");
      requireFinite(spawnYaw, "spawnYaw");
      requireFinite(spawnPitch, "spawnPitch");
      requireRange(viewDistance, MIN_DISTANCE, MAX_DISTANCE, "viewDistance");
      requireRange(
          simulationDistance,
          MIN_DISTANCE,
          MAX_DISTANCE,
          "simulationDistance");
      requireRange(
          platformRadius,
          MIN_PLATFORM_RADIUS,
          MAX_PLATFORM_RADIUS,
          "platformRadius");
    }

    public static World defaults() {
      return new World(
          "OVERWORLD",
          0.5,
          100.0,
          0.5,
          0.0f,
          0.0f,
          "SURVIVAL",
          "AUTO",
          "auth_world.schem",
          0,
          0,
          0,
          4,
          4,
          5);
    }
  }

  public record Diagnostics(boolean enabled, int timeoutSeconds, int platformRadius) {

    private static final int DEFAULT_TIMEOUT_SECONDS = 120;

    public Diagnostics {
      requirePositive(timeoutSeconds, "diagnostics.timeoutSeconds");
      requireRange(platformRadius, 1, 64, "diagnostics.platformRadius");
    }

    public static Diagnostics defaults() {
      return new Diagnostics(false, DEFAULT_TIMEOUT_SECONDS, 5);
    }
  }

  private static String normalizeName(String value, String field) {
    String normalized = Objects.requireNonNull(value, field)
        .trim()
        .toUpperCase(Locale.ROOT);
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " is blank");
    }
    return normalized;
  }

  private static String normalizeFileName(String value) {
    String fileName = Objects.requireNonNull(value, "fileName").trim();
    if (fileName.isEmpty()) {
      throw new IllegalArgumentException("fileName is blank");
    }

    Path path;
    try {
      path = Path.of(fileName);
    } catch (InvalidPathException error) {
      throw new IllegalArgumentException("fileName is not a valid path", error);
    }
    if (path.isAbsolute() || path.getRoot() != null) {
      throw new IllegalArgumentException("fileName must be a relative path");
    }
    if (path.normalize().startsWith("..")) {
      throw new IllegalArgumentException("fileName must stay within the StarX data directory");
    }
    return fileName;
  }

  private static void requireFinite(double value, String field) {
    if (!Double.isFinite(value)) {
      throw new IllegalArgumentException(field + " must be finite");
    }
  }

  private static void requirePositive(int value, String field) {
    if (value <= 0) {
      throw new IllegalArgumentException(field + " must be positive");
    }
  }

  private static void requireRange(int value, int min, int max, String field) {
    if (value < min || value > max) {
      throw new IllegalArgumentException(field + " must be " + min + ".." + max);
    }
  }
}
