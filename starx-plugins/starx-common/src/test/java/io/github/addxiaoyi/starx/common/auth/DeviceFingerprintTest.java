package io.github.addxiaoyi.starx.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

final class DeviceFingerprintTest {
  @Test
  void keepsAClientStableWithinAnIpv4NetworkWithoutRetainingTheFullAddress() throws Exception {
    String first = DeviceFingerprint.from(
        InetAddress.getByName("203.0.113.9"), 770, false, "play.star-mc.top");
    String second = DeviceFingerprint.from(
        InetAddress.getByName("203.0.113.87"), 770, false, "play.star-mc.top");

    assertEquals(first, second);
    assertFalse(first.contains("203.0.113.9"));
  }

  @Test
  void separatesMeaningfullyDifferentClientSignals() throws Exception {
    InetAddress address = InetAddress.getByName("203.0.113.9");
    String base = DeviceFingerprint.from(address, 770, false, "play.star-mc.top");

    assertNotEquals(base, DeviceFingerprint.from(address, 769, false, "play.star-mc.top"));
    assertNotEquals(base, DeviceFingerprint.from(address, 770, true, "play.star-mc.top"));
    assertNotEquals(base, DeviceFingerprint.from(address, 770, false, "max.star-mc.top"));
  }

  @Test
  void keepsAClientStableWithinAnIpv6Slash64() throws Exception {
    String first = DeviceFingerprint.from(
        InetAddress.getByName("2001:db8:1234:5678::1"), 770, true, "play.star-mc.top");
    String second = DeviceFingerprint.from(
        InetAddress.getByName("2001:db8:1234:5678::abcd"), 770, true, "play.star-mc.top");

    assertEquals(first, second);
  }
}
