package io.github.addxiaoyi.starx.velocity.network;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Selects an unused TCP port without treating a failed service bind as normal startup. */
public final class TcpPortAllocator {
  public static final int DEFAULT_SCAN_LIMIT = 256;
  private static final int FIRST_DYNAMIC_FALLBACK_PORT = 1024;
  private static final int MAX_PORT = 65_535;
  private static final int EPHEMERAL_ATTEMPTS = 16;

  private TcpPortAllocator() {
  }

  public static Selection select(
      String bind,
      int preferredPort,
      Set<Integer> reservedPorts) throws IOException {
    return select(bind, preferredPort, reservedPorts, DEFAULT_SCAN_LIMIT);
  }

  public static Selection selectWildcard(
      int preferredPort,
      Set<Integer> reservedPorts) throws IOException {
    return select("*", preferredPort, reservedPorts, DEFAULT_SCAN_LIMIT);
  }

  public static Selection select(
      String bind,
      int preferredPort,
      Set<Integer> reservedPorts,
      int fallbackRangeStart,
      int fallbackRangeEnd,
      boolean allowEphemeralFallback) throws IOException {
    requirePort(preferredPort);
    requirePort(fallbackRangeStart);
    requirePort(fallbackRangeEnd);
    if (fallbackRangeEnd < fallbackRangeStart) {
      throw new IllegalArgumentException(
          "fallbackRangeEnd must be greater than or equal to fallbackRangeStart");
    }
    String normalizedBind = normalizeBind(bind);
    Set<Integer> reserved = reservedPorts == null
        ? Set.of()
        : Set.copyOf(reservedPorts);
    List<Integer> occupied = new ArrayList<>();
    List<Integer> skippedReserved = new ArrayList<>();
    LinkedHashSet<Integer> candidates = new LinkedHashSet<>();
    candidates.add(preferredPort);
    int width = fallbackRangeEnd - fallbackRangeStart + 1;
    int first = preferredPort >= fallbackRangeStart && preferredPort <= fallbackRangeEnd
        ? preferredPort + 1
        : fallbackRangeStart;
    for (int offset = 0; offset < width; offset++) {
      int candidate = fallbackRangeStart
          + Math.floorMod(first - fallbackRangeStart + offset, width);
      candidates.add(candidate);
    }

    for (int candidate : candidates) {
      if (reserved.contains(candidate)) {
        skippedReserved.add(candidate);
        continue;
      }
      if (isAvailable(normalizedBind, candidate)) {
        return new Selection(
            preferredPort,
            candidate,
            List.copyOf(occupied),
            List.copyOf(skippedReserved),
            false);
      }
      occupied.add(candidate);
    }

    if (!allowEphemeralFallback) {
      throw new BindException(
          "No unused TCP port is available in configured fallback range "
              + fallbackRangeStart + "-" + fallbackRangeEnd);
    }
    int ephemeral = findEphemeral(normalizedBind, reserved);
    return new Selection(
        preferredPort,
        ephemeral,
        List.copyOf(occupied),
        List.copyOf(skippedReserved),
        true);
  }

  static Selection select(
      String bind,
      int preferredPort,
      Set<Integer> reservedPorts,
      int scanLimit) throws IOException {
    requirePort(preferredPort);
    if (scanLimit < 1 || scanLimit > MAX_PORT) {
      throw new IllegalArgumentException("scanLimit must be between 1 and 65535");
    }
    String normalizedBind = normalizeBind(bind);
    Set<Integer> reserved = reservedPorts == null
        ? Set.of()
        : Set.copyOf(reservedPorts);
    List<Integer> occupied = new ArrayList<>();
    List<Integer> skippedReserved = new ArrayList<>();

    for (int offset = 0; offset < scanLimit; offset++) {
      int candidate = candidate(preferredPort, offset);
      if (reserved.contains(candidate)) {
        skippedReserved.add(candidate);
        continue;
      }
      if (isAvailable(normalizedBind, candidate)) {
        return new Selection(
            preferredPort,
            candidate,
            List.copyOf(occupied),
            List.copyOf(skippedReserved),
            false);
      }
      occupied.add(candidate);
    }

    int ephemeral = findEphemeral(normalizedBind, reserved);
    return new Selection(
        preferredPort,
        ephemeral,
        List.copyOf(occupied),
        List.copyOf(skippedReserved),
        true);
  }

