package io.github.addxiaoyi.starx.common.auth;

import java.net.InetAddress;
import java.util.Objects;

/** Coarse network bucket used for familiar-region decisions; never persists a full IP. */
public final class RegionKey {
  private RegionKey() {}

  public static String from(InetAddress address) {
    Objects.requireNonNull(address, "address");
    byte[] bytes = address.getAddress();
    int prefix = bytes.length == 4 ? 3 : 8;
    StringBuilder key = new StringBuilder("net=");
    for (int index = 0; index < prefix; index++) {
      if (index > 0) key.append('.');
      key.append(bytes[index] & 0xff);
    }
    return key.append(bytes.length == 4 ? "/24" : "/64").toString();
  }
}
