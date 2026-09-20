package io.github.addxiaoyi.starx.velocity.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Persistent recovery journal for one managed FRP configuration transaction. */
final class FrpManagedTransactionStore {
  private static final int SCHEMA_VERSION = 1;
  private static final Gson GSON = new GsonBuilder()
      .setPrettyPrinting()
      .disableHtmlEscaping()
      .create();

  private final Path mainConfig;
  private final Path managedConfig;
  private final Path stateFile;
  private final Path backupFile;

  private FrpManagedTransactionStore(
      Path dataDirectory,
      Path mainConfig,
      Path managedConfig) {
    Path root = Objects.requireNonNull(dataDirectory, "dataDirectory")
        .toAbsolutePath().normalize().resolve("frp/transactions");
    this.mainConfig = normalize(mainConfig, "mainConfig");
    this.managedConfig = normalize(managedConfig, "managedConfig");
    String id = transactionId(this.mainConfig, this.managedConfig);
    this.stateFile = root.resolve(id + ".transaction.json");
    this.backupFile = root.resolve(id + ".backup");
  }

  static FrpManagedTransactionStore forConfig(
      Path dataDirectory,
      Path mainConfig,
      Path managedConfig) {
    return new FrpManagedTransactionStore(dataDirectory, mainConfig, managedConfig);
  }

  Path stateFile() {
    return this.stateFile;
  }

  Path backupFile() {
    return this.backupFile;
  }

  LoadResult load() {
    if (!Files.isRegularFile(this.stateFile)) {
      return new LoadResult(LoadStatus.MISSING, null, "");
    }
    try {
      JsonObject root = JsonParser.parseString(
          Files.readString(this.stateFile, StandardCharsets.UTF_8)).getAsJsonObject();
      if (integer(root, "schemaVersion") != SCHEMA_VERSION) {
        throw new IllegalArgumentException("unsupported schemaVersion");
      }
      Path storedMain = Path.of(string(root, "mainConfig")).toAbsolutePath().normalize();
      Path storedManaged = Path.of(string(root, "managedConfig")).toAbsolutePath().normalize();
      if (!this.mainConfig.equals(storedMain) || !this.managedConfig.equals(storedManaged)) {
        throw new IllegalArgumentException("transaction path mismatch");
      }
      boolean previousExisted = bool(root, "previousExisted");
      String previousSha256 = optionalString(root, "previousSha256");
      String desiredSha256 = hash(root, "desiredSha256");
      if (previousExisted) {
        previousSha256 = validateHash(previousSha256, "previousSha256");
        if (!Files.isRegularFile(this.backupFile)) {
          throw new IllegalArgumentException("transaction backup missing");
        }
        String backup = Files.readString(this.backupFile, StandardCharsets.UTF_8);
        if (!previousSha256.equals(sha256(backup))) {
          throw new IllegalArgumentException("transaction backup checksum mismatch");
        }
      } else if (!previousSha256.isBlank()) {
        throw new IllegalArgumentException("unexpected previousSha256");
      }
      Snapshot snapshot = new Snapshot(
          Instant.parse(string(root, "createdAt")),
          Instant.parse(string(root, "updatedAt")),
          Phase.valueOf(string(root, "phase")),
          previousExisted,
          previousSha256,
          desiredSha256);
      return new LoadResult(LoadStatus.LOADED, snapshot, "");
    } catch (IOException | RuntimeException error) {
      return new LoadResult(LoadStatus.INVALID, null, safeMessage(error));
    }
  }

  Snapshot begin(String previousConfig, String desiredConfig, Instant now) throws IOException {
    Objects.requireNonNull(desiredConfig, "desiredConfig");
    Objects.requireNonNull(now, "now");
    if (Files.exists(this.stateFile)) {
      throw new IOException("FRP transaction already exists");
    }
    boolean previousExisted = previousConfig != null;
    String previousSha256 = previousExisted ? sha256(previousConfig) : "";
    if (previousExisted) {
      writeAtomically(this.backupFile, previousConfig);
    } else {
      Files.deleteIfExists(this.backupFile);
    }
    Snapshot snapshot = new Snapshot(
        now,
        now,
        Phase.PREPARED,
        previousExisted,
        previousSha256,
        sha256(desiredConfig));
    write(snapshot);
    return snapshot;
  }

  Snapshot updatePhase(Snapshot snapshot, Phase phase, Instant now) throws IOException {
    Objects.requireNonNull(snapshot, "snapshot");
    Objects.requireNonNull(phase, "phase");
    Objects.requireNonNull(now, "now");
    Snapshot next = new Snapshot(
        snapshot.createdAt(),
        now,
        phase,
        snapshot.previousExisted(),
        snapshot.previousSha256(),
        snapshot.desiredSha256());
    write(next);
    return next;
  }

  boolean currentContentCompatible(Snapshot snapshot) throws IOException {
    Objects.requireNonNull(snapshot, "snapshot");
    if (!Files.isRegularFile(this.managedConfig)) {
      return !snapshot.previousExisted();
    }
    String currentSha256 = sha256(Files.readString(this.managedConfig, StandardCharsets.UTF_8));
    return currentSha256.equals(snapshot.desiredSha256())
        || (snapshot.previousExisted() && currentSha256.equals(snapshot.previousSha256()));
  }

  void restore(Snapshot snapshot) throws IOException {
    Objects.requireNonNull(snapshot, "snapshot");
    if (snapshot.previousExisted()) {
      if (!Files.isRegularFile(this.backupFile)) {
        throw new IOException("FRP transaction backup missing");
      }
      String previous = Files.readString(this.backupFile, StandardCharsets.UTF_8);
      if (!snapshot.previousSha256().equals(sha256(previous))) {
        throw new IOException("FRP transaction backup checksum mismatch");
      }
      writeAtomically(this.managedConfig, previous);
    } else {
      Files.deleteIfExists(this.managedConfig);
    }
  }

