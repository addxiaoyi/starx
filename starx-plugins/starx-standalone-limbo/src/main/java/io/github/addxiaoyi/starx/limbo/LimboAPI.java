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

package io.github.addxiaoyi.starx.limbo;

import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.natives.compression.VelocityCompressor;
import com.velocitypowered.natives.util.Natives;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.network.Connections;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.netty.MinecraftCompressDecoder;
import com.velocitypowered.proxy.protocol.netty.MinecraftCompressorAndLengthEncoder;
import com.velocitypowered.proxy.protocol.netty.MinecraftDecoder;
import com.velocitypowered.proxy.protocol.netty.MinecraftEncoder;
import com.velocitypowered.proxy.protocol.netty.MinecraftVarintLengthEncoder;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.elytrium.limboapi.thirdparty.commons.config.YamlConfig;
import net.elytrium.limboapi.thirdparty.commons.kyori.serialization.Serializer;
import net.elytrium.limboapi.thirdparty.commons.kyori.serialization.Serializers;
import net.elytrium.commons.utils.reflection.ReflectionException;
import net.elytrium.limboapi.thirdparty.fastprepare.PreparedPacketFactory;
import net.elytrium.limboapi.thirdparty.fastprepare.handler.PreparedPacketEncoder;
import io.github.addxiaoyi.starx.Limbo;
import io.github.addxiaoyi.starx.LimboFactory;
import io.github.addxiaoyi.starx.chunk.BuiltInBiome;
import io.github.addxiaoyi.starx.chunk.Dimension;
import io.github.addxiaoyi.starx.chunk.VirtualBiome;
import io.github.addxiaoyi.starx.chunk.VirtualBlock;
import io.github.addxiaoyi.starx.chunk.VirtualBlockEntity;
import io.github.addxiaoyi.starx.chunk.VirtualChunk;
import io.github.addxiaoyi.starx.chunk.VirtualWorld;
import io.github.addxiaoyi.starx.file.BuiltInWorldFileType;
import io.github.addxiaoyi.starx.file.WorldFile;
import io.github.addxiaoyi.starx.material.Block;
import io.github.addxiaoyi.starx.material.Item;
import io.github.addxiaoyi.starx.material.VirtualItem;
import io.github.addxiaoyi.starx.protocol.PreparedPacket;
import io.github.addxiaoyi.starx.protocol.item.ItemComponentMap;
import io.github.addxiaoyi.starx.protocol.packets.PacketFactory;
import io.github.addxiaoyi.starx.limbo.file.WorldFileTypeRegistry;
import io.github.addxiaoyi.starx.limbo.injection.event.EventManagerHook;
import io.github.addxiaoyi.starx.limbo.injection.login.LoginListener;
import io.github.addxiaoyi.starx.limbo.injection.login.LoginTasksQueue;
import io.github.addxiaoyi.starx.limbo.injection.packet.LegacyPlayerListItemHook;
import io.github.addxiaoyi.starx.limbo.injection.packet.LimboCompressDecoder;
import io.github.addxiaoyi.starx.limbo.injection.packet.MinecraftDiscardCompressDecoder;
import io.github.addxiaoyi.starx.limbo.injection.packet.MinecraftLimitedCompressDecoder;
import io.github.addxiaoyi.starx.limbo.injection.packet.PreparedPacketImpl;
import io.github.addxiaoyi.starx.limbo.injection.packet.RemovePlayerInfoHook;
import io.github.addxiaoyi.starx.limbo.injection.packet.UpsertPlayerInfoHook;
import io.github.addxiaoyi.starx.limbo.material.Biome;
import io.github.addxiaoyi.starx.limbo.protocol.LimboProtocol;
import io.github.addxiaoyi.starx.limbo.protocol.packets.PacketFactoryImpl;
import io.github.addxiaoyi.starx.limbo.server.CachedPackets;
import io.github.addxiaoyi.starx.limbo.server.LimboImpl;
import io.github.addxiaoyi.starx.limbo.server.item.SimpleItemComponentManager;
import io.github.addxiaoyi.starx.limbo.server.item.SimpleItemComponentMap;
import io.github.addxiaoyi.starx.limbo.server.world.SimpleBlock;
import io.github.addxiaoyi.starx.limbo.server.world.SimpleBlockEntity;
import io.github.addxiaoyi.starx.limbo.server.world.SimpleItem;
import io.github.addxiaoyi.starx.limbo.server.world.SimpleTagManager;
import io.github.addxiaoyi.starx.limbo.server.world.SimpleWorld;
import io.github.addxiaoyi.starx.limbo.server.world.chunk.SimpleChunk;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.ComponentSerializer;
import org.slf4j.Logger;

