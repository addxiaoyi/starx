/*
 * Copyright (C) 2021 - 2025 Elytrium
 *
 * The LimboAPI (excluding the LimboAPI plugin) is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package io.github.addxiaoyi.starx.chunk.data;

import io.github.addxiaoyi.starx.chunk.VirtualBlock;
import org.checkerframework.checker.nullness.qual.Nullable;

public interface BlockSection {

  void setBlockAt(int posX, int posY, int posZ, @Nullable VirtualBlock block);

  VirtualBlock getBlockAt(int posX, int posY, int posZ);

  BlockSection getSnapshot();

  long getLastUpdate();
}
