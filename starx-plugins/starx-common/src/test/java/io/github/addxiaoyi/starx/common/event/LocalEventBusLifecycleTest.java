package io.github.addxiaoyi.starx.common.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.addxiaoyi.starx.api.event.StarxEvent;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

final class LocalEventBusLifecycleTest {

  @Test
  void unsubscribedConsumerStopsReceivingEvents() {
    LocalEventBus bus = new LocalEventBus();
    AtomicInteger calls = new AtomicInteger();
    Consumer<StarxEvent> consumer = ignored -> calls.incrementAndGet();
    bus.subscribe("auth.success", consumer);
    bus.publish(new StarxEvent("auth.success", Map.of()));

    bus.unsubscribe("auth.success", consumer);
    bus.publish(new StarxEvent("auth.success", Map.of()));

    assertEquals(1, calls.get());
  }
}
