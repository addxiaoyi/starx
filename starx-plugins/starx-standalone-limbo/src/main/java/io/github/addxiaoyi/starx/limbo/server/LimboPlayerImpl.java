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

package io.github.addxiaoyi.starx.limbo.server;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.client.ClientConfigSessionHandler;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.packet.LegacyPlayerListItemPacket;
import com.velocitypowered.proxy.protocol.packet.UpsertPlayerInfoPacket;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import io.github.addxiaoyi.starx.limbo.LimboAPI;
import io.github.addxiaoyi.starx.Limbo;
import io.github.addxiaoyi.starx.material.Item;
import io.github.addxiaoyi.starx.material.VirtualItem;
import io.github.addxiaoyi.starx.player.GameMode;
import io.github.addxiaoyi.starx.player.LimboPlayer;
import io.github.addxiaoyi.starx.uworld.UworldTransferPlayer;
import io.github.addxiaoyi.starx.protocol.item.ItemComponentMap;
import io.github.addxiaoyi.starx.protocol.packets.data.AbilityFlags;
import io.github.addxiaoyi.starx.protocol.packets.data.MapData;
import io.github.addxiaoyi.starx.protocol.packets.data.MapPalette;
import io.github.addxiaoyi.starx.limbo.injection.login.LoginTasksQueue;
import io.github.addxiaoyi.starx.limbo.protocol.packets.s2c.ChangeGameStatePacket;
import io.github.addxiaoyi.starx.limbo.protocol.packets.s2c.MapDataPacket;
import io.github.addxiaoyi.starx.limbo.protocol.packets.s2c.PlayerAbilitiesPacket;
import io.github.addxiaoyi.starx.limbo.protocol.packets.s2c.PositionRotationPacket;
import io.github.addxiaoyi.starx.limbo.protocol.packets.s2c.SetSlotPacket;
import io.github.addxiaoyi.starx.limbo.protocol.packets.s2c.TimeUpdatePacket;
import io.github.addxiaoyi.starx.limbo.server.world.SimpleItem;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.IntBinaryTag;
import net.kyori.adventure.text.Component;

public class LimboPlayerImpl implements LimboPlayer, UworldTransferPlayer {

  private final LimboAPI plugin;
  private final LimboImpl server;
  private final ConnectedPlayer player;
  private final MinecraftConnection connection;
  private final LimboSessionHandlerImpl sessionHandler;
  private final ProtocolVersion version;

  private GameMode gameMode = GameMode.ADVENTURE;

  public LimboPlayerImpl(LimboAPI plugin, LimboImpl server, ConnectedPlayer player) {
    this.plugin = plugin;
    this.server = server;
    this.player = player;

    this.connection = this.player.getConnection();
    this.sessionHandler = (LimboSessionHandlerImpl) this.connection.getActiveSessionHandler();
    this.version = this.player.getProtocolVersion();
  }

  @Override
  public void writePacket(Object packetObj) {
    this.connection.delayedWrite(packetObj);
  }

  @Override
  public void writePacketAndFlush(Object packetObj) {
    this.connection.write(packetObj);
  }

  @Override
  public void flushPackets() {
    this.connection.flush();
  }

  @Override
  public void closeWith(Object packetObj) {
    this.connection.closeWith(packetObj);
  }

  @Override
  public ScheduledExecutorService getScheduledExecutor() {
    return this.connection.eventLoop();
  }

  @Override
  public void sendImage(BufferedImage image) {
    this.sendImage(0, image, true, true);
  }

  @Override
  public void sendImage(BufferedImage image, boolean sendItem) {
    this.sendImage(0, image, sendItem, true);
  }

  @Override
  public void sendImage(int mapID, BufferedImage image) {
    this.sendImage(mapID, image, true, true);
  }

  @Override
  public void sendImage(int mapID, BufferedImage image, boolean sendItem) {
    this.sendImage(mapID, image, sendItem, true);
  }

