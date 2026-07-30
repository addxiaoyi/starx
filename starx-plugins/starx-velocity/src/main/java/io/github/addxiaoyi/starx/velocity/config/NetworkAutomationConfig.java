package io.github.addxiaoyi.starx.velocity.config;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Configuration for conservative public exposure, FRP and ACME assistance. */
public record NetworkAutomationConfig(
    boolean enabled,
    String reportFile,
    PublicAddress publicAddress,
    Frp frp,
    Certificate certificate) {

  public NetworkAutomationConfig {
    reportFile = normalize(reportFile, "network-automation.json");
    publicAddress = publicAddress == null ? PublicAddress.defaults() : publicAddress;
    frp = frp == null ? Frp.defaults() : frp;
    certificate = certificate == null ? Certificate.defaults() : certificate;
  }

  public static NetworkAutomationConfig defaults() {
    return new NetworkAutomationConfig(
        true,
        "network-automation.json",
        PublicAddress.defaults(),
        Frp.defaults(),
        Certificate.defaults());
  }

  public record PublicAddress(
      boolean enabled,
      int minimumAgreement,
      int timeoutMs,
      List<String> endpoints) {

    public PublicAddress {
      if (minimumAgreement < 2 || minimumAgreement > 8) {
        throw new IllegalArgumentException(
            "network-automation.public-address.minimum-agreement must be between 2 and 8");
      }
      if (timeoutMs < 250 || timeoutMs > 30000) {
        throw new IllegalArgumentException(
            "network-automation.public-address.timeout-ms must be between 250 and 30000");
      }
      endpoints = endpoints == null ? List.of() : endpoints.stream()
          .filter(Objects::nonNull)
          .map(String::trim)
          .filter(value -> !value.isBlank())
          .distinct()
          .toList();
      if (enabled && endpoints.size() < minimumAgreement) {
        throw new IllegalArgumentException(
            "network-automation.public-address.endpoints must contain enough independent sources");
      }
    }

    public static PublicAddress defaults() {
      return new PublicAddress(true, 2, 2500, List.of(
          "https://api64.ipify.org",
          "https://checkip.amazonaws.com",
          "https://icanhazip.com"));
    }
  }

  public record Frp(
      Mode mode,
      String publicHost,
      String publicScheme,
      String publicUrl,
      String proxyName,
      String localAddress,
      int localPort,
      int remotePort,
      String frpcCommand,
      String mainConfigFile,
      String managedConfigFile,
      boolean autoApply) {

    public Frp {
      mode = mode == null ? Mode.DETECT : mode;
      publicHost = blankToEmpty(publicHost);
      publicScheme = normalize(publicScheme, "http").toLowerCase(Locale.ROOT);
      if (!publicScheme.equals("http") && !publicScheme.equals("https")) {
        throw new IllegalArgumentException(
            "network-automation.frp.public-scheme must be http or https");
      }
      publicUrl = blankToEmpty(publicUrl);
      proxyName = normalize(proxyName, "starx-api");
      if (!proxyName.matches("[A-Za-z0-9._-]{1,64}")) {
        throw new IllegalArgumentException(
            "network-automation.frp.proxy-name contains unsupported characters");
      }
      localAddress = normalize(localAddress, "127.0.0.1");
      requirePort(localPort, "network-automation.frp.local-port");
      if (remotePort < 0 || remotePort > 65535) {
        throw new IllegalArgumentException(
            "network-automation.frp.remote-port must be between 0 and 65535");
      }
      if (mode == Mode.MANAGED && remotePort != 0) {
        throw new IllegalArgumentException(
            "managed FRP must use remote-port 0 so frps atomically assigns an unused port");
      }
      frpcCommand = normalize(frpcCommand, "frpc");
      mainConfigFile = blankToEmpty(mainConfigFile);
      managedConfigFile = normalize(managedConfigFile, "frp/starx-api.toml");
    }

    public static Frp defaults() {
      return new Frp(
          Mode.DETECT,
          "",
          "http",
          "",
          "starx-api",
          "127.0.0.1",
          8788,
          0,
          "frpc",
          "",
          "frp/starx-api.toml",
          false);
    }

    public enum Mode {
      OFF,
      DETECT,
      MANAGED;

      public static Mode parse(String value) {
        if (value == null || value.isBlank()) {
          return DETECT;
        }
        try {
          return valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
          throw new IllegalArgumentException(
              "network-automation.frp.mode must be off, detect or managed", error);
        }
      }
    }
  }

  public record Certificate(
      boolean enabled,
      String domain,
      String email,
      Client client,
      Challenge challenge,
      boolean stagingFirst,
      boolean autoRun,
      int http01LocalPort,
      boolean http01PublicRouteConfirmed,
      int renewBeforeDays) {

    public Certificate {
      domain = blankToEmpty(domain).toLowerCase(Locale.ROOT);
      email = blankToEmpty(email);
      client = client == null ? Client.AUTO : client;
      challenge = challenge == null ? Challenge.HTTP_01 : challenge;
      requirePort(http01LocalPort, "network-automation.certificate.http01-local-port");
      if (renewBeforeDays < 1 || renewBeforeDays > 60) {
        throw new IllegalArgumentException(
            "network-automation.certificate.renew-before-days must be between 1 and 60");
      }
    }

    public static Certificate defaults() {
      return new Certificate(
          false, "", "", Client.AUTO, Challenge.HTTP_01,
          true, false, 8789, false, 30);
    }

    public enum Client {
      AUTO,
      CERTBOT;

      public static Client parse(String value) {
        if (value == null || value.isBlank()) {
          return AUTO;
        }
        try {
          return valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
          throw new IllegalArgumentException(
              "network-automation.certificate.client must be auto or certbot", error);
        }
      }
    }

    public enum Challenge {
      HTTP_01,
      DNS_01;

      public static Challenge parse(String value) {
        if (value == null || value.isBlank()) {
          return HTTP_01;
        }
        try {
          return valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
          throw new IllegalArgumentException(
              "network-automation.certificate.challenge must be http-01 or dns-01", error);
        }
      }
    }
  }

  private static String normalize(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private static String blankToEmpty(String value) {
    return value == null ? "" : value.trim();
  }

  private static void requirePort(int value, String path) {
    if (value <= 0 || value > 65535) {
      throw new IllegalArgumentException(path + " must be between 1 and 65535");
    }
  }
}
