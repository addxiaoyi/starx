package io.github.addxiaoyi.starx.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class RegionKeyTest {
  @Test
  void keepsOnlyTheCoarseNetworkPrefix() throws Exception {
    assertEquals("net=203.0.113/24", RegionKey.from(InetAddress.getByName("203.0.113.42")));
    assertEquals("net=32.1.13.184.0.0.0.0/64", RegionKey.from(
        InetAddress.getByName("2001:db8::42")));
  }
}
