package io.github.addxiaoyi.starx.velocity.network;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/** Cross-process non-blocking lock for FRP and ACME mutations. */
final class NetworkOperationLock implements AutoCloseable {
  private final FileChannel channel;
  private final FileLock lock;

  private NetworkOperationLock(FileChannel channel, FileLock lock) {
    this.channel = channel;
    this.lock = lock;
  }

  static Optional<NetworkOperationLock> tryAcquire(Path path) throws IOException {
    Path lockPath = path.toAbsolutePath().normalize();
    Path parent = lockPath.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    FileChannel channel = FileChannel.open(
        lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    try {
      FileLock lock = channel.tryLock();
      if (lock == null) {
        channel.close();
        return Optional.empty();
      }
      return Optional.of(new NetworkOperationLock(channel, lock));
    } catch (OverlappingFileLockException alreadyHeld) {
      channel.close();
      return Optional.empty();
    } catch (IOException | RuntimeException error) {
      channel.close();
      throw error;
    }
  }

  @Override
  public void close() throws IOException {
    try {
      if (this.lock.isValid()) {
        this.lock.release();
      }
    } finally {
      this.channel.close();
    }
  }
}
