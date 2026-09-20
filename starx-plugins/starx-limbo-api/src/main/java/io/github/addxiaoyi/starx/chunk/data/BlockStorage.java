/*
 * Copyright (C) 2021 - 2025 Elytrium
 *
 * The LimboAPI (excluding the LimboAPI plugin) is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package io.github.addxiaoyi.starx.chunk.data;

import com.velocitypowered.api.network.ProtocolVersion;
import io.github.addxiaoyi.starx.chunk.VirtualBlock;
import org.checkerframework.checker.nullness.qual.NonNull;

public interface BlockStorage {

  void write(Object byteBufObject, ProtocolVersion version, int pass);

  void set(int posX, int posY, int posZ, @NonNull VirtualBlock block);

  @NonNull
  VirtualBlock get(int posX, int posY, int posZ);

  int getDataLength(ProtocolVersion version);

  BlockStorage copy();

  static int index(int posX, int posY, int posZ) {
    return posY << 8 | posZ << 4 | posX;
  }
}