public class LimboAPI implements LimboFactory {

  private static final int SUPPORTED_MAXIMUM_PROTOCOL_VERSION_NUMBER = 776;

  private static Logger LOGGER;
  private static Serializer SERIALIZER;

  public static final ConcurrentHashMap<Player, UUID> INITIAL_ID = new ConcurrentHashMap<>();

  private static final MethodHandle STATE_FIELD;
  private static LimboAPI coreOwner;

  private final VelocityServer server;
  private final File configFile;
  private final LimboPlayerState<Player, LoginTasksQueue, Function<KickedFromServerEvent, Boolean>, RegisteredServer>
      playerState;
  private final CachedPackets packets;
  private final PacketFactory packetFactory;
  private final SimpleItemComponentManager itemComponentManager = new SimpleItemComponentManager();

  private PreparedPacketFactory preparedPacketFactory;
  private PreparedPacketFactory configPreparedPacketFactory;
  private PreparedPacketFactory loginUncompressedPreparedPacketFactory;
  private PreparedPacketFactory loginPreparedPacketFactory;
  private ProtocolVersion minVersion;
  private ProtocolVersion maxVersion;
  private LoginListener loginListener;
  private boolean compressionEnabled;
  private EventManagerHook eventManagerHook;
  private Object eventOwner;
  private volatile boolean initialized;

  protected LimboAPI(Logger logger, ProxyServer server, Path dataDirectory) {
    setLogger(Objects.requireNonNull(logger, "logger"));
    Objects.requireNonNull(dataDirectory, "dataDirectory");
    if (!(server instanceof VelocityServer velocityServer)) {
      throw new IllegalArgumentException("StarX Limbo requires the Velocity proxy implementation");
    }

    this.server = velocityServer;
    this.configFile = dataDirectory.resolve("core.yml").toFile();
    this.playerState = new LimboPlayerState<>();
    this.packetFactory = new PacketFactoryImpl();
    this.packets = new CachedPackets(this);

    int maximumProtocolVersionNumber = ProtocolVersion.MAXIMUM_VERSION.getProtocol();
    if (maximumProtocolVersionNumber < SUPPORTED_MAXIMUM_PROTOCOL_VERSION_NUMBER) {
      throw new IllegalStateException(
          "StarX Limbo requires Velocity protocol " + SUPPORTED_MAXIMUM_PROTOCOL_VERSION_NUMBER
              + " or newer, found " + maximumProtocolVersionNumber
      );
    } else if (maximumProtocolVersionNumber != SUPPORTED_MAXIMUM_PROTOCOL_VERSION_NUMBER) {
      LOGGER.warn("StarX Limbo was built for protocol {}, but Velocity reports {}",
          SUPPORTED_MAXIMUM_PROTOCOL_VERSION_NUMBER, maximumProtocolVersionNumber);
    }

    synchronized (LimboAPI.class) {
      if (coreOwner != null) {
        throw new IllegalStateException("StarX Limbo core is already initialized");
      }
      coreOwner = this;
      try {
        LOGGER.info("Initializing StarX virtual world core");
        SimpleBlock.init();
        SimpleBlockEntity.init();
        SimpleItem.init();
        SimpleTagManager.init();
        LegacyPlayerListItemHook.init(this, LimboProtocol.PLAY_CLIENTBOUND_REGISTRY);
        UpsertPlayerInfoHook.init(this, LimboProtocol.PLAY_CLIENTBOUND_REGISTRY);
        RemovePlayerInfoHook.init(this, LimboProtocol.PLAY_CLIENTBOUND_REGISTRY);
        LimboProtocol.init();
      } catch (Throwable error) {
        coreOwner = null;
        throw new ReflectionException(error);
      }
    }
  }

