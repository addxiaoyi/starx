package io.github.addxiaoyi.starx.velocity.http;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record WebhookOutboxRecovery(
    FileWebhookOutbox outbox,
    Optional<Path> quarantine,
    Optional<Exception> error) {
  public WebhookOutboxRecovery {
    outbox = Objects.requireNonNull(outbox, "outbox");
    quarantine = Objects.requireNonNull(quarantine, "quarantine");
    error = Objects.requireNonNull(error, "error");
  }

  public static WebhookOutboxRecovery open(Path file, long now) {
    Objects.requireNonNull(file, "file");
    try {
      return new WebhookOutboxRecovery(new FileWebhookOutbox(file), Optional.empty(), Optional.empty());
    } catch (IllegalStateException error) {
      Path quarantined = quarantine(file, now);
      return new WebhookOutboxRecovery(
          new FileWebhookOutbox(file), Optional.of(quarantined), Optional.of(error));
    }
  }

  public boolean recovered() {
    return quarantine.isPresent();
  }

  private static Path quarantine(Path file, long now) {
    Path source = file.toAbsolutePath().normalize();
    Path quarantined = source.resolveSibling(
        source.getFileName() + ".corrupt-" + now + "-" + UUID.randomUUID());
    try {
      try {
        Files.move(source, quarantined, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException ignored) {
        Files.move(source, quarantined);
      }
      return quarantined;
    } catch (IOException error) {
      throw new IllegalStateException("Failed to quarantine corrupt webhook outbox: " + source, error);
    }
  }
}
