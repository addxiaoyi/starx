package io.github.addxiaoyi.starx.velocity.network;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class LocalAddressInfoTest {

  @Test
  void classifiesIpv4ScopesWithoutNetworkRequests() {
    assertAll(
        () -> assertScope("127.0.0.1", LocalAddressInfo.Scope.LOOPBACK, "本机"),
        () -> assertScope("10.2.3.4", LocalAddressInfo.Scope.PRIVATE, "内网"),
        () -> assertScope("172.31.255.255", LocalAddressInfo.Scope.PRIVATE, "内网"),
        () -> assertScope("192.168.1.2", LocalAddressInfo.Scope.PRIVATE, "内网"),
        () -> assertScope("100.64.0.1", LocalAddressInfo.Scope.CGNAT, "运营商级内网"),
        () -> assertScope("100.127.255.254", LocalAddressInfo.Scope.CGNAT, "运营商级内网"),
        () -> assertScope("198.18.0.1", LocalAddressInfo.Scope.BENCHMARK, "测试专用地址"),
        () -> assertScope("192.0.2.20", LocalAddressInfo.Scope.DOCUMENTATION, "文档专用地址"),
        () -> assertScope("224.0.0.1", LocalAddressInfo.Scope.MULTICAST, "组播地址"),
        () -> assertScope("240.0.0.1", LocalAddressInfo.Scope.RESERVED, "保留地址"),
        () -> assertScope("172.32.0.1", LocalAddressInfo.Scope.PUBLIC, "公网"),
        () -> assertScope("8.8.8.8", LocalAddressInfo.Scope.PUBLIC, "公网"));
  }

  @Test
  void classifiesIpv6AndInvalidValuesLocally() {
    assertAll(
        () -> assertScope("::", LocalAddressInfo.Scope.RESERVED, "保留地址"),
        () -> assertScope("::1", LocalAddressInfo.Scope.LOOPBACK, "本机"),
        () -> assertScope("fc00::1", LocalAddressInfo.Scope.PRIVATE, "内网"),
        () -> assertScope("fe80::1", LocalAddressInfo.Scope.LINK_LOCAL, "链路本地"),
        () -> assertScope("2001:db8::1", LocalAddressInfo.Scope.DOCUMENTATION, "文档专用地址"),
        () -> assertScope("ff02::1", LocalAddressInfo.Scope.MULTICAST, "组播地址"),
        () -> assertScope("not-an-ip", LocalAddressInfo.Scope.INVALID, "地址不可用"),
        () -> assertScope("999.1.1.1", LocalAddressInfo.Scope.INVALID, "地址不可用"));
  }

  @Test
  void onlyPublicScopeIsConsideredGloballyRoutable() {
    assertTrue(LocalAddressInfo.parse("1.1.1.1").isGloballyRoutable());
    assertFalse(LocalAddressInfo.parse("100.64.1.1").isGloballyRoutable());
    assertFalse(LocalAddressInfo.parse("203.0.113.1").isGloballyRoutable());
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
