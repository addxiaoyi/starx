package io.github.addxiaoyi.starx.uworld;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.concurrent.CompletionStage;
import net.kyori.adventure.text.Component;

public interface UworldTransferPlayer {

  CompletionStage<Boolean> transferTo(RegisteredServer server);

  default CompletionStage<TransferResult> transferResultTo(RegisteredServer server) {
    return this.transferTo(server).thenApply(started -> Boolean.TRUE.equals(started)
        ? TransferResult.started()
        : TransferResult.failed());
  }

  record TransferResult(Status status, Component reason) {

    public TransferResult {
      java.util.Objects.requireNonNull(status, "status");
    }

    public static TransferResult started() {
      return new TransferResult(Status.STARTED, null);
    }

    public static TransferResult kicked(Component reason) {
      return new TransferResult(Status.KICKED, java.util.Objects.requireNonNull(reason, "reason"));
    }

    public static TransferResult failed() {
      return new TransferResult(Status.FAILED, null);
    }
  }

  enum Status {
    STARTED,
    KICKED,
    FAILED
  }
}