  void clear() throws IOException {
    Files.deleteIfExists(this.stateFile);
    Files.deleteIfExists(this.backupFile);
  }

  private void write(Snapshot snapshot) throws IOException {
    JsonObject root = new JsonObject();
    root.addProperty("schemaVersion", SCHEMA_VERSION);
    root.addProperty("mainConfig", this.mainConfig.toString());
    root.addProperty("managedConfig", this.managedConfig.toString());
    root.addProperty("createdAt", snapshot.createdAt().toString());
    root.addProperty("updatedAt", snapshot.updatedAt().toString());
    root.addProperty("phase", snapshot.phase().name());
    root.addProperty("previousExisted", snapshot.previousExisted());
    if (snapshot.previousExisted()) {
      root.addProperty("previousSha256", snapshot.previousSha256());
    }
    root.addProperty("desiredSha256", snapshot.desiredSha256());
    writeAtomically(this.stateFile, GSON.toJson(root) + System.lineSeparator());
  }

  private static void writeAtomically(Path target, String content) throws IOException {
    Path parent = target.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Path temporary = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
    try {
      Files.writeString(temporary, content, StandardCharsets.UTF_8);
      try {
        Files.setPosixFilePermissions(temporary, Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE));
      } catch (UnsupportedOperationException ignored) {
        // Windows and some filesystems do not expose POSIX permissions.
      }
      try {
        Files.move(
            temporary,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException ignored) {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static Path normalize(Path path, String name) {
    return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
  }

  private static String transactionId(Path mainConfig, Path managedConfig) {
    return sha256(mainConfig + "\n" + managedConfig).substring(0, 24);
  }

  private static String sha256(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (Exception error) {
      throw new IllegalStateException("SHA-256 unavailable", error);
    }
  }

  private static int integer(JsonObject root, String name) {
    if (!root.has(name) || !root.get(name).isJsonPrimitive()) {
      throw new IllegalArgumentException("missing " + name);
    }
    return root.get(name).getAsInt();
  }

  private static boolean bool(JsonObject root, String name) {
    if (!root.has(name) || !root.get(name).isJsonPrimitive()) {
      throw new IllegalArgumentException("missing " + name);
    }
    return root.get(name).getAsBoolean();
  }

  private static String string(JsonObject root, String name) {
    if (!root.has(name) || !root.get(name).isJsonPrimitive()) {
      throw new IllegalArgumentException("missing " + name);
    }
    String value = root.get(name).getAsString().trim();
    if (value.isBlank()) {
      throw new IllegalArgumentException("blank " + name);
    }
    return value;
  }

  private static String optionalString(JsonObject root, String name) {
    if (!root.has(name) || root.get(name).isJsonNull()) {
      return "";
    }
    if (!root.get(name).isJsonPrimitive()) {
      throw new IllegalArgumentException("invalid " + name);
    }
    return root.get(name).getAsString().trim();
  }

  private static String hash(JsonObject root, String name) {
    return validateHash(string(root, name), name);
  }

  private static String validateHash(String value, String name) {
    if (!value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("invalid " + name);
    }
    return value;
  }

  private static String safeMessage(Throwable error) {
    String message = error.getMessage();
    return message == null || message.isBlank()
        ? error.getClass().getSimpleName()
        : message;
  }

  enum LoadStatus {
    MISSING,
    LOADED,
    INVALID
  }

  enum Phase {
    PREPARED,
    RELOAD_REQUIRED
  }

  record LoadResult(LoadStatus status, Snapshot snapshot, String diagnostic) {
    LoadResult {
      status = Objects.requireNonNull(status, "status");
      diagnostic = Objects.requireNonNullElse(diagnostic, "");
      if (status == LoadStatus.LOADED && snapshot == null) {
        throw new IllegalArgumentException("loaded transaction requires a snapshot");
      }
      if (status != LoadStatus.LOADED && snapshot != null) {
        throw new IllegalArgumentException("non-loaded transaction must not expose a snapshot");
      }
    }
  }

  record Snapshot(
      Instant createdAt,
      Instant updatedAt,
      Phase phase,
      boolean previousExisted,
      String previousSha256,
      String desiredSha256) {
    Snapshot {
      createdAt = Objects.requireNonNull(createdAt, "createdAt");
      updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
      phase = Objects.requireNonNull(phase, "phase");
      previousSha256 = Objects.requireNonNullElse(previousSha256, "");
      desiredSha256 = validateHash(
          Objects.requireNonNull(desiredSha256, "desiredSha256"),
          "desiredSha256");
      if (previousExisted) {
        previousSha256 = validateHash(previousSha256, "previousSha256");
      } else if (!previousSha256.isBlank()) {
        throw new IllegalArgumentException("unexpected previousSha256");
      }
      if (updatedAt.isBefore(createdAt)) {
        throw new IllegalArgumentException("updatedAt precedes createdAt");
      }
    }

    Map<String, Object> report() {
      LinkedHashMap<String, Object> result = new LinkedHashMap<>();
      result.put("phase", this.phase.name());
      result.put("createdAt", this.createdAt.toString());
      result.put("updatedAt", this.updatedAt.toString());
      result.put("previousExisted", this.previousExisted);
      result.put("desiredSha256", this.desiredSha256);
      if (this.previousExisted) {
        result.put("previousSha256", this.previousSha256);
      }
      return Map.copyOf(result);
    }
  }
}
