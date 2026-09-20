package io.github.addxiaoyi.starx.velocity.module.skin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

final class BackendSkinFallbackCache {
  private static final int FORMAT_VERSION = 1;
  private static final int DEFAULT_MAX_ENTRIES = 4096;

  private final Path directory;
  private final Duration ttl;
  private final int maxEntries;
  private final Logger logger;

  BackendSkinFallbackCache(Path directory, Duration ttl, Logger logger) {
    this(directory, ttl, DEFAULT_MAX_ENTRIES, logger);
  }

  BackendSkinFallbackCache(Path directory, Duration ttl, int maxEntries, Logger logger) {
    if (directory == null) throw new IllegalArgumentException("directory is required");
    if (ttl == null || ttl.isZero() || ttl.isNegative()) {
      throw new IllegalArgumentException("TTL must be positive");
    }
    if (maxEntries <= 0) throw new IllegalArgumentException("maxEntries must be positive");
    this.directory = directory.toAbsolutePath().normalize();
    this.ttl = ttl;
    this.maxEntries = maxEntries;
    this.logger = logger == null ? Logger.getLogger(getClass().getName()) : logger;
  }

  synchronized void put(BackendSkinData skin, Instant now) {
    if (skin == null || now == null || !valid(skin)) return;
    Instant expiresAt = now.plus(this.ttl);
    Properties properties = new Properties();
    properties.setProperty("version", Integer.toString(FORMAT_VERSION));
    properties.setProperty("uuid", skin.uuid().toString());
    properties.setProperty("name", skin.name());
    properties.setProperty("provider", skin.provider());
    properties.setProperty("value", skin.value());
    properties.setProperty("signature", skin.signature() == null ? "" : skin.signature());
    properties.setProperty("expiresAt", expiresAt.toString());
    properties.setProperty("checksum", checksum(canonical(properties)));
    try {
      Files.createDirectories(this.directory);
      Path target = fileFor(skin.uuid());
      Path temporary = Files.createTempFile(this.directory, skin.uuid() + "-", ".tmp");
      try (OutputStream output = Files.newOutputStream(temporary)) {
        properties.store(output, "StarX backend skin fallback cache");
      }
      try {
        Files.move(temporary, target,
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException ignored) {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
      } finally {
        Files.deleteIfExists(temporary);
      }
      prune(now);
    } catch (IOException error) {
      this.logger.log(Level.WARNING, "Unable to persist backend skin fallback", error);
    }
  }

  synchronized Optional<BackendSkinData> find(UUID uuid, String expectedName, Instant now) {
    if (uuid == null || now == null) return Optional.empty();
    Path file = fileFor(uuid);
    if (!Files.isRegularFile(file)) return Optional.empty();
    Properties properties = new Properties();
    try (InputStream input = Files.newInputStream(file)) {
      properties.load(input);
      if (!Integer.toString(FORMAT_VERSION).equals(properties.getProperty("version"))) {
        return reject(file);
      }
      UUID storedUuid = UUID.fromString(properties.getProperty("uuid", ""));
      Instant expiresAt = Instant.parse(properties.getProperty("expiresAt", ""));
      String name = properties.getProperty("name", "").trim();
      String provider = properties.getProperty("provider", "").trim();
      String value = properties.getProperty("value", "").trim();
      String signature = properties.getProperty("signature", "");
      String storedChecksum = properties.getProperty("checksum", "");
      if (!storedUuid.equals(uuid)
          || !expiresAt.isAfter(now)
          || (expectedName != null && !expectedName.isBlank()
              && !name.equalsIgnoreCase(expectedName.trim()))
          || !storedChecksum.equals(checksum(canonical(properties)))) {
        return reject(file);
      }
      BackendSkinData skin = new BackendSkinData(storedUuid, name, provider, value, signature);
      return valid(skin) ? Optional.of(skin) : reject(file);
    } catch (IOException | IllegalArgumentException error) {
      this.logger.log(Level.FINE, "Rejected invalid backend skin fallback " + file, error);
      return reject(file);
    }
  }

  private void prune(Instant now) throws IOException {
    if (!Files.isDirectory(this.directory)) return;
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(this.directory, "*.properties")) {
      java.util.List<Path> files = new java.util.ArrayList<>();
      for (Path path : stream) files.add(path);
      for (Path path : files) {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
          properties.load(input);
          if (!Instant.parse(properties.getProperty("expiresAt", "")).isAfter(now)) {
            Files.deleteIfExists(path);
          }
        } catch (IOException | IllegalArgumentException invalid) {
          Files.deleteIfExists(path);
        }
      }
      try (java.util.stream.Stream<Path> remaining = Files.list(this.directory)) {
        java.util.List<Path> sorted = remaining
            .filter(path -> path.getFileName().toString().endsWith(".properties"))
            .sorted(Comparator.comparingLong(this::lastModifiedSafe))
            .toList();
        for (int index = 0; index < sorted.size() - this.maxEntries; index++) {
          Files.deleteIfExists(sorted.get(index));
        }
      }
    }
  }

  private long lastModifiedSafe(Path path) {
    try {
      return Files.getLastModifiedTime(path).toMillis();
    } catch (IOException ignored) {
      return Long.MIN_VALUE;
    }
  }

  private Optional<BackendSkinData> reject(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException ignored) {
      // A rejected entry is never returned even if deletion is unavailable.
    }
    return Optional.empty();
  }

  private Path fileFor(UUID uuid) {
    Path file = this.directory.resolve(uuid.toString().toLowerCase(Locale.ROOT) + ".properties")
        .normalize();
    if (!file.startsWith(this.directory)) {
      throw new IllegalArgumentException("Cache path escaped the data directory");
    }
    return file;
  }

  private static boolean valid(BackendSkinData skin) {
    return skin.uuid() != null
        && skin.name() != null && !skin.name().isBlank()
        && skin.provider() != null && !skin.provider().isBlank()
        && skin.value() != null && !skin.value().isBlank();
  }

  private static String canonical(Properties properties) {
    return String.join("\n",
        properties.getProperty("version", ""),
        properties.getProperty("uuid", ""),
        properties.getProperty("name", ""),
        properties.getProperty("provider", ""),
        properties.getProperty("value", ""),
        properties.getProperty("signature", ""),
        properties.getProperty("expiresAt", ""));
  }

  private static String checksum(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }
}
