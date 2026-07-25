package io.github.addxiaoyi.starx.common.auth;

import java.net.InetAddress;

/** Scores only address properties available without an external reputation service. */
public final class AddressRisk {
  private AddressRisk() {
  }

  public static int score(InetAddress address) {
    if (address == null) return 30;
    if (address.isAnyLocalAddress() || address.isMulticastAddress()) return 40;
    if (address.isLoopbackAddress() || address.isSiteLocalAddress()
        || address.isLinkLocalAddress()) return 0;
    return 10;
  }
}
