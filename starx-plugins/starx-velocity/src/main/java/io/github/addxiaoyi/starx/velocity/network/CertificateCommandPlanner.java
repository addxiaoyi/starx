package io.github.addxiaoyi.starx.velocity.network;

import io.github.addxiaoyi.starx.velocity.config.NetworkAutomationConfig;
import java.net.IDN;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Produces non-shell Certbot commands. Commands are executable only after the
 * explicitly configured ACME challenge prerequisites are satisfied.
 */
public final class CertificateCommandPlanner {
  private static final Pattern EMAIL = Pattern.compile(
      "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
  private static final Pattern DNS_NAME = Pattern.compile(
      "^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+"
          + "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");

  private CertificateCommandPlanner() {
  }

  public static Plan plan(
      Path dataDirectory,
      NetworkAutomationConfig.Certificate config) {
    Objects.requireNonNull(dataDirectory, "dataDirectory");
    Objects.requireNonNull(config, "config");

    if (!config.enabled()) {
      return blocked(Status.DISABLED, "certificate automation is disabled");
    }
    String domain = normalizeDomain(config.domain());
    if (domain.isBlank()) {
      return blocked(Status.MISSING_DOMAIN, "certificate domain is required");
    }
    boolean wildcard = domain.startsWith("*.");
    String validationName = wildcard ? domain.substring(2) : domain;
    if (!validDnsName(validationName)) {
      return blocked(Status.INVALID_DOMAIN, "certificate domain is not a valid DNS name");
    }
    if (config.email().isBlank()) {
      return blocked(Status.MISSING_EMAIL, "ACME account email is required");
    }
    if (!EMAIL.matcher(config.email()).matches()) {
      return blocked(Status.INVALID_EMAIL, "ACME account email is invalid");
    }
    if (wildcard && config.challenge() != NetworkAutomationConfig.Certificate.Challenge.DNS_01) {
      return blocked(Status.WILDCARD_REQUIRES_DNS,
          "wildcard certificates require the DNS-01 challenge");
    }
    if (config.challenge() == NetworkAutomationConfig.Certificate.Challenge.DNS_01) {
      return blocked(Status.DNS_PROVIDER_REQUIRED,
          "unattended DNS-01 requires an explicit DNS provider plugin and API credential");
    }
    if (!config.http01PublicRouteConfirmed()) {
      return blocked(Status.HTTP_ROUTE_UNCONFIRMED,
          "HTTP-01 requires the public domain's TCP port 80 to reach the local challenge port");
    }

    Path root = dataDirectory.toAbsolutePath().normalize().resolve("certificates");
    Path productionRoot = root.resolve("production");
    List<String> production = command(
        productionRoot, config, domain, validationName, false);
    List<String> staging = config.stagingFirst()
        ? command(root.resolve("staging"), config, domain, validationName, true)
        : List.of();

    Path live = productionRoot.resolve("config").resolve("live").resolve(validationName);
    return new Plan(
        Status.READY,
        "certificate request prerequisites are satisfied",
        staging,
        production,
        live.resolve("fullchain.pem"),
        live.resolve("privkey.pem"),
        config.autoRun());
  }

  private static List<String> command(
      Path root,
      NetworkAutomationConfig.Certificate config,
      String domain,
      String certName,
      boolean staging) {
    ArrayList<String> command = new ArrayList<>(List.of(
        executable(config),
        "certonly",
        "--non-interactive",
        "--agree-tos",
        "--email", config.email(),
        "--standalone",
        "--preferred-challenges", "http",
        "--http-01-port", Integer.toString(config.http01LocalPort()),
        "--cert-name", certName,
        "-d", domain,
        "--keep-until-expiring",
        "--config-dir", root.resolve("config").toString(),
        "--work-dir", root.resolve("work").toString(),
        "--logs-dir", root.resolve("logs").toString()));
    if (staging) {
      command.add("--test-cert");
    }
    return List.copyOf(command);
  }

  private static Plan blocked(Status status, String reason) {
    return new Plan(status, reason, List.of(), List.of(), null, null, false);
  }

  private static String executable(NetworkAutomationConfig.Certificate config) {
    return switch (config.client()) {
      case AUTO, CERTBOT -> "certbot";
    };
  }

  private static String normalizeDomain(String value) {
    if (value == null) {
      return "";
    }
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    boolean wildcard = normalized.startsWith("*.");
    String valueWithoutWildcard = wildcard ? normalized.substring(2) : normalized;
    try {
      String ascii = IDN.toASCII(valueWithoutWildcard, IDN.USE_STD3_ASCII_RULES);
      return wildcard ? "*." + ascii : ascii;
    } catch (IllegalArgumentException error) {
      return "";
    }
  }

  private static boolean validDnsName(String name) {
    return DNS_NAME.matcher(name).matches()
        && !LocalAddressInfo.parse(name).isGloballyRoutable();
  }

  public record Plan(
      Status status,
      String reason,
      List<String> stagingCommand,
      List<String> productionCommand,
      Path fullChain,
      Path privateKey,
      boolean autoRunAllowed) {

    public Plan {
      status = Objects.requireNonNull(status, "status");
      reason = Objects.requireNonNull(reason, "reason");
      stagingCommand = List.copyOf(stagingCommand);
      productionCommand = List.copyOf(productionCommand);
    }

    public boolean ready() {
      return this.status == Status.READY;
    }
  }

  public enum Status {
    READY,
    DISABLED,
    MISSING_DOMAIN,
    INVALID_DOMAIN,
    MISSING_EMAIL,
    INVALID_EMAIL,
    WILDCARD_REQUIRES_DNS,
    DNS_PROVIDER_REQUIRED,
    HTTP_ROUTE_UNCONFIRMED
  }
}
