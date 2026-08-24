package io.github.addxiaoyi.starx.server;

import io.github.addxiaoyi.starx.api.bridge.BridgeMessage;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

final class BackendHeartbeatExchange {
  private static final int MAX_EXCHANGES = 32;
  private static final int MIN_EXCHANGES = 1;

  private BackendHeartbeatExchange() {
  }

  static CompletableFuture<Void> run(
      BackendHeartbeatClient client,
      BackendBridgeSession session,
      BridgeMessage outbound,
      int maxExchanges
  ) {
    return run(client, session, outbound, maxExchanges, command -> {
      try {
        return CompletableFuture.completedFuture(command.get());
      } catch (RuntimeException error) {
        return CompletableFuture.failedFuture(error);
      }
    });
  }

  static CompletableFuture<Void> run(
      BackendHeartbeatClient client,
      BackendBridgeSession session,
      BridgeMessage outbound,
      int maxExchanges,
      CommandExecutor commandExecutor
  ) {
    Objects.requireNonNull(client, "client");
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(outbound, "outbound");
    Objects.requireNonNull(commandExecutor, "commandExecutor");
    if (maxExchanges < MIN_EXCHANGES || maxExchanges > MAX_EXCHANGES) {
      throw new IllegalArgumentException(
          "maxExchanges must be between " + MIN_EXCHANGES + " and " + MAX_EXCHANGES);
    }
    return exchange(client, session, outbound, maxExchanges, commandExecutor);
  }

  private static CompletableFuture<Void> exchange(
      BackendHeartbeatClient client,
      BackendBridgeSession session,
      BridgeMessage outbound,
      int remaining,
      CommandExecutor commandExecutor
  ) {
    return client.send(outbound).thenCompose(command -> {
      if (command.isEmpty()) {
        return CompletableFuture.completedFuture(null);
      }
      CompletableFuture<Optional<BridgeMessage>> scheduled;
      try {
        scheduled = Objects.requireNonNull(
            commandExecutor.execute(() -> session.receive(command.get())),
            "commandExecutor result");
      } catch (RuntimeException error) {
        return CompletableFuture.failedFuture(error);
      }
      return scheduled.thenCompose(response -> {
        if (response.isEmpty()) {
          return CompletableFuture.completedFuture(null);
        }
        if (remaining == 1) {
          return CompletableFuture.failedFuture(new IllegalStateException(
              "Backend heartbeat exchange limit reached before sending a response"));
        }
        return exchange(
            client, session, response.get(), remaining - 1, commandExecutor);
      });
    });
  }

  @FunctionalInterface
  interface CommandExecutor {
    CompletableFuture<Optional<BridgeMessage>> execute(
        Supplier<Optional<BridgeMessage>> command);
  }
}
