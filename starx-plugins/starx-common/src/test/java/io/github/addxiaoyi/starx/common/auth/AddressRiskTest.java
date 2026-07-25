package io.github.addxiaoyi.starx.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class AddressRiskTest {
  @Test
  void classifiesOnlyLocallyVerifiableAddressSignals() throws Exception {
    assertEquals(0, AddressRisk.score(InetAddress.getByName("127.0.0.1")));
    assertEquals(0, AddressRisk.score(InetAddress.getByName("192.168.1.8")));
    assertEquals(10, AddressRisk.score(InetAddress.getByName("203.0.113.8")));
    assertEquals(30, AddressRisk.score(null));
    assertEquals(40, AddressRisk.score(InetAddress.getByName("0.0.0.0")));
  }
}
