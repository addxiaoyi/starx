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
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Persistent, lineage-scoped Certbot attempt state and exponential backoff policy. */
final class CertificateAttemptStateStore {
  private static final int SCHEMA_VERSION = 1;
  private static final int MAX_FAILURES = 30;
  private static final Duration CRASH_GUARD = Duration.ofMinutes(15);
  private static final Duration MAX_BACKOFF = Duration.ofHours(24);
  private static final Gson GSON = new GsonBuilder()
      .setPrettyPrinting()
      .disableHtmlEscaping()
      .create();

  private final String lineage;
  private final Path stateFile;
  private final Path lockFile;

  private CertificateAttemptStateStore(Path dataDirectory, String lineage) {
    this.lineage = Objects.requireNonNull(lineage, "lineage").trim().toLowerCase(Locale.ROOT);
    if (this.lineage.isBlank()) {
      throw new IllegalArgumentException("certificate lineage must not be blank");
    }
    Path root = Objects.requireNonNull(dataDirectory, "dataDirectory")
        .toAbsolutePath().normalize().resolve("certificates/automation");
    String id = lineageId(this.lineage);
    this.stateFile = root.resolve(id + ".state.json");
    this.lockFile = root.resolve(id + ".lock");
  }

  static CertificateAttemptStateStore forLineage(Path dataDirectory, String lineage) {
    return new CertificateAttemptStateStore(dataDirectory, lineage);
  }

  String lineage() {
    return this.lineage;
  }

  Path stateFile() {
    return this.stateFile;
  }

  Path lockFile() {
    return this.lockFile;
  }

  LoadResult load() {
    if (!Files.isRegularFile(this.stateFile)) {
      return new LoadResult(LoadStatus.MISSING, Snapshot.empty(this.lineage), "");
    }
    try {
      JsonObject root = JsonParser.parseString(
          Files.readString(this.stateFile, StandardCharsets.UTF_8)).getAsJsonObject();
      if (integer(root, "schemaVersion") != SCHEMA_VERSION) {
        throw new IllegalArgumentException("unsupported schemaVersion");
      }
      String storedLineage = string(root, "lineage").toLowerCase(Locale.ROOT);
      if (!this.lineage.equals(storedLineage)) {
        throw new IllegalArgumentException("lineage mismatch");
      }
      Phase phase = Phase.valueOf(string(root, "phase"));
      Outcome outcome = Outcome.valueOf(string(root, "outcome"));
      FailureClass failureClass = FailureClass.valueOf(string(root, "failureClass"));
      int failures = integer(root, "consecutiveFailures");
      if (failures < 0 || failures > MAX_FAILURES) {
        throw new IllegalArgumentException("invalid consecutiveFailures");
      }
      Snapshot snapshot = new Snapshot(
          this.lineage,
          instant(root, "updatedAt"),
          optionalInstant(root, "lastAttemptAt"),
          phase,
          outcome,
          failureClass,
          failures,
          optionalInstant(root, "nextAllowedAt"));
      return new LoadResult(LoadStatus.LOADED, snapshot, "");
    } catch (IOException | RuntimeException error) {
      return new LoadResult(
          LoadStatus.INVALID,
          Snapshot.empty(this.lineage),
          safeMessage(error));
    }
  }

  Snapshot recordStart(Snapshot previous, Instant now, Phase phase) throws IOException {
    Objects.requireNonNull(previous, "previous");
    Objects.requireNonNull(now, "now");
    Snapshot next = new Snapshot(
        this.lineage,
        now,
        now,
        phase,
        Outcome.IN_PROGRESS,
        FailureClass.NONE,
        previous.consecutiveFailures(),
        now.plus(CRASH_GUARD));
    write(next);
    return next;
  }

  Snapshot recordFailure(
      Snapshot previous,
      Instant now,
      Phase phase,
      FailureClass failureClass) throws IOException {
    Objects.requireNonNull(previous, "previous");
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(failureClass, "failureClass");
    if (failureClass == FailureClass.NONE) {
      throw new IllegalArgumentException("failureClass must describe a failure");
    }
    int failures = Math.min(MAX_FAILURES, previous.consecutiveFailures() + 1);
    Snapshot next = new Snapshot(
        this.lineage,
        now,
        now,
        phase,
        Outcome.FAILED,
        failureClass,
        failures,
        now.plus(backoffDelay(failureClass, failures)));
    write(next);
    return next;
  }

  Snapshot recordSuccess(Snapshot previous, Instant now, Phase phase) throws IOException {
    Objects.requireNonNull(previous, "previous");
    Objects.requireNonNull(now, "now");
    Snapshot next = new Snapshot(
        this.lineage,
        now,
        previous.lastAttemptAt() == null ? now : previous.lastAttemptAt(),
        phase,
        Outcome.SUCCEEDED,
        FailureClass.NONE,
        0,
        null);
    write(next);
    return next;
  }

  static FailureClass classify(NetworkAutomationService.CommandResult result) {
    Objects.requireNonNull(result, "result");
    if (result.timedOut()) {
      return FailureClass.TIMEOUT;
    }
    String diagnostic = (result.output() + " " + result.error()).toLowerCase(Locale.ROOT);
    if (diagnostic.contains("too many requests")
        || diagnostic.contains("rate limit")
        || diagnostic.contains("ratelimit")) {
      return FailureClass.ACME_RATE_LIMIT;
    }
    if (diagnostic.contains("nxdomain")
        || diagnostic.contains("dns problem")
        || diagnostic.contains("no valid a records")
        || diagnostic.contains("no valid aaaa records")) {
      return FailureClass.DNS_FAILURE;
    }
    if (diagnostic.contains("unauthorized")
        || diagnostic.contains("invalid response")
        || diagnostic.contains("challenge failed")
        || diagnostic.contains("connection refused")) {
      return FailureClass.VALIDATION_FAILURE;
    }
    if ((result.exitCode() < 0 && !result.error().isBlank())
        || diagnostic.contains("cannot run program")
        || diagnostic.contains("createprocess error")
        || diagnostic.contains("no such file or directory")) {
      return FailureClass.CLIENT_UNAVAILABLE;
    }
    return FailureClass.COMMAND_FAILED;
  }