  public synchronized void initialize(Object eventOwner) {
    if (this.initialized) {
      return;
    }
    this.eventOwner = Objects.requireNonNull(eventOwner, "eventOwner");
    Settings.IMP.setLogger(LOGGER);

    if (Settings.IMP.reload(this.configFile, Settings.IMP.PREFIX) == YamlConfig.LoadResult.CONFIG_NOT_EXISTS) {
      LOGGER.info("Created default StarX Limbo core configuration at {}", this.configFile);
    }

    int level = this.server.getConfiguration().getCompressionLevel();
    int threshold = this.server.getConfiguration().getCompressionThreshold();
    this.preparedPacketFactory = new PreparedPacketFactory(
        PreparedPacketImpl::new,
        LimboProtocol.getLimboStateRegistry(),
        this.compressionEnabled,
        level,
        threshold,
        Settings.IMP.MAIN.SAVE_UNCOMPRESSED_PACKETS,
        true,
        Settings.IMP.MAIN.COMPATIBILITY_MODE
    );
    this.configPreparedPacketFactory = new PreparedPacketFactory(
        PreparedPacketImpl::new,
        StateRegistry.CONFIG,
        this.compressionEnabled,
        level,
        threshold,
        Settings.IMP.MAIN.SAVE_UNCOMPRESSED_PACKETS,
        true,
        Settings.IMP.MAIN.COMPATIBILITY_MODE
    );
    this.loginUncompressedPreparedPacketFactory = new PreparedPacketFactory(
        PreparedPacketImpl::new,
        StateRegistry.LOGIN,
        false,
        level,
        threshold,
        false,
        true,
        Settings.IMP.MAIN.COMPATIBILITY_MODE
    );
    this.loginPreparedPacketFactory = new PreparedPacketFactory(
        PreparedPacketImpl::new,
        StateRegistry.LOGIN,
        this.compressionEnabled,
        level,
        threshold,
        Settings.IMP.MAIN.SAVE_UNCOMPRESSED_PACKETS,
        true,
        Settings.IMP.MAIN.COMPATIBILITY_MODE
    );
    this.reloadPreparedPacketFactory();
    try {
      this.reload();
      this.initialized = true;
    } catch (RuntimeException error) {
      this.eventOwner = null;
      throw error;
    }
  }

  public synchronized void postInitialize() {
    // Login-stage hooks are intentionally disabled for the embedded runtime.
  }

  public synchronized void reload() {
    if (this.eventOwner == null) {
      throw new IllegalStateException("StarX Limbo has no event owner");
    }
    Settings.IMP.reload(this.configFile, Settings.IMP.PREFIX);
    ComponentSerializer<Component, Component, String> serializer = Settings.IMP.SERIALIZER.getSerializer();
    if (serializer == null) {
      LOGGER.warn("The specified serializer could not be founded, using default. (LEGACY_AMPERSAND)");
      setSerializer(new Serializer(Objects.requireNonNull(Serializers.LEGACY_AMPERSAND.getSerializer())));
    } else {
      setSerializer(new Serializer(serializer));
    }

    LOGGER.info("Creating and preparing packets...");
    this.reloadVersion();
    this.packets.createPackets();

    LOGGER.info("StarX Limbo core loaded");
  }

  public synchronized void close() {
    boolean hasActivePlayers = this.playerState.joinedCount() != 0;
    if (hasActivePlayers) {
      LOGGER.warn("StarX Limbo is closing with {} active players; shared packet cleanup is deferred",
          this.playerState.joinedCount());
    } else {
      this.packets.dispose();
      this.eventOwner = null;
    }
    this.playerState.clear();
    INITIAL_ID.clear();
    this.initialized = false;
    // Static registries remain process-owned; supported reloads restart the Velocity JVM.
  }