  @Override
  public void sendImage(int mapID, BufferedImage image, boolean sendItem, boolean resize) {
    if (sendItem) {
      if (this.version.noLessThan(ProtocolVersion.MINECRAFT_1_20_5)) {
        this.setInventory(36, SimpleItem.fromItem(Item.FILLED_MAP), 1, mapID,
            this.plugin.createItemComponentMap().add(ProtocolVersion.MINECRAFT_1_20_5, "minecraft:map_id", mapID));
      } else if (this.version.noLessThan(ProtocolVersion.MINECRAFT_1_17)) {
        this.setInventory(36, SimpleItem.fromItem(Item.FILLED_MAP), 1, mapID,
            CompoundBinaryTag.builder().put("map", IntBinaryTag.intBinaryTag(mapID)).build());
      } else {
        this.setInventory(36, SimpleItem.fromItem(Item.FILLED_MAP), 1, mapID, (CompoundBinaryTag) null);
      }
    }

    if (image.getWidth() != MapData.MAP_DIM_SIZE || image.getHeight() != MapData.MAP_DIM_SIZE) {
      if (resize) {
        BufferedImage resizedImage = new BufferedImage(MapData.MAP_DIM_SIZE, MapData.MAP_DIM_SIZE, image.getType());

        Graphics2D graphics = resizedImage.createGraphics();
        graphics.drawImage(image.getScaledInstance(MapData.MAP_DIM_SIZE, MapData.MAP_DIM_SIZE, Image.SCALE_SMOOTH), 0, 0, null);
        graphics.dispose();

        image = resizedImage;
      } else {
        throw new IllegalStateException(
            "You either need to provide an image of " + MapData.MAP_DIM_SIZE + "x" + MapData.MAP_DIM_SIZE
                + " pixels or set the resize parameter to true so that API will automatically resize your image."
        );
      }
    }

    int[] toWrite = MapPalette.imageToBytes(image, this.version);
    if (this.version.compareTo(ProtocolVersion.MINECRAFT_1_8) < 0) {
      byte[][] canvas = new byte[MapData.MAP_DIM_SIZE][MapData.MAP_DIM_SIZE];
      for (int i = 0; i < MapData.MAP_SIZE; ++i) {
        canvas[i & 127][i >> 7] = (byte) toWrite[i];
      }

      for (int i = 0; i < MapData.MAP_DIM_SIZE; ++i) {
        this.writePacket(new MapDataPacket(mapID, (byte) 0, new MapData(i, canvas[i])));
      }

      this.flushPackets();
    } else {
      byte[] canvas = new byte[MapData.MAP_SIZE];
      for (int i = 0; i < MapData.MAP_SIZE; ++i) {
        canvas[i] = (byte) toWrite[i];
      }

      this.writePacketAndFlush(new MapDataPacket(mapID, (byte) 0, new MapData(canvas)));
    }
  }

  @Override
  public void setInventory(VirtualItem item, int count) {
    this.writePacketAndFlush(new SetSlotPacket(0, 36, item, count, 0, null, null));
  }

  @Override
  public void setInventory(VirtualItem item, int slot, int count) {
    this.writePacketAndFlush(new SetSlotPacket(0, slot, item, count, 0, null, null));
  }

  @Override
  public void setInventory(int slot, VirtualItem item, int count, int data, CompoundBinaryTag nbt) {
    this.writePacketAndFlush(new SetSlotPacket(0, slot, item, count, data, nbt, null));
  }

  @Override
  public void setInventory(int slot, VirtualItem item, int count, int data, ItemComponentMap map) {
    this.writePacketAndFlush(new SetSlotPacket(0, slot, item, count, data, null, map));
  }

  @Override
  public void setGameMode(GameMode gameMode) {
    boolean is17 = this.version.compareTo(ProtocolVersion.MINECRAFT_1_8) < 0;
    if (gameMode != GameMode.SPECTATOR || !is17) { // Spectator game mode was added in 1.8.
      this.gameMode = gameMode;

      int id = this.gameMode.getID();
      this.sendAbilities();
      if (!is17) {
        UUID uuid = this.plugin.getInitialID(this.player);
        if (this.connection.getProtocolVersion().compareTo(ProtocolVersion.MINECRAFT_1_19_1) <= 0) {
          this.writePacket(
              new LegacyPlayerListItemPacket(LegacyPlayerListItemPacket.UPDATE_GAMEMODE,
                  List.of(
                      new LegacyPlayerListItemPacket.Item(uuid).setGameMode(id)
                  )
              )
          );
        } else {
          UpsertPlayerInfoPacket.Entry playerInfoEntry = new UpsertPlayerInfoPacket.Entry(uuid);
          playerInfoEntry.setGameMode(id);

          this.writePacket(new UpsertPlayerInfoPacket(EnumSet.of(UpsertPlayerInfoPacket.Action.UPDATE_GAME_MODE), List.of(playerInfoEntry)));
        }
      }
      this.writePacket(new ChangeGameStatePacket(3, id));

      this.flushPackets();
    }
  }

