package io.github.addxiaoyi.starx.velocity.network;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class LocalAddressInfoTest {

  @Test
  void classifiesIpv4ScopesWithoutNetworkRequests() {
    assertAll(
        () -> assertScope("127.0.0.1", LocalAddressInfo.Scope.LOOPBACK, "本机"),
        () -> assertScope("10.2.3.4", LocalAddressInfo.Scope.PRIVATE, "内网"),
        () -> assertScope("172.16.0.1", LocalAddressInfo.Scope.PRIVATE, "内网"),
        () -> assertScope("172.31.255.255", LocalAddressInfo.Scope.PRIVATE, "内网"),
        () -> assertScope("192.168.1.2", LocalAddressInfo.Scope.PRIVATE, "内网"),
        () -> assertScope("172.32.0.1", LocalAddressInfo.Scope.PUBLIC, "公网"),
        () -> assertScope("8.8.8.8", LocalAddressInfo.Scope.PUBLIC, "公网"));
  }

  @Test
  void classifiesIpv6AndInvalidValuesLocally() {
    assertAll(
        () -> assertScope("::1", LocalAddressInfo.Scope.LOOPBACK, "本机"),
        () -> assertScope("fc00::1", LocalAddressInfo.Scope.PRIVATE, "内网"),
        () -> assertScope("fe80::1", LocalAddressInfo.Scope.LINK_LOCAL, "链路本地"),
        () -> assertScope("not-an-ip", LocalAddressInfo.Scope.INVALID, "地址不可用"),
        () -> assertScope("999.1.1.1", LocalAddressInfo.Scope.INVALID, "地址不可用"));
  }

  private static void assertScope(
      String address,
      LocalAddressInfo.Scope scope,
      String label) {
    LocalAddressInfo info = LocalAddressInfo.parse(address);
    assertEquals(scope, info.scope());
    assertEquals(label, info.locationLabel());
  }
}