  public boolean isInitialized() {
    return this.initialized;
  }

  public Object getSchedulerOwner() {
    Object owner = this.eventOwner;
    if (owner == null) {
      throw new IllegalStateException("StarX Limbo is not initialized");
    }
    return owner;
  }

  private void reloadVersion() {
    if (Settings.IMP.MAIN.PREPARE_MAX_VERSION.equals("LATEST")) {
      this.maxVersion = ProtocolVersion.MAXIMUM_VERSION;
    } else {
      this.maxVersion = ProtocolVersion.valueOf("MINECRAFT_" + Settings.IMP.MAIN.PREPARE_MAX_VERSION);
    }

    this.minVersion = ProtocolVersion.valueOf("MINECRAFT_" + Settings.IMP.MAIN.PREPARE_MIN_VERSION);

    if (ProtocolVersion.MAXIMUM_VERSION.compareTo(this.maxVersion) > 0 || ProtocolVersion.MINIMUM_VERSION.compareTo(this.minVersion) < 0) {
      LOGGER.warn(
          "Currently working only with "
              + this.minVersion.getVersionIntroducedIn() + " - " + this.maxVersion.getMostRecentSupportedVersion()
              + " versions; update limbo/core.yml to allow other protocol versions."
      );
    }
  }

  public void reloadPreparedPacketFactory() {
    int level = this.server.getConfiguration().getCompressionLevel();
    int threshold = this.server.getConfiguration().getCompressionThreshold();
    this.compressionEnabled = threshold != -1;

    this.preparedPacketFactory.updateCompressor(this.compressionEnabled, level, threshold,
        Settings.IMP.MAIN.SAVE_UNCOMPRESSED_PACKETS, Settings.IMP.MAIN.COMPATIBILITY_MODE);
    this.configPreparedPacketFactory.updateCompressor(this.compressionEnabled, level, threshold,
        Settings.IMP.MAIN.SAVE_UNCOMPRESSED_PACKETS, Settings.IMP.MAIN.COMPATIBILITY_MODE);
    this.loginPreparedPacketFactory.updateCompressor(this.compressionEnabled, level, threshold,
        Settings.IMP.MAIN.SAVE_UNCOMPRESSED_PACKETS, Settings.IMP.MAIN.COMPATIBILITY_MODE);
  }

  @Override
  public VirtualBlock createSimpleBlock(Block block) {
    return SimpleBlock.fromLegacyID((short) block.getID());
  }

  @Override
  public VirtualBlock createSimpleBlock(short legacyID) {
    return SimpleBlock.fromLegacyID(legacyID);
  }

  @Override
  public VirtualBlock createSimpleBlock(String modernID) {
    return SimpleBlock.fromModernID(modernID);
  }

  @Override
  public VirtualBlock createSimpleBlock(String modernID, Map<String, String> properties) {
    return SimpleBlock.fromModernID(modernID, properties);
  }

  @Override
  public VirtualBlock createSimpleBlock(short id, boolean modern) {
    if (modern) {
      return SimpleBlock.solid(id);
    } else {
      return SimpleBlock.fromLegacyID(id);
    }
  }

  @Override
  public VirtualBlock createSimpleBlock(boolean solid, boolean air, boolean motionBlocking, short id) {
    return new SimpleBlock(solid, air, motionBlocking, id);
  }

  @Override
  public VirtualBlock createSimpleBlock(boolean solid, boolean air, boolean motionBlocking, String modernID, Map<String, String> properties) {
    return new SimpleBlock(solid, air, motionBlocking, modernID, properties);
  }

  @Override
  public VirtualWorld createVirtualWorld(Dimension dimension, double posX, double posY, double posZ, float yaw, float pitch) {
    return new SimpleWorld(dimension, posX, posY, posZ, yaw, pitch);
  }

  @Override
  public VirtualChunk createVirtualChunk(int posX, int posZ) {
    return new SimpleChunk(posX, posZ);
  }

  @Override
  public VirtualChunk createVirtualChunk(int posX, int posZ, VirtualBiome defaultBiome) {
    return new SimpleChunk(posX, posZ, defaultBiome);
  }

