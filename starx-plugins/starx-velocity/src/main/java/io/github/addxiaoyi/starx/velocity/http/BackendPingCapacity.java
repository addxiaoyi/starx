package io.github.addxiaoyi.starx.velocity.http;

import com.velocitypowered.api.proxy.server.ServerPing;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

final class BackendPingCapacity {

  private static final long TIMEOUT_SECONDS = 1;

  private BackendPingCapacity() {
  }

  static int read(CompletableFuture<ServerPing> request) {
    Objects.requireNonNull(request, "request");
    try {
      ServerPing ping = request.completeOnTimeout(null, TIMEOUT_SECONDS, TimeUnit.SECONDS).join();
      if (ping == null) {
        return -1;
      }
      return ping.getPlayers()
          .map(ServerPing.Players::getMax)
          .filter(capacity -> capacity >= 0)
          .orElse(-1);
    } catch (CompletionException ignored) {
      return -1;
    }
  }
}
