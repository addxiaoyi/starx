package io.github.addxiaoyi.starx.website;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Atomically updates only the website-sync credential scalars while preserving other YAML text. */
public final class YamlWebsiteCredentialStore implements WebsiteSyncCredentialStore {
  private final Path configFile;

  public YamlWebsiteCredentialStore(Path configFile) {
    this.configFile = Objects.requireNonNull(configFile, "configFile").toAbsolutePath();
  }

  @Override
  public synchronized void persistEnrollment(SecretValue nodeToken) throws IOException {
    if (nodeToken == null || !nodeToken.isPresent()) {
      throw new IllegalArgumentException("nodeToken must be present");
    }
    String source = Files.readString(this.configFile, StandardCharsets.UTF_8);
    String newline = source.contains("\r\n") ? "\r\n" : "\n";
    List<String> lines = new ArrayList<>(List.of(source.split("\\R", -1)));
    int root = findRoot(lines);
    int end = findRootEnd(lines, root);
    setScalar(lines, root + 1, end, "bootstrap-token", "");
    end = findRootEnd(lines, root);
    setScalar(lines, root + 1, end, "node-token", nodeToken.reveal());
    String updated = String.join(newline, lines);
    writeAtomically(updated);
  }

  private static int findRoot(List<String> lines) {
    for (int index = 0; index < lines.size(); index++) {
      if (lines.get(index).trim().equals("website-sync:")
          && indentation(lines.get(index)) == 0) {
        return index;
      }
    }
    throw new IllegalStateException("Configuration does not contain a website-sync root");
  }

  private static int findRootEnd(List<String> lines, int root) {
    for (int index = root + 1; index < lines.size(); index++) {
      String line = lines.get(index);
      if (!line.isBlank() && !line.stripLeading().startsWith("#")
          && indentation(line) == 0) {
        return index;
      }
    }
    return lines.size();
  }

  private static void setScalar(
      List<String> lines,
      int start,
      int end,
      String key,
      String value
  ) {
    String prefix = key + ":";
    for (int index = start; index < end; index++) {
      String line = lines.get(index);
      if (indentation(line) == 2 && line.trim().startsWith(prefix)) {
        lines.set(index, "  " + key + ": " + quote(value));
        return;
      }
    }
    lines.add(end, "  " + key + ": " + quote(value));
  }

  private void writeAtomically(String content) throws IOException {
    Path parent = this.configFile.getParent();
    if (parent == null) {
      throw new IOException("Configuration file has no parent directory");
    }
    Path temporary = parent.resolve(
        this.configFile.getFileName() + ".enroll-" + UUID.randomUUID() + ".tmp");
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    try {
      try (FileChannel channel = FileChannel.open(
          temporary,
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE)) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
        channel.force(true);
      }
      restrictPermissions(temporary);
      try {
        Files.move(
            temporary,
            this.configFile,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException error) {
        Files.move(temporary, this.configFile, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static void restrictPermissions(Path file) throws IOException {
    try {
      Files.setPosixFilePermissions(
          file,
          EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    } catch (UnsupportedOperationException ignored) {
      // Windows ACLs inherit from the plugin data directory.
    }
  }

  private static int indentation(String line) {
    int count = 0;
    while (count < line.length() && line.charAt(count) == ' ') {
      count++;
    }
    return count;
  }

  private static String quote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