  @Override
  public VirtualChunk createVirtualChunk(int posX, int posZ, BuiltInBiome defaultBiome) {
    return new SimpleChunk(posX, posZ, Biome.of(defaultBiome));
  }

  @Override
  public Limbo createLimbo(VirtualWorld world) {
    return new LimboImpl(this, world);
  }

  @Override
  public void releasePreparedPacketThread(Thread thread) {
    this.preparedPacketFactory.releaseThread(thread);
  }

  @Override
  public PreparedPacket createPreparedPacket() {
    return (PreparedPacket) this.preparedPacketFactory.createPreparedPacket(this.minVersion, this.maxVersion);
  }

  @Override
  public PreparedPacket createPreparedPacket(ProtocolVersion minVersion, ProtocolVersion maxVersion) {
    return (PreparedPacket) this.preparedPacketFactory.createPreparedPacket(minVersion, maxVersion);
  }

  @Override
  public PreparedPacket createConfigPreparedPacket() {
    return (PreparedPacket) this.configPreparedPacketFactory.createPreparedPacket(this.minVersion, this.maxVersion);
  }

  @Override
  public PreparedPacket createConfigPreparedPacket(ProtocolVersion minVersion, ProtocolVersion maxVersion) {
    return (PreparedPacket) this.configPreparedPacketFactory.createPreparedPacket(minVersion, maxVersion);
  }

  public ByteBuf encodeSingleLogin(MinecraftPacket packet, ProtocolVersion version) {
    return this.loginPreparedPacketFactory.encodeSingle(packet, version);
  }

  public ByteBuf encodeSingleLoginUncompressed(MinecraftPacket packet, ProtocolVersion version) {
    return this.loginUncompressedPreparedPacketFactory.encodeSingle(packet, version);
  }

  public void inject3rdParty(Player player, MinecraftConnection connection, ChannelPipeline pipeline) {
    StateRegistry state = connection.getState();
    if (connection.getProtocolVersion().compareTo(ProtocolVersion.MINECRAFT_1_20_2) < 0
        || (state != StateRegistry.CONFIG && state != StateRegistry.LOGIN)) {
      this.preparedPacketFactory.inject(player, connection, pipeline);
    } else {
      this.configPreparedPacketFactory.inject(player, connection, pipeline);
    }
  }

  public void setState(MinecraftConnection connection, StateRegistry stateRegistry) {
    connection.setState(stateRegistry);
    this.setEncoderState(connection, stateRegistry);
    this.fixDecoderState(connection, stateRegistry);
  }

  public void setActiveSessionHandler(MinecraftConnection connection, StateRegistry stateRegistry,
                                      MinecraftSessionHandler sessionHandler) {
    connection.setActiveSessionHandler(stateRegistry, sessionHandler);
    this.setEncoderState(connection, stateRegistry);
    this.fixDecoderState(connection, stateRegistry);
  }

  public void setEncoderState(MinecraftConnection connection, StateRegistry state) {
    // As CONFIG state was added in 1.20.2, no need to track it for lower versions
    if (connection.getProtocolVersion().compareTo(ProtocolVersion.MINECRAFT_1_20_2) < 0) {
      return;
    }

    if (Settings.IMP.MAIN.COMPATIBILITY_MODE) {
      MinecraftEncoder encoder = connection.getChannel().pipeline().get(MinecraftEncoder.class);
      if (encoder != null) {
        encoder.setState(state);
      }
    }

    PreparedPacketEncoder encoder = connection.getChannel().pipeline().get(PreparedPacketEncoder.class);
    if (encoder != null) {
      if (state != StateRegistry.CONFIG && state != StateRegistry.LOGIN) {
        encoder.setFactory(this.preparedPacketFactory);
      } else {
        encoder.setFactory(this.configPreparedPacketFactory);
      }
    }
  }

