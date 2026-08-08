package io.github.addxiaoyi.starx.velocity.module.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

public final class ExternalHandshake {

  public static final String KEY_FILE_NAME = "external-handshake.key";
  private static final String MARKER = "starx-handshake";
  private static final int KEY_BYTES = 32;
  private static final int KEY_LENGTH = Base64.getUrlEncoder()
      .withoutPadding()
      .encodeToString(new byte[KEY_BYTES])
      .length();
  private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9_-]{" + KEY_LENGTH + "}");
  private static final ExternalHandshake DISABLED = new ExternalHandshake(null, "", new byte[0]);

  private final Path keyFile;
  private final String key;
  private final byte[] keyBytes;

  private ExternalHandshake(Path keyFile, String key, byte[] keyBytes) {
    this.keyFile = keyFile;
    this.key = key;
    this.keyBytes = keyBytes;
  }

  public static ExternalHandshake open(Path dataDirectory) throws IOException {
    return open(dataDirectory, new SecureRandom());
  }

  static ExternalHandshake open(Path dataDirectory, SecureRandom random) throws IOException {
    Path directory = Objects.requireNonNull(dataDirectory, "dataDirectory")
        .toAbsolutePath()
        .normalize();
    SecureRandom source = Objects.requireNonNull(random, "random");
    Files.createDirectories(directory);

    Path keyFile = directory.resolve(KEY_FILE_NAME);
    String key = loadOrCreate(keyFile, source);
    return new ExternalHandshake(keyFile, key, key.getBytes(StandardCharsets.UTF_8));
  }

  static ExternalHandshake disabled() {
    return DISABLED;
  }

  public String key() {
    return this.key;
  }

  public Path keyFile() {
    if (this.keyFile == null) {
      throw new IllegalStateException("External handshake is disabled");
    }
    return this.keyFile;
  }

  public boolean matches(String rawVirtualHost) {
    if (this.keyFile == null || rawVirtualHost == null) {
      return false;
    }
    String[] fields = rawVirtualHost.split("\u0000", -1);
    if (fields.length != 3 || fields[0].isBlank() || !MARKER.equals(fields[1])) {
      return false;
    }
    return MessageDigest.isEqual(
        this.keyBytes,
        fields[2].getBytes(StandardCharsets.UTF_8));
  }

  private static String loadOrCreate(Path keyFile, SecureRandom random) throws IOException {
    if (Files.isSymbolicLink(keyFile)) {
      throw new IOException("External handshake key must not be a symbolic link: " + keyFile);
    }
    if (Files.notExists(keyFile, LinkOption.NOFOLLOW_LINKS)) {
      String generated = generateKey(random);
      try {
        writeNewKey(keyFile, generated);
        return generated;
      } catch (FileAlreadyExistsException ignored) {
        // Another startup won the first-create race; validate the stored key below.
      }
    }
    String stored = Files.readString(keyFile, StandardCharsets.UTF_8).trim();
    decodeKey(stored);
    return stored;
  }

  private static String generateKey(SecureRandom random) {
    byte[] bytes = new byte[KEY_BYTES];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static byte[] decodeKey(String key) throws IOException {
    if (key == null || !KEY_PATTERN.matcher(key).matches()) {
      throw new IOException(
          "External handshake key must be a 32-byte URL-safe base64 value");
    }
    try {
      byte[] bytes = Base64.getUrlDecoder().decode(key);
      if (bytes.length != KEY_BYTES) {
        throw new IOException(
            "External handshake key must decode to exactly 32 bytes");
      }
      return bytes;
    } catch (IllegalArgumentException error) {
      throw new IOException("External handshake key is not valid base64", error);
    }
  }

  private static void writeNewKey(Path keyFile, String key) throws IOException {
    Path parent = keyFile.toAbsolutePath().normalize().getParent();
    if (parent == null) {
      throw new IOException("External handshake key has no parent directory: " + keyFile);
    }
    Path temp = Files.createTempFile(parent, ".external-handshake-", ".tmp");
    boolean moved = false;
    try {
      Files.writeString(
          temp,
          key + System.lineSeparator(),
          StandardCharsets.UTF_8,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE);
      try {
        Files.move(temp, keyFile, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException ignored) {
        Files.move(temp, keyFile);
      }
      moved = true;
    } finally {
      if (!moved) {
        Files.deleteIfExists(temp);
      }
    }
  }
}
