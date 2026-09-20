/*
 * Copyright (C) 2021 - 2025 Elytrium
 *
 * The LimboAPI (excluding the LimboAPI plugin) is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package io.github.addxiaoyi.starx.chunk;

import com.velocitypowered.api.network.ProtocolVersion;
import io.github.addxiaoyi.starx.material.WorldVersion;

public interface VirtualBlock {

  short getModernID();

  String getModernStringID();

  @Deprecated
  short getID(ProtocolVersion version);

  short getBlockID(WorldVersion version);

  short getBlockID(ProtocolVersion version);

  boolean isSupportedOn(ProtocolVersion version);

  boolean isSupportedOn(WorldVersion version);

  short getBlockStateID(ProtocolVersion version);

  boolean isSolid();

  boolean isAir();

  boolean isMotionBlocking();
}