  @Override
  public void teleport(double posX, double posY, double posZ, float yaw, float pitch) {
    this.writePacketAndFlush(new PositionRotationPacket(posX, posY, posZ, yaw, pitch, false, 44, true));
  }

  @Override
  public void disableFalling() {
    this.writePacketAndFlush(new PlayerAbilitiesPacket((byte) (this.getAbilities() | AbilityFlags.FLYING | AbilityFlags.ALLOW_FLYING), 0F, 0F));
  }

  @Override
  public void enableFalling() {
    this.writePacketAndFlush(new PlayerAbilitiesPacket((byte) (this.getAbilities() & (~AbilityFlags.FLYING)), 0.05F, 0.1F));
  }

  @Override
  public void disconnect() {
    this.connection.eventLoop().execute(() -> {
      if (this.connection.getActiveSessionHandler() == this.sessionHandler) {
        this.sessionHandler.disconnect(() -> {
          boolean hasLoginQueue = this.plugin.hasLoginQueue(this.player);
          if (loginQueueOwnsConfigTransition(hasLoginQueue, this.version)) {
            this.plugin.getLoginQueue(this.player).next();
          } else if (hasLoginQueue) {
            this.sessionHandler.disconnected();
            this.plugin.getLoginQueue(this.player).next();
          } else {
            RegisteredServer server = this.sessionHandler.getPreviousServer();
            if (server != null) {
              this.sendToRegisteredServer(server);
            } else {
              this.sessionHandler.disconnected();
            }
          }
        });
      }
    });
  }

  @Override
  public void disconnect(RegisteredServer server) {
    this.transferTo(server);
  }

  @Override
  public CompletionStage<Boolean> transferTo(RegisteredServer server) {
    return this.transferResultTo(server)
        .thenApply(result -> result.status() == UworldTransferPlayer.Status.STARTED);
  }

  @Override
  public CompletionStage<UworldTransferPlayer.TransferResult> transferResultTo(
      RegisteredServer server
  ) {
    Objects.requireNonNull(server, "server");
    CompletableFuture<UworldTransferPlayer.TransferResult> transfer = new CompletableFuture<>();
    try {
      this.connection.eventLoop().execute(() -> {
        try {
          if (this.connection.getActiveSessionHandler() != this.sessionHandler) {
            transfer.completeExceptionally(new IllegalStateException(
                "Limbo session is no longer active"));
            return;
          }
          this.sessionHandler.disconnect(() -> {
            try {
              boolean hasLoginQueue = this.plugin.hasLoginQueue(this.player);
              if (loginQueueOwnsConfigTransition(hasLoginQueue, this.version)) {
                this.plugin.setNextServer(this.player, server);
                this.plugin.getLoginQueue(this.player).next();
                transfer.complete(UworldTransferPlayer.TransferResult.started());
              } else if (hasLoginQueue) {
                this.sessionHandler.disconnected();
                this.plugin.setNextServer(this.player, server);
                this.plugin.getLoginQueue(this.player).next();
                transfer.complete(UworldTransferPlayer.TransferResult.started());
              } else {
                this.sendToRegisteredServer(server, transfer);
              }
            } catch (RuntimeException error) {
              transfer.completeExceptionally(error);
            }
          });
        } catch (RuntimeException error) {
          transfer.completeExceptionally(error);
        }
      });
    } catch (RuntimeException error) {
      transfer.completeExceptionally(error);
    }
    return transfer;
  }

  static boolean loginQueueOwnsConfigTransition(
      boolean hasLoginQueue,
      ProtocolVersion version
  ) {
    Objects.requireNonNull(version, "version");
    return hasLoginQueue && version.compareTo(ProtocolVersion.MINECRAFT_1_20_2) >= 0;
  }

  private void deject() {
    this.plugin.deject3rdParty(this.connection.getChannel().pipeline());
    this.plugin.fixCompressor(this.connection.getChannel().pipeline(), this.version);
  }

