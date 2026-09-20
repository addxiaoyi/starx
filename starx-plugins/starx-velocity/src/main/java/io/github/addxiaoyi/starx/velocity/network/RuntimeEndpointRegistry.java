package io.github.addxiaoyi.starx.velocity.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.addxiaoyi.starx.velocity.config.StarxConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Owns the per-data-directory runtime lock, publishes the effective HTTP endpoint and preserves a
 * non-secret port lease for stable restarts.
 */
public final class RuntimeEndpointRegistry implements AutoCloseable {
  public static final String ENDPOINT_FILE_NAME = "runtime-endpoint.json";
  public static final String LOCK_FILE_NAME = "runtime-endpoint.lock";
  public static final String LEASE_FILE_NAME = "runtime-port-lease.json";
  private static final int SCHEMA_VERSION = 1;

  private final Path dataDirectory;
  private final Path endpointFile;
  private final Path lockFile;
  private final Path leaseFile;
  private final FileChannel lockChannel;
  private final FileLock lock;
  private final Clock clock;
  private final String instanceId = UUID.randomUUID().toString();
  private final Instant startedAt;
  private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
  private volatile Integer leaseCandidate;
  private volatile boolean closed;

  public static RuntimeEndpointRegistry open(Path dataDirectory) throws IOException {
    return open(dataDirectory, Clock.systemUTC());
  }

  static RuntimeEndpointRegistry open(Path dataDirectory, Clock clock) throws IOException {
    return new RuntimeEndpointRegistry(dataDirectory, clock);
  }

  private RuntimeEndpointRegistry(Path dataDirectory, Clock clock) throws IOException {
    this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory")
        .toAbsolutePath().normalize();
    this.clock = Objects.requireNonNull(clock, "clock");
    this.startedAt = this.clock.instant();
    Files.createDirectories(this.dataDirectory);
    this.endpointFile = this.dataDirectory.resolve(ENDPOINT_FILE_NAME);
    this.lockFile = this.dataDirectory.resolve(LOCK_FILE_NAME);
    this.leaseFile = this.dataDirectory.resolve(LEASE_FILE_NAME);
    this.lockChannel = FileChannel.open(
        this.lockFile,
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE);
    FileLock acquired = null;
    try {
      acquired = this.lockChannel.tryLock();
    } catch (OverlappingFileLockException alreadyHeld) {
      // Same-JVM duplicate startup is equivalent to another process holding the data directory.
    }
    if (acquired == null) {
      this.lockChannel.close();
      throw new IOException(
          "Another StarX Velocity instance already owns data directory " + this.dataDirectory);
    }
    this.lock = acquired;
  }

  public OptionalInt leasedPort(int configuredPort) {
    requirePort(configuredPort, "configuredPort");
    if (!Files.isRegularFile(this.leaseFile)) {
      this.leaseCandidate = null;
      return OptionalInt.empty();
    }
    try {
      JsonObject root = JsonParser.parseString(
          Files.readString(this.leaseFile, StandardCharsets.UTF_8)).getAsJsonObject();
      if (integer(root, "schemaVersion") != SCHEMA_VERSION
          || integer(root, "configuredPort") != configuredPort) {
        this.leaseCandidate = null;
        return OptionalInt.empty();
      }
      int selectedPort = integer(root, "selectedPort");
      if (!validPort(selectedPort)) {
        this.leaseCandidate = null;
        return OptionalInt.empty();
      }
      this.leaseCandidate = selectedPort;
      return OptionalInt.of(selectedPort);
    } catch (RuntimeException | IOException invalidLease) {
      this.leaseCandidate = null;
      return OptionalInt.empty();
    }
  }

