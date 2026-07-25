package io.github.addxiaoyi.starx.velocity.network;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;

public record LocalAddressInfo(String address, Scope scope) {

  public LocalAddressInfo {
    address = Objects.requireNonNull(address, "address");
    scope = Objects.requireNonNull(scope, "scope");
  }

  public static LocalAddressInfo parse(String rawAddress) {
    if (rawAddress == null || rawAddress.isBlank()) {
      return new LocalAddressInfo("", Scope.INVALID);
    }
    String address = rawAddress.trim();
    Scope ipv4 = parseIpv4(address);
    if (ipv4 != null) {
      return new LocalAddressInfo(address, ipv4);
    }
    if (!address.contains(":") || !address.matches("[0-9a-fA-F:.%]+")) {
      return new LocalAddressInfo(address, Scope.INVALID);
    }
    return new LocalAddressInfo(address, parseIpv6(address));
  }

  public String locationLabel() {
    return this.scope.label();
  }

  public int baseRiskScore() {
    return this.scope == Scope.INVALID ? 20 : 0;
  }

  private static Scope parseIpv4(String address) {
    if (!address.matches("[0-9.]+")) {
      return null;
    }
    String[] parts = address.split("\\.", -1);
    if (parts.length != 4) {
      return Scope.INVALID;
    }
    int[] octets = new int[4];
    for (int index = 0; index < parts.length; index++) {
      try {
        octets[index] = Integer.parseInt(parts[index]);
      } catch (NumberFormatException error) {
        return Scope.INVALID;
      }
      if (octets[index] < 0 || octets[index] > 255) {
        return Scope.INVALID;
      }
    }
    if (octets[0] == 127) {
      return Scope.LOOPBACK;
    }
    if (octets[0] == 10
        || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
        || (octets[0] == 192 && octets[1] == 168)) {
      return Scope.PRIVATE;
    }
    if (octets[0] == 169 && octets[1] == 254) {
      return Scope.LINK_LOCAL;
    }
    return Scope.PUBLIC;
  }

  private static Scope parseIpv6(String address) {
    try {
      InetAddress parsed = InetAddress.getByName(address);
      if (!(parsed instanceof Inet6Address)) {
        return Scope.INVALID;
      }
      if (parsed.isLoopbackAddress()) {
        return Scope.LOOPBACK;
      }
      if (parsed.isLinkLocalAddress()) {
        return Scope.LINK_LOCAL;
      }
      byte[] bytes = parsed.getAddress();
      boolean uniqueLocal = (bytes[0] & 0xfe) == 0xfc;
      return uniqueLocal || parsed.isSiteLocalAddress() ? Scope.PRIVATE : Scope.PUBLIC;
    } catch (UnknownHostException error) {
      return Scope.INVALID;
    }
  }

  public enum Scope {
    LOOPBACK("本机"),
    PRIVATE("内网"),
    LINK_LOCAL("链路本地"),
    PUBLIC("公网"),
    INVALID("地址不可用");

    private final String label;

    Scope(String label) {
      this.label = label;
    }

    public String label() {
      return this.label;
    }
  }
}
