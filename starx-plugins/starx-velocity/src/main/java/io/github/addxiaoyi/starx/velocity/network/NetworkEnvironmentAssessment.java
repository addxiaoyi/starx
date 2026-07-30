package io.github.addxiaoyi.starx.velocity.network;

import java.net.InetAddress;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Classifies address topology. This does not claim that an application port is reachable;
 * inbound reachability still requires an external callback probe.
 */
public final class NetworkEnvironmentAssessment {

  private NetworkEnvironmentAssessment() {
  }

  public static Result assess(
      List<LocalAddressInfo> localAddresses,
      PublicAddressConsensus.Result external) {
    Objects.requireNonNull(localAddresses, "localAddresses");
    Objects.requireNonNull(external, "external");

    Set<String> localPublic = new LinkedHashSet<>();
    boolean cgnat = false;
    boolean privateAddress = false;
    for (LocalAddressInfo info : localAddresses) {
      if (info == null) {
        continue;
      }
      cgnat |= info.scope() == LocalAddressInfo.Scope.CGNAT;
      privateAddress |= info.scope() == LocalAddressInfo.Scope.PRIVATE;
      if (info.isGloballyRoutable()) {
        canonical(info.address()).ifPresent(localPublic::add);
      }
    }

    if (external.confirmed()) {
      String observed = canonical(external.address()).orElse(external.address());
      if (localPublic.contains(observed)) {
        return new Result(
            Topology.DIRECT_PUBLIC_ADDRESS,
            observed,
            Set.copyOf(localPublic),
            "external observers agree with a local globally-routable address");
      }
      if (!localPublic.isEmpty()) {
        return new Result(
            Topology.TRANSLATED_OR_FAKE_PUBLIC,
            observed,
            Set.copyOf(localPublic),
            "local public-looking address differs from the externally observed address");
      }
      if (cgnat) {
        return new Result(
            Topology.CGNAT,
            observed,
            Set.copyOf(localPublic),
            "local interfaces include RFC 6598 carrier-grade NAT space");
      }
      if (privateAddress) {
        return new Result(
            Topology.PRIVATE_NAT,
            observed,
            Set.copyOf(localPublic),
            "external address exists but local interfaces are private");
      }
      return new Result(
          Topology.TRANSLATED_OR_FAKE_PUBLIC,
          observed,
          Set.copyOf(localPublic),
          "external address is not assigned to a local interface");
    }

    if (!localPublic.isEmpty()) {
      return new Result(
          Topology.UNVERIFIED_PUBLIC,
          "",
          Set.copyOf(localPublic),
          "a local address looks public but independent observers did not confirm it");
    }
    if (cgnat) {
      return new Result(
          Topology.CGNAT,
          "",
          Set.copyOf(localPublic),
          "carrier-grade NAT address detected");
    }
    if (privateAddress) {
      return new Result(
          Topology.PRIVATE_ONLY,
          "",
          Set.copyOf(localPublic),
          "only private addresses were detected");
    }
    return new Result(
        Topology.UNKNOWN,
        "",
        Set.copyOf(localPublic),
        "no reliable address evidence was available");
  }

  private static java.util.Optional<String> canonical(String address) {
    try {
      return java.util.Optional.of(InetAddress.getByName(address).getHostAddress());
    } catch (Exception ignored) {
      return java.util.Optional.empty();
    }
  }

  public record Result(
      Topology topology,
      String externallyObservedAddress,
      Set<String> localPublicAddresses,
      String reason) {

    public Result {
      topology = Objects.requireNonNull(topology, "topology");
      externallyObservedAddress =
          externallyObservedAddress == null ? "" : externallyObservedAddress;
      localPublicAddresses = Set.copyOf(localPublicAddresses);
      reason = Objects.requireNonNull(reason, "reason");
    }

    public boolean directAddressConfirmed() {
      return this.topology == Topology.DIRECT_PUBLIC_ADDRESS;
    }

    public boolean requiresTunnel() {
      return !directAddressConfirmed();
    }
  }

  public enum Topology {
    DIRECT_PUBLIC_ADDRESS,
    TRANSLATED_OR_FAKE_PUBLIC,
    CGNAT,
    PRIVATE_NAT,
    PRIVATE_ONLY,
    UNVERIFIED_PUBLIC,
    UNKNOWN
  }
}
