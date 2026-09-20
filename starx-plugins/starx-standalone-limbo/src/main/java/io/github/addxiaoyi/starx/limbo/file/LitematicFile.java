/*
 * Copyright (C) 2025 StarX Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package io.github.addxiaoyi.starx.limbo.file;

import io.github.addxiaoyi.starx.LimboFactory;
import io.github.addxiaoyi.starx.chunk.VirtualBlock;
import io.github.addxiaoyi.starx.chunk.VirtualWorld;
import io.github.addxiaoyi.starx.file.WorldFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;

public final class LitematicFile implements WorldFile {

  static final int MAX_REGIONS = 1024;
  static final int MAX_DIMENSION = 4096;
  static final long MAX_BLOCK_VOLUME = 64L * 1024L * 1024L;
  static final int MAX_PALETTE_SIZE = 65_536;

  private final List<Region> regions;
  private final long totalVolume;
  private final int blockEntityCount;

  public LitematicFile(CompoundBinaryTag rootTag) {
    CompoundBinaryTag regionsTag = rootTag.getCompound("Regions");
    if (regionsTag.keySet().isEmpty()) {
      throw new IllegalArgumentException("Litematic file contains no regions");
    }
    if (regionsTag.size() > MAX_REGIONS) {
      throw new IllegalArgumentException(
          "Litematic region count exceeds " + MAX_REGIONS + ": " + regionsTag.size());
    }

    List<Region> parsed = new ArrayList<>(regionsTag.size());
    long volume = 0L;
    int blockEntities = 0;
    for (Map.Entry<String, ? extends BinaryTag> entry : regionsTag) {
      if (!(entry.getValue() instanceof CompoundBinaryTag regionTag)) {
        throw new IllegalArgumentException("Litematic region is not a compound: " + entry.getKey());
      }
      Region region = new Region(entry.getKey(), regionTag);
      volume = Math.addExact(volume, region.volume());
      if (volume > MAX_BLOCK_VOLUME) {
        throw new IllegalArgumentException(
            "Litematic total block volume exceeds " + MAX_BLOCK_VOLUME + ": " + volume);
      }
      blockEntities = Math.addExact(blockEntities, region.blockEntities().size());
      parsed.add(region);
    }
    this.regions = List.copyOf(parsed);
    this.totalVolume = volume;
    this.blockEntityCount = blockEntities;
  }

  @Override
  public void toWorld(
      LimboFactory factory,
      VirtualWorld world,
      int offsetX,
      int offsetY,
      int offsetZ,
      int lightLevel
  ) {
    for (Region region : this.regions) {
      region.toWorld(factory, world, offsetX, offsetY, offsetZ);
    }
    world.fillSkyLight(lightLevel);
  }

  int regionCount() {
    return this.regions.size();
  }

  long totalVolume() {
    return this.totalVolume;
  }

  int blockEntityCount() {
    return this.blockEntityCount;
  }

  Region region(int index) {
    return this.regions.get(index);
  }

  static final class Region {
    private final String name;
    private final int positionX;
    private final int positionY;
    private final int positionZ;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final int width;
    private final int height;
    private final int length;
    private final long volume;
    private final List<PaletteEntry> palette;
    private final long[] blockStates;
    private final int bitsPerBlock;
    private final long paletteMask;
    private final List<CompoundBinaryTag> blockEntities;

    Region(String name, CompoundBinaryTag tag) {
      this.name = name;
      CompoundBinaryTag position = tag.getCompound("Position");
      CompoundBinaryTag size = tag.getCompound("Size");
      this.positionX = position.getInt("x");
      this.positionY = position.getInt("y");
      this.positionZ = position.getInt("z");
      this.sizeX = requireSize(name, "x", size.getInt("x"));
      this.sizeY = requireSize(name, "y", size.getInt("y"));
      this.sizeZ = requireSize(name, "z", size.getInt("z"));
      this.width = Math.abs(this.sizeX);
      this.height = Math.abs(this.sizeY);
      this.length = Math.abs(this.sizeZ);
      this.volume = checkedVolume(name, this.width, this.height, this.length);

      ListBinaryTag paletteTag = tag.getList("BlockStatePalette");
      if (paletteTag.isEmpty()) {
        throw new IllegalArgumentException("Litematic region has an empty palette: " + name);
      }
      if (paletteTag.size() > MAX_PALETTE_SIZE) {
        throw new IllegalArgumentException(
            "Litematic palette exceeds " + MAX_PALETTE_SIZE + " entries in region " + name);
      }
      List<PaletteEntry> parsedPalette = new ArrayList<>(paletteTag.size());
      for (BinaryTag binaryTag : paletteTag) {
        if (!(binaryTag instanceof CompoundBinaryTag blockTag)) {
          throw new IllegalArgumentException(
              "Litematic palette entry is not a compound in region " + name);
        }
        String modernId = blockTag.getString("Name");
        if (modernId.isBlank()) {
          throw new IllegalArgumentException(
              "Litematic palette entry has no block name in region " + name);
        }
        Map<String, String> properties = new HashMap<>();
        CompoundBinaryTag propertiesTag = blockTag.getCompound("Properties");
        for (String key : propertiesTag.keySet()) {
          properties.put(key, propertiesTag.getString(key));
        }
        parsedPalette.add(new PaletteEntry(modernId, Map.copyOf(properties)));
      }
      this.palette = List.copyOf(parsedPalette);
      this.bitsPerBlock = Math.max(
          2,
          Integer.SIZE - Integer.numberOfLeadingZeros(this.palette.size() - 1));
      this.paletteMask = (1L << this.bitsPerBlock) - 1L;
      this.blockStates = tag.getLongArray("BlockStates");
      long expectedLongs = Math.addExact(
          Math.multiplyExact(this.volume, (long) this.bitsPerBlock),
          63L) / 64L;
      if (this.blockStates.length != expectedLongs) {
        throw new IllegalArgumentException(
            "Litematic BlockStates length mismatch in region " + name
                + ": expected=" + expectedLongs + " actual=" + this.blockStates.length);
      }

      ListBinaryTag entities = tag.getList("Entities");
      if (!entities.isEmpty()) {
        throw new IllegalArgumentException(
            "Litematic region " + name + " contains " + entities.size()
                + " ordinary entities; Uworld does not support entity import yet");
      }
      List<CompoundBinaryTag> parsedBlockEntities = new ArrayList<>();
      for (BinaryTag binaryTag : tag.getList("TileEntities")) {
        if (!(binaryTag instanceof CompoundBinaryTag blockEntity)) {
          throw new IllegalArgumentException(
              "Litematic block entity is not a compound in region " + name);
        }
        parsedBlockEntities.add(blockEntity);
      }
      this.blockEntities = List.copyOf(parsedBlockEntities);
    }

    private void toWorld(
        LimboFactory factory,
        VirtualWorld world,
        int offsetX,
        int offsetY,
        int offsetZ
    ) {
      VirtualBlock[] blocks = new VirtualBlock[this.palette.size()];
      for (int index = 0; index < blocks.length; index++) {
        PaletteEntry entry = this.palette.get(index);
        blocks[index] = factory.createSimpleBlock(entry.modernId(), entry.properties());
      }

      for (int localY = 0; localY < this.height; localY++) {
        for (int localZ = 0; localZ < this.length; localZ++) {
          for (int localX = 0; localX < this.width; localX++) {
            int paletteIndex = this.paletteIndex(localX, localY, localZ);
            VirtualBlock block = blocks[paletteIndex];
            world.setBlock(
                offsetX + this.worldX(localX),
                offsetY + this.worldY(localY),
                offsetZ + this.worldZ(localZ),
                block);
          }
        }
      }

      for (CompoundBinaryTag blockEntity : this.blockEntities) {
        int localX = blockEntity.getInt("x");
        int localY = blockEntity.getInt("y");
        int localZ = blockEntity.getInt("z");
        this.requireLocalCoordinate(localX, localY, localZ, "block entity");
        VirtualBlock block = blocks[this.paletteIndex(localX, localY, localZ)];
        world.setBlockEntity(
            offsetX + this.worldX(localX),
            offsetY + this.worldY(localY),
            offsetZ + this.worldZ(localZ),
            blockEntity,
            factory.getBlockEntity(block.getModernStringID()));
      }
    }

    int paletteIndex(int localX, int localY, int localZ) {
      this.requireLocalCoordinate(localX, localY, localZ, "block");
      long index = ((long) localY * this.length + localZ) * this.width + localX;
      long startOffset = index * this.bitsPerBlock;
      int startLong = Math.toIntExact(startOffset >>> 6);
      int endLong = Math.toIntExact(
          (((index + 1L) * this.bitsPerBlock) - 1L) >>> 6);
      int startBit = (int) (startOffset & 63L);
      long value = this.blockStates[startLong] >>> startBit;
      if (startLong != endLong) {
        value |= this.blockStates[endLong] << (64 - startBit);
      }
      int paletteIndex = (int) (value & this.paletteMask);
      if (paletteIndex < 0 || paletteIndex >= this.palette.size()) {
        throw new IllegalArgumentException(
            "Litematic palette index out of bounds in region " + this.name
                + ": " + paletteIndex + "/" + this.palette.size());
      }
      return paletteIndex;
    }

    int worldX(int localX) {
      return this.positionX + localCoordinate(this.sizeX, localX);
    }

    int worldY(int localY) {
      return this.positionY + localCoordinate(this.sizeY, localY);
    }

    int worldZ(int localZ) {
      return this.positionZ + localCoordinate(this.sizeZ, localZ);
    }

    int width() {
      return this.width;
    }

    int height() {
      return this.height;
    }

    int length() {
      return this.length;
    }

    long volume() {
      return this.volume;
    }

    List<PaletteEntry> palette() {
      return this.palette;
    }

    List<CompoundBinaryTag> blockEntities() {
      return this.blockEntities;
    }

    private void requireLocalCoordinate(int x, int y, int z, String label) {
      if (x < 0 || x >= this.width || y < 0 || y >= this.height || z < 0 || z >= this.length) {
        throw new IllegalArgumentException(
            "Litematic " + label + " coordinate outside region " + this.name
                + ": (" + x + "," + y + "," + z + ")");
      }
    }
  }

  record PaletteEntry(String modernId, Map<String, String> properties) {
  }

  private static int requireSize(String region, String axis, int signedSize) {
    if (signedSize == 0) {
      throw new IllegalArgumentException(
          "Litematic region " + region + " has zero " + axis + " size");
    }
    if (signedSize == Integer.MIN_VALUE || Math.abs(signedSize) > MAX_DIMENSION) {
      throw new IllegalArgumentException(
          "Litematic region " + region + " " + axis + " size exceeds "
              + MAX_DIMENSION + ": " + signedSize);
    }
    return signedSize;
  }

  private static long checkedVolume(String region, int width, int height, int length) {
    long volume = Math.multiplyExact(Math.multiplyExact((long) width, height), length);
    if (volume > MAX_BLOCK_VOLUME) {
      throw new IllegalArgumentException(
          "Litematic region " + region + " block volume exceeds "
              + MAX_BLOCK_VOLUME + ": " + volume);
    }
    return volume;
  }

  private static int localCoordinate(int signedSize, int storedCoordinate) {
    return signedSize < 0 ? signedSize + 1 + storedCoordinate : storedCoordinate;
  }
}
