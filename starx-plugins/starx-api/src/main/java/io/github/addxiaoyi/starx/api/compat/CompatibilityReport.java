package io.github.addxiaoyi.starx.api.compat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Secret-free compatibility report suitable for commands, logs and support bundles.
 *
 * @param platform platform that produced the report
 * @param runtimeVersion detected platform runtime version
 * @param javaVersion detected Java runtime version
 * @param generatedAt report generation time
 * @param checks immutable compatibility decisions included in the report
 */
public record CompatibilityReport(
    String platform,
    String runtimeVersion,
    String javaVersion,
    Instant generatedAt,
    List<CompatibilityCheck> checks
) {
  /** Validates report metadata and copies the supplied check list. */
  public CompatibilityReport {
    platform = requireText(platform, "platform");
    runtimeVersion = normalize(runtimeVersion);
    javaVersion = normalize(javaVersion);
    generatedAt = Objects.requireNonNull(generatedAt, "generatedAt");
    checks = List.copyOf(Objects.requireNonNull(checks, "checks"));
  }

  /**
   * Computes the most severe status across all checks.
   *
   * @return aggregate compatibility status
   */
  public CompatibilityStatus overallStatus() {
    CompatibilityStatus status = CompatibilityStatus.SUPPORTED;
    for (CompatibilityCheck check : this.checks) {
      status = CompatibilityStatus.worst(status, check.status());
    }
    return status;
  }

  /**
   * Returns whether any check blocks strict startup.
   *
   * @return {@code true} when at least one check is unsupported
   */
  public boolean blocksStrictStartup() {
    return this.checks.stream().anyMatch(CompatibilityCheck::blocksStrictStartup);
  }

  /**
   * Writes this report as UTF-8 JSON using an atomic replacement when supported.
   *
   * @param target destination JSON file
   * @throws IOException when the report cannot be written or replaced
   */
  public void writeAtomically(Path target) throws IOException {
    Objects.requireNonNull(target, "target");
    Path absolute = target.toAbsolutePath().normalize();
    Path parent = Objects.requireNonNull(absolute.getParent(), "target parent");
    Files.createDirectories(parent);
    Path temporary = Files.createTempFile(parent, absolute.getFileName().toString(), ".tmp");
    try {
      Files.writeString(temporary, this.toJson(), StandardCharsets.UTF_8);
      try {
        Files.move(temporary, absolute,
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException ignored) {
        Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  /**
   * Serializes this report to a stable, secret-free JSON document.
   *
   * @return JSON document terminated by a newline
   */
  public String toJson() {
    StringBuilder json = new StringBuilder(512);
    json.append("{\n")
        .append("  \"platform\": ").append(quote(this.platform)).append(",\n")
        .append("  \"runtimeVersion\": ").append(quote(this.runtimeVersion)).append(",\n")
        .append("  \"javaVersion\": ").append(quote(this.javaVersion)).append(",\n")
        .append("  \"generatedAt\": ").append(quote(this.generatedAt.toString())).append(",\n")
        .append("  \"overallStatus\": ").append(quote(this.overallStatus().name())).append(",\n")
        .append("  \"checks\": [\n");
    for (int index = 0; index < this.checks.size(); index++) {
      CompatibilityCheck check = this.checks.get(index);
      json.append("    {\"id\": ").append(quote(check.id()))
          .append(", \"component\": ").append(quote(check.component()))
          .append(", \"detectedVersion\": ").append(quote(check.detectedVersion()))
          .append(", \"supportedRange\": ").append(quote(check.supportedRange()))
          .append(", \"status\": ").append(quote(check.status().name()))
          .append(", \"message\": ").append(quote(check.message())).append('}');
      if (index + 1 < this.checks.size()) {
        json.append(',');
      }
      json.append('\n');
    }
    return json.append("  ]\n}\n").toString();
  }

  private static String quote(String value) {
    StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '"' -> escaped.append("\\\"");
        case '\\' -> escaped.append("\\\\");
        case '\n' -> escaped.append("\\n");
        case '\r' -> escaped.append("\\r");
        case '\t' -> escaped.append("\\t");
        default -> {
          if (character < 0x20) {
            escaped.append(String.format("\\u%04x", (int) character));
          } else {
            escaped.append(character);
          }
        }
      }
    }
    return escaped.append('"').toString();
  }

  private static String requireText(String value, String name) {
    String normalized = normalize(value);
    if (normalized.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return normalized;
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim();
  }
}