  public void fixDecoderState(MinecraftConnection connection, StateRegistry state) {
    if (state.name() == null) { // custom state
      MinecraftDecoder decoder = connection.getChannel().pipeline().get(MinecraftDecoder.class);
      if (decoder != null) {
        try {
          // Let decoder know what we're in PLAY state, or it will kick the player.
          STATE_FIELD.invokeExact(decoder, StateRegistry.PLAY);
        } catch (Throwable throwable) {
          LimboAPI.getLogger().error("Failed to fixup decoder", throwable);
        }
      }
    }
  }

  public void deject3rdParty(ChannelPipeline pipeline) {
    this.preparedPacketFactory.deject(pipeline);
  }

  public void fixDecompressor(ChannelPipeline pipeline, int threshold, boolean onLogin) {
    ChannelHandler decoder;
    if (onLogin && Settings.IMP.MAIN.DISCARD_COMPRESSION_ON_LOGIN) {
      decoder = new MinecraftDiscardCompressDecoder();
    } else if (!onLogin && Settings.IMP.MAIN.DISCARD_COMPRESSION_AFTER_LOGIN) {
      decoder = new MinecraftDiscardCompressDecoder();
    } else {
      int level = this.server.getConfiguration().getCompressionLevel();
      VelocityCompressor compressor = Natives.compress.get().create(level);
      decoder = new MinecraftLimitedCompressDecoder(threshold, compressor);
    }

    if (Settings.IMP.MAIN.COMPATIBILITY_MODE && pipeline.context(Connections.COMPRESSION_DECODER) != null) {
      pipeline.replace(Connections.COMPRESSION_DECODER, Connections.COMPRESSION_DECODER, decoder);
    } else {
      pipeline.addBefore(Connections.MINECRAFT_DECODER, Connections.COMPRESSION_DECODER, decoder);
    }
  }

  public void fixCompressor(ChannelPipeline pipeline, ProtocolVersion version) {
    ChannelHandler compressionHandler = pipeline.get(Connections.COMPRESSION_ENCODER);
    if (compressionHandler == null) {
      if (!Settings.IMP.MAIN.COMPATIBILITY_MODE) {
        pipeline.addBefore(Connections.MINECRAFT_DECODER, Connections.FRAME_ENCODER, MinecraftVarintLengthEncoder.INSTANCE);
      }
    } else {
      int level = this.server.getConfiguration().getCompressionLevel();
      int compressionThreshold = this.server.getConfiguration().getCompressionThreshold();
      VelocityCompressor compressor = Natives.compress.get().create(level);
      if (!Settings.IMP.MAIN.COMPATIBILITY_MODE) {
        MinecraftCompressorAndLengthEncoder encoder = new MinecraftCompressorAndLengthEncoder(compressionThreshold, compressor);
        pipeline.remove(compressionHandler);
        pipeline.addBefore(Connections.MINECRAFT_ENCODER, Connections.COMPRESSION_ENCODER, encoder);
      }

      if (pipeline.get(Connections.COMPRESSION_DECODER) instanceof LimboCompressDecoder) {
        MinecraftCompressDecoder decoder = new MinecraftCompressDecoder(compressionThreshold, compressor, ProtocolUtils.Direction.SERVERBOUND);
        pipeline.replace(Connections.COMPRESSION_DECODER, Connections.COMPRESSION_DECODER, decoder);
      } else if (Settings.IMP.MAIN.COMPATIBILITY_MODE) {
        compressor.close();
      }
    }
  }

  @Override
  public void passLoginLimbo(Player player) {
    LoginTasksQueue queue = this.playerState.loginQueue(player);
    if (queue != null) {
      queue.next();
    }
  }

  @Override
  public VirtualItem getItem(Item item) {
    return SimpleItem.fromItem(item);
  }

  @Override
  public VirtualItem getItem(String itemID) {
    return SimpleItem.fromModernID(itemID);
  }

  @Override
  public VirtualItem getLegacyItem(int itemLegacyID) {
    return SimpleItem.fromLegacyID(itemLegacyID);
  }

  @Override
  public ItemComponentMap createItemComponentMap() {
    return new SimpleItemComponentMap(this.itemComponentManager);
  }

  @Override
  public VirtualBlockEntity getBlockEntity(String entityID) {
    return SimpleBlockEntity.fromModernID(entityID);
  }