  static boolean isAvailable(String bind, int port) throws IOException {
    requirePort(port);
    try (ServerSocket socket = new ServerSocket()) {
      socket.setReuseAddress(false);
      socket.bind(socketAddress(normalizeBind(bind), port), 1);
      return true;
    } catch (BindException occupied) {
      return false;
    }
  }

  private static int findEphemeral(String bind, Set<Integer> reserved) throws IOException {
    IOException lastError = null;
    for (int attempt = 0; attempt < EPHEMERAL_ATTEMPTS; attempt++) {
      try (ServerSocket socket = new ServerSocket()) {
        socket.setReuseAddress(false);
        socket.bind(socketAddress(bind, 0), 1);
        int port = socket.getLocalPort();
        if (!reserved.contains(port)) {
          return port;
        }
      } catch (IOException error) {
        lastError = error;
      }
    }
    throw new IOException(
        "Unable to allocate an unused TCP port"
            + (lastError == null ? "" : ": " + lastError.getMessage()),
        lastError);
  }

  private static int candidate(int preferredPort, int offset) {
    if (offset == 0) {
      return preferredPort;
    }
    int start = preferredPort >= FIRST_DYNAMIC_FALLBACK_PORT
        ? preferredPort + 1
        : FIRST_DYNAMIC_FALLBACK_PORT;
    int range = MAX_PORT - FIRST_DYNAMIC_FALLBACK_PORT + 1;
    return FIRST_DYNAMIC_FALLBACK_PORT
        + Math.floorMod(start - FIRST_DYNAMIC_FALLBACK_PORT + offset - 1, range);
  }

  private static InetSocketAddress socketAddress(String bind, int port) {
    if ("*".equals(bind)) {
      return new InetSocketAddress(port);
    }
    return new InetSocketAddress(bind, port);
  }

  private static String normalizeBind(String bind) {
    String value = Objects.requireNonNullElse(bind, "").trim();
    if (value.isBlank()) {
      return "127.0.0.1";
    }
    if (value.startsWith("[") && value.endsWith("]")) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }

  private static void requirePort(int port) {
    if (port < 1 || port > MAX_PORT) {
      throw new IllegalArgumentException("port must be between 1 and 65535");
    }
  }

  public record Selection(
      int preferredPort,
      int selectedPort,
      List<Integer> occupiedPorts,
      List<Integer> reservedPorts,
      boolean ephemeralFallback) {
    public Selection {
      requirePort(preferredPort);
      requirePort(selectedPort);
      occupiedPorts = occupiedPorts == null ? List.of() : List.copyOf(occupiedPorts);
      reservedPorts = reservedPorts == null ? List.of() : List.copyOf(reservedPorts);
    }

    public boolean changed() {
      return this.preferredPort != this.selectedPort;
    }

    public String mode() {
      if (!changed()) {
        return "preferred";
      }
      return this.ephemeralFallback ? "ephemeral_fallback" : "next_available";
    }

    public Selection withAdditionalUnavailable(Set<Integer> additional) {
      if (additional == null || additional.isEmpty()) {
        return this;
      }
      LinkedHashSet<Integer> merged = new LinkedHashSet<>(this.occupiedPorts);
      merged.addAll(additional);
      merged.remove(this.selectedPort);
      return new Selection(
          this.preferredPort,
          this.selectedPort,
          List.copyOf(merged),
          this.reservedPorts,
          this.ephemeralFallback);
    }
  }
}
