/*
 * Copyright (C) 2021 - 2025 Elytrium
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

package io.github.addxiaoyi.starx.limbo.injection.packet;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.connection.backend.BackendPlaySessionHandler;
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.packet.LegacyPlayerListItemPacket;
import io.netty.util.collection.IntObjectMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.elytrium.commons.utils.reflection.ReflectionException;
import io.github.addxiaoyi.starx.limbo.LimboAPI;
import io.github.addxiaoyi.starx.limbo.protocol.LimboProtocol;

@SuppressWarnings("unchecked")
public class LegacyPlayerListItemHook extends LegacyPlayerListItemPacket {

  private static final MethodHandle SERVER_CONN_FIELD;

  private final LimboAPI plugin;

  private LegacyPlayerListItemHook(LimboAPI plugin) {
    this.plugin = plugin;
  }

  @Override
  public boolean handle(MinecraftSessionHandler handler) {
    if (handler instanceof BackendPlaySessionHandler) {
      try {
        ConnectedPlayer player = ((VelocityServerConnection) SERVER_CONN_FIELD.invokeExact((BackendPlaySessionHandler) handler)).getPlayer();
        rewriteItems(
            this.getItems(),
            player.getUniqueId(),
            this.plugin.getInitialID(player));
      } catch (Throwable e) {
        throw new ReflectionException(e);
      }
    }

    return super.handle(handler);
  }

  static void rewriteItems(List<Item> items, UUID playerId, UUID initialId) {
    if (initialId == null) {
      return;
    }
    for (int i = 0; i < items.size(); ++i) {
      Item item = items.get(i);
      if (!playerId.equals(item.getUuid())) {
        continue;
      }
      items.set(i, new Item(initialId)
          .setDisplayName(item.getDisplayName())
          .setGameMode(item.getGameMode())
          .setLatency(item.getLatency())
          .setName(item.getName())
          .setProperties(item.getProperties()));
    }
  }

  static {
    try {
      SERVER_CONN_FIELD = MethodHandles.privateLookupIn(BackendPlaySessionHandler.class, MethodHandles.lookup())
          .findGetter(BackendPlaySessionHandler.class, "serverConn", VelocityServerConnection.class);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new ReflectionException(e);
    }
  }

  public static void init(LimboAPI plugin, StateRegistry.PacketRegistry registry) throws ReflectiveOperationException {
    // See LimboProtocol#overlayRegistry about var.
    var playProtocolRegistryVersions = (Map<ProtocolVersion, StateRegistry.PacketRegistry.ProtocolRegistry>) LimboProtocol.VERSIONS_FIELD.get(registry);
    playProtocolRegistryVersions.forEach((protocolVersion, protocolRegistry) -> {
      try {
        var packetIDToSupplier = (IntObjectMap<Supplier<? extends MinecraftPacket>>) LimboProtocol.PACKET_ID_TO_SUPPLIER_FIELD.get(protocolRegistry);
        var packetClassToID = (Object2IntMap<Class<? extends MinecraftPacket>>) LimboProtocol.PACKET_CLASS_TO_ID_FIELD.get(protocolRegistry);

        int id = packetClassToID.getInt(LegacyPlayerListItemPacket.class);
        packetClassToID.put(LegacyPlayerListItemHook.class, id);
        packetIDToSupplier.put(id, () -> new LegacyPlayerListItemHook(plugin));
      } catch (ReflectiveOperationException e) {
        throw new ReflectionException(e);
      }
    });
  }
}
