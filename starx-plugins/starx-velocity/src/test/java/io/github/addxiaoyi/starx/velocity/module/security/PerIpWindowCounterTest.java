package io.github.addxiaoyi.starx.velocity.module.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

final class PerIpWindowCounterTest {

  @Test
  void resetsCountAfterOneSecondWindow() {
    PerIpWindowCounter counter = new PerIpWindowCounter(16, 1_000);

    assertEquals(1, counter.increment("127.0.0.1", 0));
    assertEquals(2, counter.increment("127.0.0.1", 999));
    assertEquals(1, counter.increment("127.0.0.1", 1_000));
  }

  @Test
  void countsConcurrentRequestsWithoutLostUpdates() {
    PerIpWindowCounter counter = new PerIpWindowCounter(16, 1_000);

    IntStream.range(0, 1_000).parallel().forEach(ignored ->
        counter.increment("127.0.0.1", 10));

    assertEquals(1_000, counter.count("127.0.0.1", 10));
  }

  @Test
  void evictsOldestIpAtCapacity() {
    PerIpWindowCounter counter = new PerIpWindowCounter(2, 1_000);
    counter.increment("10.0.0.1", 1);
    counter.increment("10.0.0.2", 2);

    counter.increment("10.0.0.3", 3);

    assertEquals(0, counter.count("10.0.0.1", 3));
    assertEquals(2, counter.size());
  }
}