  private void sendToRegisteredServer(RegisteredServer server) {
    this.sendToRegisteredServer(server, new CompletableFuture<>());
  }

  private void sendToRegisteredServer(
      RegisteredServer server,
      CompletableFuture<UworldTransferPlayer.TransferResult> transfer
  ) {
    this.deject();
    this.connection.setState(StateRegistry.PLAY);

    if (this.connection.getProtocolVersion().compareTo(ProtocolVersion.MINECRAFT_1_20_2) >= 0) {
      this.sessionHandler.disconnectToConfig(() -> {
        // Rollback original CONFIG handler
        ClientConfigSessionHandler handler = new ClientConfigSessionHandler(this.plugin.getServer(), this.player);
        LoginTasksQueue.BRAND_CHANNEL_SETTER.accept(handler, "minecraft:brand");
        this.connection.setActiveSessionHandler(StateRegistry.CONFIG, handler);
        this.completeTransfer(this.player.createConnectionRequest(server).connect(), transfer);
      });
    } else {
      if (this.connection.getProtocolVersion().compareTo(ProtocolVersion.MINECRAFT_1_19_1) <= 0) {
        this.connection.delayedWrite(new LegacyPlayerListItemPacket(
            LegacyPlayerListItemPacket.REMOVE_PLAYER,
            List.of(new LegacyPlayerListItemPacket.Item(this.plugin.getInitialID(this.player)))
        ));
      }

      this.sessionHandler.disconnected();
      this.completeTransfer(this.player.createConnectionRequest(server).connect(), transfer);
    }
  }

  private void completeTransfer(
      CompletableFuture<com.velocitypowered.api.proxy.ConnectionRequestBuilder.Result> connection,
      CompletableFuture<UworldTransferPlayer.TransferResult> transfer
  ) {
    connection.whenComplete((result, error) -> {
      if (error != null) {
        transfer.completeExceptionally(error);
        return;
      }
      if (result == null) {
        transfer.complete(UworldTransferPlayer.TransferResult.failed());
        return;
      }
      if (result.isSuccessful()) {
        transfer.complete(UworldTransferPlayer.TransferResult.started());
        return;
      }
      if (result.getStatus()
          == com.velocitypowered.api.proxy.ConnectionRequestBuilder.Status.SERVER_DISCONNECTED) {
        transfer.complete(UworldTransferPlayer.TransferResult.kicked(
            result.getReasonComponent().orElse(Component.text("子服拒绝了 Uworld 转服"))));
        return;
      }
      transfer.complete(UworldTransferPlayer.TransferResult.failed());
    });
  }

  @Override
  public void sendAbilities() {
    this.writePacketAndFlush(new PlayerAbilitiesPacket(this.getAbilities(), 0.05F, 0.1F));
  }

  @Override
  public void sendAbilities(int abilities, float flySpeed, float walkSpeed) {
    this.writePacketAndFlush(new PlayerAbilitiesPacket((byte) abilities, flySpeed, walkSpeed));
  }

  @Override
  public void sendAbilities(byte abilities, float flySpeed, float walkSpeed) {
    this.writePacketAndFlush(new PlayerAbilitiesPacket(abilities, flySpeed, walkSpeed));
  }

  @Override
  public byte getAbilities() {
    return switch (this.gameMode) {
      case CREATIVE -> AbilityFlags.ALLOW_FLYING | AbilityFlags.CREATIVE_MODE | AbilityFlags.INVULNERABLE;
      case SPECTATOR -> AbilityFlags.ALLOW_FLYING | AbilityFlags.INVULNERABLE | AbilityFlags.FLYING;
      default -> 0;
    };
  }

  @Override
  public GameMode getGameMode() {
    return this.gameMode;
  }

  @Override
  public Limbo getServer() {
    return this.server;
  }

  @Override
  public Player getProxyPlayer() {
    return this.player;
  }

  @Override
  public int getPing() {
    LimboSessionHandlerImpl handler = (LimboSessionHandlerImpl) this.connection.getActiveSessionHandler();
    if (handler != null) {
      return handler.getPing();
    } else {
      return -1;
    }
  }

  @Override
  public void setWorldTime(long ticks) {
    this.writePacketAndFlush(new TimeUpdatePacket(ticks, ticks));
  }
}
