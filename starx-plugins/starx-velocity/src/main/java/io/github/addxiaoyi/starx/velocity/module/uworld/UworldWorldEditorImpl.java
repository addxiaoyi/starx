package io.github.addxiaoyi.starx.velocity.module.uworld;

import io.github.addxiaoyi.starx.LimboFactory;
import io.github.addxiaoyi.starx.chunk.BuiltInBiome;
import io.github.addxiaoyi.starx.chunk.VirtualBlock;
import io.github.addxiaoyi.starx.chunk.VirtualWorld;
import io.github.addxiaoyi.starx.file.BuiltInWorldFileType;
import io.github.addxiaoyi.starx.limbo.material.Biome;
import io.github.addxiaoyi.starx.uworld.UworldWorldEditor;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

final class UworldWorldEditorImpl implements UworldWorldEditor {

  private static final int MIN_LIGHT = 0;
  private static final int MAX_LIGHT = 15;

  private final LimboFactory factory;
  private final VirtualWorld world;
  private final ReentrantLock lock = new ReentrantLock();
  private boolean sealed;

  UworldWorldEditorImpl(LimboFactory factory, VirtualWorld world) {
    this.factory = Objects.requireNonNull(factory, "factory");
    this.world = Objects.requireNonNull(world, "world");
  }

  @Override
  public VirtualBlock createBlock(String modernId) {
    return this.mutate(() -> this.factory.createSimpleBlock(
        Objects.requireNonNull(modernId, "modernId")));
  }

  @Override
  public void setBlock(int x, int y, int z, VirtualBlock block) {
    this.mutate(() -> this.world.setBlock(x, y, z, Objects.requireNonNull(block, "block")));
  }

  @Override
  public void setBiome(int x, int y, int z, BuiltInBiome biome) {
    this.mutate(() -> this.world.setBiome3d(
        x,
        y,
        z,
        Biome.of(Objects.requireNonNull(biome, "biome"))));
  }

  @Override
  public void fillSkyLight(int level) {
    this.mutate(() -> this.world.fillSkyLight(validateLight(level)));
  }

  @Override
  public void fillBlockLight(int level) {
    this.mutate(() -> this.world.fillBlockLight(validateLight(level)));
  }

  @Override
  public void load(
      BuiltInWorldFileType type,
      Path path,
      int offsetX,
      int offsetY,
      int offsetZ
  ) throws IOException {
    this.lock.lock();
    try {
      this.requireOpen();
      this.factory.openWorldFile(
          Objects.requireNonNull(type, "type"),
          Objects.requireNonNull(path, "path"))
          .toWorld(this.factory, this.world, offsetX, offsetY, offsetZ);
    } finally {
      this.lock.unlock();
    }
  }

  @Override
  public boolean isSealed() {
    this.lock.lock();
    try {
      return this.sealed;
    } finally {
      this.lock.unlock();
    }
  }

  void seal() {
    this.lock.lock();
    try {
      this.sealed = true;
    } finally {
      this.lock.unlock();
    }
  }

  private void mutate(Runnable action) {
    this.lock.lock();
    try {
      this.requireOpen();
      action.run();
    } finally {
      this.lock.unlock();
    }
  }

  private <T> T mutate(SupplierWithResult<T> action) {
    this.lock.lock();
    try {
      this.requireOpen();
      return action.get();
    } finally {
      this.lock.unlock();
    }
  }

  private void requireOpen() {
    if (this.sealed) {
      throw new IllegalStateException("Uworld editor is sealed");
    }
  }

  private static int validateLight(int level) {
    if (level < MIN_LIGHT || level > MAX_LIGHT) {
      throw new IllegalArgumentException("light level must be 0..15");
    }
    return level;
  }

  @FunctionalInterface
  private interface SupplierWithResult<T> {
    T get();
  }
}
