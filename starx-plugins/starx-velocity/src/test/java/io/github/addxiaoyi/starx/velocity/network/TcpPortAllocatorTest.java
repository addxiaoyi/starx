package io.github.addxiaoyi.starx.velocity.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class TcpPortAllocatorTest {

  @Test
  void skipsAnOccupiedPreferredPort() throws Exception {
    try (ServerSocket occupied = new ServerSocket(
        0, 1, InetAddress.getByName("127.0.0.1"))) {
      int preferred = occupied.getLocalPort();

      TcpPortAllocator.Selection selection = TcpPortAllocator.select(
          "127.0.0.1", preferred, Set.of(), 32);

      assertNotEquals(preferred, selection.selectedPort());
      assertTrue(selection.changed());
      assertTrue(selection.occupiedPorts().contains(preferred));
      assertTrue(TcpPortAllocator.isAvailable(
          "127.0.0.1", selection.selectedPort()));
    }
  }

  @Test
  void skipsReservedPortsWithoutReportingThemAsOccupied() throws Exception {
    int preferred;
    try (ServerSocket available = new ServerSocket(
        0, 1, InetAddress.getByName("127.0.0.1"))) {
      preferred = available.getLocalPort();
    }

    TcpPortAllocator.Selection selection = TcpPortAllocator.select(
        "127.0.0.1", preferred, Set.of(preferred), 32);

    assertNotEquals(preferred, selection.selectedPort());
    assertEquals(Set.of(preferred), Set.copyOf(selection.reservedPorts()));
    assertFalse(selection.occupiedPorts().contains(preferred));
  }

  @Test
  void wildcardSelectionDetectsAListenerOnAllInterfaces() throws Exception {
    try (ServerSocket occupied = new ServerSocket(0)) {
      int preferred = occupied.getLocalPort();

      TcpPortAllocator.Selection selection = TcpPortAllocator.selectWildcard(
          preferred, Set.of());

      assertNotEquals(preferred, selection.selectedPort());
      assertTrue(selection.occupiedPorts().contains(preferred));
    }
  }

  @Test
  void boundedSelectionUsesOnlyConfiguredFallbackRange() throws Exception {
    int fallback;
    try (ServerSocket candidate = new ServerSocket(
        0, 1, InetAddress.getByName("127.0.0.1"))) {
      fallback = candidate.getLocalPort();
    }
    try (ServerSocket occupied = new ServerSocket(
        0, 1, InetAddress.getByName("127.0.0.1"))) {
      TcpPortAllocator.Selection selection = TcpPortAllocator.select(
          "127.0.0.1",
          occupied.getLocalPort(),
          Set.of(),
          fallback,
          fallback,
          false);

      assertEquals(fallback, selection.selectedPort());
      assertFalse(selection.ephemeralFallback());
    }
  }

  @Test
  void boundedSelectionFailsWhenRangeIsExhausted() throws Exception {
    int fallback;
    try (ServerSocket candidate = new ServerSocket(
        0, 1, InetAddress.getByName("127.0.0.1"))) {
      fallback = candidate.getLocalPort();
    }
    try (ServerSocket occupied = new ServerSocket(
        0, 1, InetAddress.getByName("127.0.0.1"))) {
      assertThrows(
          BindException.class,
          () -> TcpPortAllocator.select(
              "127.0.0.1",
              occupied.getLocalPort(),
              Set.of(fallback),
              fallback,
              fallback,
              false));
    }
  }

  @Test
  void ephemeralPolicyEscapesOnlyAfterBoundedRangeIsExhausted() throws Exception {
    int fallback;
    try (ServerSocket candidate = new ServerSocket(
        0, 1, InetAddress.getByName("127.0.0.1"))) {
      fallback = candidate.getLocalPort();
    }
    try (ServerSocket occupied = new ServerSocket(
        0, 1, InetAddress.getByName("127.0.0.1"))) {
      TcpPortAllocator.Selection selection = TcpPortAllocator.select(
          "127.0.0.1",
          occupied.getLocalPort(),
          Set.of(fallback),
          fallback,
          fallback,
          true);

      assertTrue(selection.ephemeralFallback());
      assertNotEquals(occupied.getLocalPort(), selection.selectedPort());
      assertNotEquals(fallback, selection.selectedPort());
    }
  }

  @Test
  void keepsThePreferredPortWhenItIsAvailable() throws Exception {
    int preferred;
    try (ServerSocket available = new ServerSocket(
        0, 1, InetAddress.getByName("127.0.0.1"))) {
      preferred = available.getLocalPort();
    }

    TcpPortAllocator.Selection selection = TcpPortAllocator.select(
        "127.0.0.1", preferred, Set.of(), 32);

    assertEquals(preferred, selection.selectedPort());
    assertFalse(selection.changed());
    assertEquals("preferred", selection.mode());
  }
}