  @Override
  public PacketFactory getPacketFactory() {
    return this.packetFactory;
  }

  public VelocityServer getServer() {
    return this.server;
  }

  public void setLimboJoined(Player player) {
    this.playerState.join(player, () -> {
      ConnectedPlayer connectedPlayer = (ConnectedPlayer) player;
      connectedPlayer.getPhase().onFirstJoin(connectedPlayer);
    });
  }

  public void unsetLimboJoined(Player player) {
    this.playerState.leave(player);
  }

  public boolean isLimboJoined(Player player) {
    return this.playerState.isJoined(player);
  }

  public CachedPackets getPackets() {
    return this.packets;
  }

  public void addLoginQueue(Player player, LoginTasksQueue queue) {
    this.playerState.setLoginQueue(player, queue);
  }

  public void removeLoginQueue(Player player) {
    this.playerState.removeLoginQueue(player);
  }

  public boolean hasLoginQueue(Player player) {
    return this.playerState.loginQueue(player) != null;
  }

  public LoginTasksQueue getLoginQueue(Player player) {
    return this.playerState.loginQueue(player);
  }

  public void setKickCallback(Player player, Function<KickedFromServerEvent, Boolean> queue) {
    this.playerState.setKickCallback(player, queue);
  }

  public void removeKickCallback(Player player) {
    this.playerState.removeKickCallback(player);
  }

  public Function<KickedFromServerEvent, Boolean> getKickCallback(Player player) {
    return this.playerState.kickCallback(player);
  }

  public void setNextServer(Player player, RegisteredServer nextServer) {
    this.playerState.setNextServer(player, nextServer);
  }

  public void removeNextServer(Player player) {
    this.playerState.removeNextServer(player);
  }

  public boolean hasNextServer(Player player) {
    return this.playerState.hasNextServer(player);
  }

  public RegisteredServer getNextServer(Player player) {
    return this.playerState.nextServer(player);
  }

  public RegisteredServer takeNextServer(Player player) {
    return this.playerState.takeNextServer(player);
  }

  public void setInitialID(Player player, UUID nextServer) {
    INITIAL_ID.put(player, nextServer);
  }

  public void removeInitialID(Player player) {
    INITIAL_ID.remove(player);
  }

  public UUID getInitialID(Player player) {
    return INITIAL_ID.get(player);
  }

  public LoginListener getLoginListener() {
    return this.loginListener;
  }

  public boolean isCompressionEnabled() {
    return this.compressionEnabled;
  }

  public PreparedPacketFactory getPreparedPacketFactory() {
    return this.preparedPacketFactory;
  }

  public ProtocolVersion getPrepareMinVersion() {
    return this.minVersion;
  }

  public ProtocolVersion getPrepareMaxVersion() {
    return this.maxVersion;
  }

  public EventManagerHook getEventManagerHook() {
    return this.eventManagerHook;
  }

  @Override
  public WorldFile openWorldFile(BuiltInWorldFileType apiType, Path file) throws IOException {
    return WorldFileTypeRegistry.fromApiType(apiType, file);
  }

  @Override
  public WorldFile openWorldFile(BuiltInWorldFileType apiType, InputStream stream) throws IOException {
    return WorldFileTypeRegistry.fromApiType(apiType, stream);
  }

  @Override
  public WorldFile openWorldFile(BuiltInWorldFileType apiType, CompoundBinaryTag tag) {
    return WorldFileTypeRegistry.fromApiType(apiType, tag);
  }

  private static void setLogger(Logger logger) {
    LOGGER = logger;
  }

  public static Logger getLogger() {
    return LOGGER;
  }

  private static void setSerializer(Serializer serializer) {
    SERIALIZER = serializer;
  }

  public static Serializer getSerializer() {
    return SERIALIZER;
  }

  static {
    try {
      STATE_FIELD = MethodHandles.privateLookupIn(MinecraftDecoder.class, MethodHandles.lookup())
          .findSetter(MinecraftDecoder.class, "state", StateRegistry.class);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }
}
