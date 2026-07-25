package io.github.addxiaoyi.starx.velocity.module.integrations.napcat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class NapCatWebSocketLifecycleTest {

  @Test
  void connectionCompletingAfterStopIsClosedInsteadOfAdopted() {
    NapCatWebSocketClient client = new NapCatWebSocketClient(
        "ws://127.0.0.1:3001", "http://127.0.0.1:3000", new NoopHandler());
    AtomicInteger closes = new AtomicInteger();
    WebSocket socket = (WebSocket) Proxy.newProxyInstance(
        WebSocket.class.getClassLoader(),
        new Class<?>[]{WebSocket.class},
        (proxy, method, args) -> {
          if (method.getName().equals("sendClose")) {
            closes.incrementAndGet();
            return CompletableFuture.completedFuture(proxy);
          }
          if (method.getReturnType() == long.class) return 0L;
          if (method.getReturnType() == boolean.class) return false;
          return null;
        });

    assertFalse(client.acceptConnectedSocket(socket));
    assertEquals(1, closes.get());
    client.stop();
  }

  @Test
  void staleSocketTerminationCannotReleaseTheActiveConnection() {
    NapCatWebSocketClient client = new NapCatWebSocketClient(
        "ws://127.0.0.1:3001", "http://127.0.0.1:3000", new NoopHandler());
    WebSocket oldSocket = socket(new AtomicInteger());
    WebSocket activeSocket = socket(new AtomicInteger());
    client.start();

    assertTrue(client.acceptConnectedSocket(oldSocket));
    assertTrue(client.acceptConnectedSocket(activeSocket));
    assertFalse(client.releaseConnectedSocket(oldSocket));
    assertTrue(client.releaseConnectedSocket(activeSocket));
    client.stop();
  }

  private static WebSocket socket(AtomicInteger closes) {
    return (WebSocket) Proxy.newProxyInstance(
        WebSocket.class.getClassLoader(),
        new Class<?>[]{WebSocket.class},
        (proxy, method, args) -> {
          if (method.getName().equals("sendClose")) {
            closes.incrementAndGet();
            return CompletableFuture.completedFuture(proxy);
          }
          if (method.getReturnType() == long.class) return 0L;
          if (method.getReturnType() == boolean.class) return false;
          return null;
        });
  }

  private static final class NoopHandler implements NapCatWebSocketClient.MessageHandler {
    @Override public void onPrivateMessage(long userId, String message, String nickname) { }
    @Override public void onGroupMessage(long groupId, long userId, String message, String nickname) { }
  }
}
