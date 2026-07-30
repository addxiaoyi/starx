package io.github.addxiaoyi.starx.velocity.http;

import io.github.addxiaoyi.starx.velocity.config.StarxConfig;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;

final class ApiExposureResolver {

  private ApiExposureResolver() {
  }

  static Exposure resolve(StarxConfig.HttpConfig config) throws SocketException {
    return resolve(config, localAddresses());
  }

  static Exposure resolve(StarxConfig.HttpConfig config, List<InetAddress> addresses) {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(addresses, "addresses");

    if (listensPublicly(config.bind())) {
      InetAddress explicitAddress = explicitPublicBind(config.bind());
      java.util.stream.Stream<InetAddress> candidates = explicitAddress == null
          ? addresses.stream().filter(address -> matchesWildcardFamily(config.bind(), address))
          : java.util.stream.Stream.of(explicitAddress);
      InetAddress publicAddress = candidates
          .filter(ApiExposureResolver::isPublicAddress)
          .sorted(Comparator
              .comparing((InetAddress address) -> address instanceof Inet6Address)
              .thenComparing(InetAddress::getHostAddress))
          .findFirst()
          .orElse(null);
      if (publicAddress != null) {
        return new Exposure(Source.LOCAL_PUBLIC, httpUrl(publicAddress, config.port()), false);
      }
    }

    if (!config.frpPublicUrl().isBlank()) {
      return new Exposure(Source.FRP, config.frpPublicUrl(), false);
    }

    return new Exposure(Source.LOCAL_ONLY, localUrl(config), false);
  }

  private static List<InetAddress> localAddresses() throws SocketException {
    List<InetAddress> addresses = new ArrayList<>();
    Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
    if (interfaces == null) {
      return addresses;
    }
    while (interfaces.hasMoreElements()) {
      NetworkInterface network = interfaces.nextElement();
      if (!network.isUp() || network.isLoopback()) {
        continue;
      }
      Enumeration<InetAddress> networkAddresses = network.getInetAddresses();
      while (networkAddresses.hasMoreElements()) {
        addresses.add(networkAddresses.nextElement());
      }
    }
    return addresses;
  }

  private static boolean listensPublicly(String bind) {
    if ("0.0.0.0".equals(bind) || "::".equals(bind) || "[::]".equals(bind)) {
      return true;
    }
    try {
      return isPublicAddress(InetAddress.getByName(bind));
    } catch (Exception ignored) {
      return false;
    }
  }

  private static InetAddress explicitPublicBind(String bind) {
    if ("0.0.0.0".equals(bind) || "::".equals(bind) || "[::]".equals(bind)) {
      return null;
    }
    try {
      InetAddress address = InetAddress.getByName(bind);
      return isPublicAddress(address) ? address : null;
    } catch (Exception ignored) {
      return null;
    }
  }

  private static boolean matchesWildcardFamily(String bind, InetAddress address) {
    if ("0.0.0.0".equals(bind)) {
      return address instanceof Inet4Address;
    }
    if ("::".equals(bind) || "[::]".equals(bind)) {
      return address instanceof Inet6Address;
    }
    return false;
  }

  private static boolean isPublicAddress(InetAddress address) {
    if (address.isAnyLocalAddress() || address.isLoopbackAddress()
        || address.isLinkLocalAddress() || address.isSiteLocalAddress()
        || address.isMulticastAddress()) {
      return false;
    }
    byte[] bytes = address.getAddress();
    if (address instanceof Inet4Address) {
      int first = Byte.toUnsignedInt(bytes[0]);
      int second = Byte.toUnsignedInt(bytes[1]);
      int third = Byte.toUnsignedInt(bytes[2]);
      if (first == 0 || first == 10 || first == 127 || first >= 224) {
        return false;
      }
      if (first == 100 && second >= 64 && second <= 127) {
        return false;
      }
      if (first == 169 && second == 254) {
        return false;
      }
      if (first == 172 && second >= 16 && second <= 31) {
        return false;
      }
      if (first == 192 && second == 168) {
        return false;
      }
      if (first == 192 && second == 0 && (third == 0 || third == 2)) {
        return false;
      }
      if (first == 198 && (second == 18 || second == 19 || (second == 51 && third == 100))) {
        return false;
      }
      return !(first == 203 && second == 0 && third == 113);
    }
    if (address instanceof Inet6Address) {
      int first = Byte.toUnsignedInt(bytes[0]);
      boolean uniqueLocal = (first & 0xFE) == 0xFC;
      boolean documentation = first == 0x20
          && Byte.toUnsignedInt(bytes[1]) == 0x01
          && Byte.toUnsignedInt(bytes[2]) == 0x0D
          && Byte.toUnsignedInt(bytes[3]) == 0xB8;
      return !uniqueLocal && !documentation;
    }
    return false;
  }

  private static String localUrl(StarxConfig.HttpConfig config) {
    String host = config.bind();
    if ("0.0.0.0".equals(host) || "::".equals(host) || "[::]".equals(host)) {
      host = "127.0.0.1";
    }
    return "http://" + uriHost(host) + ":" + config.port();
  }

  private static String httpUrl(InetAddress address, int port) {
    return "http://" + uriHost(address.getHostAddress()) + ":" + port;
  }

  private static String uriHost(String host) {
    String withoutScope = host.replaceFirst("%.*$", "");
    return withoutScope.contains(":") ? "[" + withoutScope + "]" : withoutScope;
  }

  enum Source {
    LOCAL_PUBLIC,
    FRP,
    LOCAL_ONLY
  }

  record Exposure(Source source, String baseUrl, boolean publiclyReachable) {
    Exposure {
      Objects.requireNonNull(source, "source");
      Objects.requireNonNull(baseUrl, "baseUrl");
    }
  }
}
