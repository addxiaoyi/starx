package io.github.addxiaoyi.starx.velocity.event;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.addxiaoyi.starx.api.event.StarxEvent;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class VelocityEventBusObserverTest {
  @Test
  void globalObserverReceivesEveryPublishedEvent() throws InterruptedException {
    VelocityEventBus bus = new VelocityEventBus();
    CountDownLatch observed = new CountDownLatch(2);
    bus.subscribeAll(event -> observed.countDown());

    bus.publish(new StarxEvent("auth:login", Map.of()));
    bus.publish(new StarxEvent("skin:refresh", Map.of()));

    assertTrue(observed.await(2, TimeUnit.SECONDS));
    bus.close();
  }

  @Test
  void closeStopsNewEventDelivery() throws InterruptedException {
    VelocityEventBus bus = new VelocityEventBus();
    CountDownLatch observed = new CountDownLatch(1);
    bus.subscribe("auth:login", event -> observed.countDown());

    bus.close();
    bus.publish(new StarxEvent("auth:login", Map.of()));

    assertEquals(1, observed.getCount());
  }
}
