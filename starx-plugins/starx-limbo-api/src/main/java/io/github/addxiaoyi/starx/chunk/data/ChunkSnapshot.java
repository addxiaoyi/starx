/*
 * Copyright (C) 2021 - 2025 Elytrium
 *
 * The LimboAPI (excluding the LimboAPI plugin) is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package io.github.addxiaoyi.starx.chunk.data;

import java.util.List;
import io.github.addxiaoyi.starx.chunk.VirtualBiome;
import io.github.addxiaoyi.starx.chunk.VirtualBlock;
import io.github.addxiaoyi.starx.chunk.VirtualBlockEntity;

public interface ChunkSnapshot {

  VirtualBlock getBlock(int posX, int posY, int posZ);

  int getPosX();

  int getPosZ();

  boolean isFullChunk();

  BlockSection[] getSections();

  LightSection[] getLight();

  VirtualBiome[] getBiomes();

  List<VirtualBlockEntity.Entry> getBlockEntityEntries();
}
