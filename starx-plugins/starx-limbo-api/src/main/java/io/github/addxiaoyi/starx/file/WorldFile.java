/*
 * Copyright (C) 2021 - 2025 Elytrium
 *
 * The LimboAPI (excluding the LimboAPI plugin) is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package io.github.addxiaoyi.starx.file;

import io.github.addxiaoyi.starx.LimboFactory;
import io.github.addxiaoyi.starx.chunk.VirtualWorld;
import org.checkerframework.common.value.qual.IntRange;

public interface WorldFile {

  default void toWorld(LimboFactory factory, VirtualWorld world, int offsetX, int offsetY, int offsetZ) {
    this.toWorld(factory, world, offsetX, offsetY, offsetZ, 15);
  }

  void toWorld(LimboFactory factory, VirtualWorld world, int offsetX, int offsetY, int offsetZ, @IntRange(from = 0, to = 15) int lightLevel);
}
