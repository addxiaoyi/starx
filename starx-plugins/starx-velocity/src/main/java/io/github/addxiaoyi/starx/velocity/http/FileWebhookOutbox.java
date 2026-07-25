package io.github.addxiaoyi.starx.velocity.http;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class FileWebhookOutbox {
  private static final int MAX_PENDING = 4096;
  private static final int MAX_BODY_BYTES = 1024 * 1024;

  private final Path file;
  private final Gson gson = new Gson();
  private final Map<String, PendingWebhook> entries = new LinkedHashMap<>();

  public FileWebhookOutbox(Path file) {
    this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
    load();
  }

  public synchronized PendingWebhook enqueue(String url, String body, long createdAt) {
    validateUrl(url);
    Objects.requireNonNull(body, "body");
    if (body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
      throw new IllegalArgumentException("Webhook body exceeds 1 MiB");
    }
    if (entries.size() >= MAX_PENDING) {
      throw new IllegalStateException("Webhook outbox is full: " + file);
    }
    PendingWebhook pending = new PendingWebhook(
        UUID.randomUUID().toString(), url, body, createdAt);
    entries.put(pending.id(), pending);
    persist();
    return pending;
  }

  public synchronized boolean ack(String id) {
    Objects.requireNonNull(id, "id");
    PendingWebhook removed = entries.remove(id);
    if (removed == null) return false;
    persist();
    return true;
  }

  public synchronized List<PendingWebhook> pending() {
    return List.copyOf(entries.values());
  }

  private void load() {
    if (!Files.exists(file)) return;
    try {
      String json = Files.readString(file, StandardCharsets.UTF_8);
      PendingWebhook[] saved = gson.fromJson(json, PendingWebhook[].class);
      if (saved == null) throw new JsonParseException("expected an array");
      if (saved.length > MAX_PENDING) throw new IllegalStateException("Webhook outbox exceeds capacity: " + file);
      for (PendingWebhook pending : saved) {
        if (pending == null || entries.putIfAbsent(pending.id(), pending) != null) {
          throw new IllegalStateException("Webhook outbox contains invalid entries: " + file);
        }
        validateUrl(pending.url());
      }
    } catch (IOException | JsonParseException | IllegalArgumentException error) {
      throw new IllegalStateException("Failed to read webhook outbox: " + file, error);
    }
  }

  private void persist() {
    Path parent = file.getParent();
    Path temp = file.resolveSibling(file.getFileName() + ".tmp");
    try {
      if (parent != null) Files.createDirectories(parent);
      Files.writeString(temp, gson.toJson(new ArrayList<>(entries.values())), StandardCharsets.UTF_8);
      try {
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException ignored) {
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException error) {
      throw new IllegalStateException("Failed to persist webhook outbox: " + file, error);
    }
  }

  private static void validateUrl(String value) {
    URI uri = URI.create(Objects.requireNonNull(value, "url"));
    boolean supportedScheme = "https".equalsIgnoreCase(uri.getScheme())
        || "http".equalsIgnoreCase(uri.getScheme());
    if (!supportedScheme || uri.getHost() == null) {
      throw new IllegalArgumentException("Webhook URL must be absolute HTTP(S)");
    }
  }
}