  public void publish(
      StarxConfig.HttpConfig configured,
      StarxConfig.HttpConfig effective,
      TcpPortAllocator.Selection selection) throws IOException {
    ensureOpen();
    Objects.requireNonNull(configured, "configured");
    Objects.requireNonNull(effective, "effective");
    Objects.requireNonNull(selection, "selection");

    JsonObject lease = new JsonObject();
    lease.addProperty("schemaVersion", SCHEMA_VERSION);
    lease.addProperty("configuredPort", configured.port());
    lease.addProperty("selectedPort", effective.port());
    lease.addProperty("updatedAt", this.clock.instant().toString());

    JsonObject endpoint = new JsonObject();
    endpoint.addProperty("schemaVersion", SCHEMA_VERSION);
    endpoint.addProperty("instanceId", this.instanceId);
    endpoint.addProperty("processId", ProcessHandle.current().pid());
    endpoint.addProperty("startedAt", this.startedAt.toString());
    endpoint.addProperty("publishedAt", this.clock.instant().toString());
    endpoint.addProperty("bind", effective.bind());
    endpoint.addProperty("configuredPort", configured.port());
    endpoint.addProperty("effectivePort", effective.port());
    endpoint.addProperty("localBaseUrl", localBaseUrl(effective));
    endpoint.addProperty("selectionMode", selection.mode());
    endpoint.addProperty(
        "leaseReused",
        this.leaseCandidate != null
            && this.leaseCandidate == effective.port()
            && configured.port() != effective.port());

    try {
      writeAtomic(this.leaseFile, this.gson.toJson(lease) + System.lineSeparator());
      writeAtomic(this.endpointFile, this.gson.toJson(endpoint) + System.lineSeparator());
    } catch (IOException error) {
      Files.deleteIfExists(this.endpointFile);
      throw error;
    }
  }

  public Path endpointFile() {
    return this.endpointFile;
  }

  public Path lockFile() {
    return this.lockFile;
  }

  public Path leaseFile() {
    return this.leaseFile;
  }

  private void ensureOpen() {
    if (this.closed || !this.lock.isValid()) {
      throw new IllegalStateException("Runtime endpoint registry is closed");
    }
  }

  private static String localBaseUrl(StarxConfig.HttpConfig http) {
    String bind = http.bind();
    String host = bind == null ? "" : bind.trim();
    if (host.isBlank()
        || "0.0.0.0".equals(host)
        || "::".equals(host)
        || "localhost".equalsIgnoreCase(host)
        || "127.0.0.1".equals(host)
        || "::1".equals(host)) {
      host = "127.0.0.1";
    } else if (host.startsWith("[") && host.endsWith("]")) {
      host = host.substring(1, host.length() - 1);
    }
    if (host.contains(":")) {
      host = "[" + host + "]";
    }
    return "http://" + host + ":" + http.port();
  }

  private static int integer(JsonObject root, String name) {
    if (!root.has(name) || !root.get(name).isJsonPrimitive()) {
      return -1;
    }
    return root.get(name).getAsInt();
  }

  private static boolean validPort(int port) {
    return port >= 1 && port <= 65_535;
  }

  private static void requirePort(int port, String name) {
    if (!validPort(port)) {
      throw new IllegalArgumentException(name + " must be between 1 and 65535");
    }
  }

  private static void writeAtomic(Path target, String content) throws IOException {
    Path parent = target.toAbsolutePath().normalize().getParent();
    if (parent == null) {
      throw new IOException("Runtime endpoint target has no parent: " + target);
    }
    Files.createDirectories(parent);
    Path temporary = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
    try {
      Files.writeString(
          temporary,
          content,
          StandardCharsets.UTF_8,
          StandardOpenOption.TRUNCATE_EXISTING);
      try {
        Files.move(
            temporary,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException unsupported) {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  @Override
  public synchronized void close() {
    if (this.closed) {
      return;
    }
    this.closed = true;
    IOException failure = null;
    try {
      Files.deleteIfExists(this.endpointFile);
    } catch (IOException error) {
      failure = error;
    }
    try {
      if (this.lock.isValid()) {
        this.lock.release();
      }
    } catch (IOException error) {
      if (failure == null) {
        failure = error;
      } else {
        failure.addSuppressed(error);
      }
    }
    try {
      this.lockChannel.close();
    } catch (IOException error) {
      if (failure == null) {
        failure = error;
      } else {
        failure.addSuppressed(error);
      }
    }
    if (failure != null) {
      throw new UncheckedIOException("Unable to close runtime endpoint registry", failure);
    }
  }
}
