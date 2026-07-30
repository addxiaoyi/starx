package io.github.addxiaoyi.starx.velocity.network;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NetworkOperationLockTest {
  @TempDir
  Path temporary;

  @Test
  void preventsConcurrentAcquisitionAndReleasesOnClose() throws Exception {
    Path lockFile = this.temporary.resolve("network.lock");

    try (NetworkOperationLock first =
        NetworkOperationLock.tryAcquire(lockFile).orElseThrow()) {
      assertTrue(NetworkOperationLock.tryAcquire(lockFile).isEmpty());
    }

    try (NetworkOperationLock second =
        NetworkOperationLock.tryAcquire(lockFile).orElseThrow()) {
      assertTrue(second != null);
    }
  }
}
