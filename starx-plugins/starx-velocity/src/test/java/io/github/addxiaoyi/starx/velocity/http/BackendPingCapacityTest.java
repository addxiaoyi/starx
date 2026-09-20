package io.github.addxiaoyi.starx.velocity.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.velocitypowered.api.proxy.server.ServerPing;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

final class BackendPingCapacityTest {

  @Test
  void readsMaximumPlayersFromCompletedPing() {
    ServerPing ping = ServerPing.builder()
        .version(new ServerPing.Version(774, "1.21.11"))
        .onlinePlayers(0)
        .maximumPlayers(80)
        .description(Component.empty())
        .build();

    assertEquals(80, BackendPingCapacity.read(CompletableFuture.completedFuture(ping)));
  }

  @Test
  void returnsUnavailableWhenPingFails() {
    CompletableFuture<ServerPing> failed = CompletableFuture.failedFuture(
        new IllegalStateException("backend offline"));

    assertEquals(-1, BackendPingCapacity.read(failed));
  }
}
