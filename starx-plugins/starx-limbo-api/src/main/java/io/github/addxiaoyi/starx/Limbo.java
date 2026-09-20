/*
 * Copyright (C) 2021 - 2025 Elytrium
 *
 * The LimboAPI (excluding the LimboAPI plugin) is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package io.github.addxiaoyi.starx;

import com.velocitypowered.api.command.Command;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.proxy.Player;
import java.util.function.Supplier;
import io.github.addxiaoyi.starx.command.LimboCommandMeta;
import io.github.addxiaoyi.starx.player.GameMode;
import io.github.addxiaoyi.starx.protocol.PacketDirection;
import io.github.addxiaoyi.starx.protocol.packets.PacketMapping;

public interface Limbo {

  void spawnPlayer(Player player, LimboSessionHandler handler);

  void respawnPlayer(Player player);

  long getCurrentOnline();

  Limbo setName(String name);

  Limbo setReadTimeout(int millis);

  Limbo setWorldTime(long ticks);

  Limbo setGameMode(GameMode gameMode);

  Limbo setShouldRejoin(boolean shouldRejoin);

  Limbo setShouldRespawn(boolean shouldRespawn);

  @Deprecated
  Limbo setShouldUpdateTags(boolean shouldUpdateTags);

  Limbo setReducedDebugInfo(boolean reducedDebugInfo);

  Limbo setViewDistance(int viewDistance);

  Limbo setSimulationDistance(int simulationDistance);

  Limbo setMaxSuppressPacketLength(int maxSuppressPacketLength);

  Limbo registerCommand(LimboCommandMeta commandMeta);

  Limbo registerCommand(CommandMeta commandMeta, Command command);

  Limbo registerPacket(PacketDirection direction, Class<?> packetClass, Supplier<?> packetSupplier, PacketMapping[] packetMappings);

  void dispose();
}
