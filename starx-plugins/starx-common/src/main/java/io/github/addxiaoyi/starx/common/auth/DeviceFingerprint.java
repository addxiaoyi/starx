package io.github.addxiaoyi.starx.common.auth;

import java.net.InetAddress;
import java.util.Locale;
import java.util.Objects;

/** Builds a coarse, account-scoped signal; Minecraft does not expose hardware identity. */
public final class DeviceFingerprint {
  private DeviceFingerprint() {}

  public static String from(
      InetAddress address, int protocolVersion, boolean onlineMode, String virtualHost) {
    Objects.requireNonNull(address, "address");
    byte[] bytes = address.getAddress();
    int prefixLength = bytes.length == 4 ? 3 : 8;
    StringBuilder network = new StringBuilder(prefixLength * 2);
    for (int index = 0; index < prefixLength; index++) {
      if (index > 0) network.append('.');
      network.append(bytes[index] & 0xff);
    }
    String host = virtualHost == null ? "unknown" : virtualHost.trim().toLowerCase(Locale.ROOT);
    if (host.isBlank()) host = "unknown";
    return "net=" + network + "/" + (bytes.length == 4 ? "24" : "64")
        + "|proto=" + protocolVersion + "|online=" + onlineMode + "|host=" + host;
  }
}