  static Duration backoffDelay(FailureClass failureClass, int consecutiveFailures) {
    Objects.requireNonNull(failureClass, "failureClass");
    if (failureClass == FailureClass.NONE || consecutiveFailures < 1) {
      return Duration.ZERO;
    }
    Duration base = switch (failureClass) {
      case CHALLENGE_PORT_OCCUPIED -> Duration.ofMinutes(5);
      case TIMEOUT -> Duration.ofMinutes(15);
      case VALIDATION_FAILURE, COMMAND_FAILED -> Duration.ofMinutes(30);
      case CLIENT_UNAVAILABLE, DNS_FAILURE -> Duration.ofHours(1);
      case ACME_RATE_LIMIT -> Duration.ofHours(6);
      case NONE -> Duration.ZERO;
    };
    int exponent = Math.min(10, consecutiveFailures - 1);
    Duration delay;
    try {
      delay = base.multipliedBy(1L << exponent);
    } catch (ArithmeticException overflow) {
      delay = MAX_BACKOFF;
    }
    return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
  }

  private void write(Snapshot snapshot) throws IOException {
    JsonObject root = new JsonObject();
    root.addProperty("schemaVersion", SCHEMA_VERSION);
    root.addProperty("lineage", this.lineage);
    root.addProperty("updatedAt", snapshot.updatedAt().toString());
    if (snapshot.lastAttemptAt() != null) {
      root.addProperty("lastAttemptAt", snapshot.lastAttemptAt().toString());
    }
    root.addProperty("phase", snapshot.phase().name());
    root.addProperty("outcome", snapshot.outcome().name());
    root.addProperty("failureClass", snapshot.failureClass().name());
    root.addProperty("consecutiveFailures", snapshot.consecutiveFailures());
    if (snapshot.nextAllowedAt() != null) {
      root.addProperty("nextAllowedAt", snapshot.nextAllowedAt().toString());
    }
    writeAtomically(this.stateFile, GSON.toJson(root) + System.lineSeparator());
  }

  private static void writeAtomically(Path target, String content) throws IOException {
    Path parent = target.getParent();
    Files.createDirectories(parent);
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

  private static String lineageId(String lineage) {
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256")
          .digest(lineage.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash, 0, 8);
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

  private static Instant instant(JsonObject root, String name) {
    return Instant.parse(string(root, name));
  }

  private static Instant optionalInstant(JsonObject root, String name) {
    if (!root.has(name) || root.get(name).isJsonNull()) {
      return null;
    }
    return Instant.parse(root.get(name).getAsString().trim());
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
    NONE,
    PREFLIGHT,
    STAGING,
    PRODUCTION
  }

  enum Outcome {
    NEVER,
    IN_PROGRESS,
    FAILED,
    SUCCEEDED
  }

  enum FailureClass {
    NONE,
    CHALLENGE_PORT_OCCUPIED,
    CLIENT_UNAVAILABLE,
    TIMEOUT,
    ACME_RATE_LIMIT,
    DNS_FAILURE,
    VALIDATION_FAILURE,
    COMMAND_FAILED
  }

  record LoadResult(LoadStatus status, Snapshot snapshot, String diagnostic) {
    LoadResult {
      status = Objects.requireNonNull(status, "status");
      snapshot = Objects.requireNonNull(snapshot, "snapshot");
      diagnostic = diagnostic == null ? "" : diagnostic;
    }
  }

  record Snapshot(
      String lineage,
      Instant updatedAt,
      Instant lastAttemptAt,
      Phase phase,
      Outcome outcome,
      FailureClass failureClass,
      int consecutiveFailures,
      Instant nextAllowedAt) {

    Snapshot {
      lineage = Objects.requireNonNull(lineage, "lineage");
      updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
      phase = Objects.requireNonNull(phase, "phase");
      outcome = Objects.requireNonNull(outcome, "outcome");
      failureClass = Objects.requireNonNull(failureClass, "failureClass");
      if (consecutiveFailures < 0 || consecutiveFailures > MAX_FAILURES) {
        throw new IllegalArgumentException("invalid consecutiveFailures");
      }
    }

    static Snapshot empty(String lineage) {
      return new Snapshot(
          lineage,
          Instant.EPOCH,
          null,
          Phase.NONE,
          Outcome.NEVER,
          FailureClass.NONE,
          0,
          null);
    }

    boolean backoffActive(Instant now) {
      return this.nextAllowedAt != null && now.isBefore(this.nextAllowedAt);
    }

    Map<String, Object> report() {
      LinkedHashMap<String, Object> result = new LinkedHashMap<>();
      result.put("lineage", this.lineage);
      result.put("updatedAt", this.updatedAt.toString());
      result.put("phase", this.phase.name());
      result.put("outcome", this.outcome.name());
      result.put("failureClass", this.failureClass.name());
      result.put("consecutiveFailures", this.consecutiveFailures);
      if (this.lastAttemptAt != null) {
        result.put("lastAttemptAt", this.lastAttemptAt.toString());
      }
      if (this.nextAllowedAt != null) {
        result.put("nextAllowedAt", this.nextAllowedAt.toString());
      }
      return Map.copyOf(result);
    }
  }
}
