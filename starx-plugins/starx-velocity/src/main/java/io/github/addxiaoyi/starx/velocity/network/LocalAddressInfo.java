package io.github.addxiaoyi.starx.velocity.network;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;

/**
 * Locally classifies an IP address without making any network requests.
 * PUBLIC means globally routable by address policy only; end-to-end reachability
 * still requires an independent external observation.
 */
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

  public boolean isGloballyRoutable() {
    return this.scope == Scope.PUBLIC;
  }

  public int baseRiskScore() {
    return this.scope == Scope.INVALID || this.scope == Scope.RESERVED ? 20 : 0;
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

    int first = octets[0];
    int second = octets[1];
    int third = octets[2];
    if (first == 127) {
      return Scope.LOOPBACK;
    }
    if (first == 10
        || (first == 172 && second >= 16 && second <= 31)
        || (first == 192 && second == 168)) {
      return Scope.PRIVATE;
    }
    if (first == 100 && second >= 64 && second <= 127) {
      return Scope.CGNAT;
    }
    if (first == 169 && second == 254) {
      return Scope.LINK_LOCAL;
    }
    if (first == 198 && (second == 18 || second == 19)) {
      return Scope.BENCHMARK;
    }
    if ((first == 192 && second == 0 && third == 2)
        || (first == 198 && second == 51 && third == 100)
        || (first == 203 && second == 0 && third == 113)) {
      return Scope.DOCUMENTATION;
    }
    if (first >= 224 && first <= 239) {
      return Scope.MULTICAST;
    }
    if (first == 0 || first >= 240
        || (first == 192 && second == 0 && third == 0)
        || (first == 192 && second == 88 && third == 99)) {
      return Scope.RESERVED;
    }
    return Scope.PUBLIC;
  }

  private static Scope parseIpv6(String address) {
    try {
      InetAddress parsed = InetAddress.getByName(address);
      if (!(parsed instanceof Inet6Address)) {
        return Scope.INVALID;
      }
      if (parsed.isAnyLocalAddress()) {
        return Scope.RESERVED;
      }
      if (parsed.isLoopbackAddress()) {
        return Scope.LOOPBACK;
      }
      if (parsed.isLinkLocalAddress()) {
        return Scope.LINK_LOCAL;
      }
      byte[] bytes = parsed.getAddress();
      int first = Byte.toUnsignedInt(bytes[0]);
      if ((first & 0xfe) == 0xfc) {
        return Scope.PRIVATE;
      }
      if (first == 0xff) {
        return Scope.MULTICAST;
      }
      boolean documentation = first == 0x20
          && Byte.toUnsignedInt(bytes[1]) == 0x01
          && Byte.toUnsignedInt(bytes[2]) == 0x0d
          && Byte.toUnsignedInt(bytes[3]) == 0xb8;
      return documentation ? Scope.DOCUMENTATION : Scope.PUBLIC;
    } catch (UnknownHostException error) {
      return Scope.INVALID;
    }
  }

  public enum Scope {
    LOOPBACK("本机"),
    PRIVATE("内网"),
    CGNAT("运营商级内网"),
    LINK_LOCAL("链路本地"),
    DOCUMENTATION("文档专用地址"),
    BENCHMARK("测试专用地址"),
    MULTICAST("组播地址"),
    RESERVED("保留地址"),
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
