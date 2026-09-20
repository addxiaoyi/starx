package io.github.addxiaoyi.starx.velocity.network;

import io.github.addxiaoyi.starx.velocity.config.NetworkAutomationConfig;
import io.github.addxiaoyi.starx.velocity.config.StarxConfig;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Aligns runtime HTTP, FRP and ACME ports after the HTTP listener has bound successfully. */
public final class RuntimePortConfiguration {
  private RuntimePortConfiguration() {
  }

  public static Result resolve(
      StarxConfig.HttpConfig configuredHttp,
      StarxConfig.HttpConfig effectiveHttp,
      NetworkAutomationConfig configuredNetwork,
      TcpPortAllocator.Selection httpSelection) throws IOException {
    return resolve(
        configuredHttp,
        effectiveHttp,
        configuredNetwork,
        httpSelection,
        (bind, preferred, reserved) -> TcpPortAllocator.selectWildcard(preferred, reserved));
  }

  static Result resolve(
      StarxConfig.HttpConfig configuredHttp,
      StarxConfig.HttpConfig effectiveHttp,
      NetworkAutomationConfig configuredNetwork,
      TcpPortAllocator.Selection httpSelection,
      PortSelector selector) throws IOException {
    Objects.requireNonNull(configuredHttp, "configuredHttp");
    Objects.requireNonNull(effectiveHttp, "effectiveHttp");
    Objects.requireNonNull(configuredNetwork, "configuredNetwork");
    Objects.requireNonNull(httpSelection, "httpSelection");
    Objects.requireNonNull(selector, "selector");

    NetworkAutomationConfig.Frp configuredFrp = configuredNetwork.frp();
    boolean frpTracksHttp = configuredFrp.localPort() == configuredHttp.port();
    NetworkAutomationConfig.Frp effectiveFrp = new NetworkAutomationConfig.Frp(
        configuredFrp.mode(),
        configuredFrp.publicHost(),
        configuredFrp.publicScheme(),
        configuredFrp.publicUrl(),
        configuredFrp.proxyName(),
        configuredFrp.localAddress(),
        frpTracksHttp ? effectiveHttp.port() : configuredFrp.localPort(),
        configuredFrp.remotePort(),
        configuredFrp.frpcCommand(),
        configuredFrp.mainConfigFile(),
        configuredFrp.managedConfigFile(),
        configuredFrp.autoApply());

    NetworkAutomationConfig.Certificate configuredCertificate = configuredNetwork.certificate();
    TcpPortAllocator.Selection certificateSelection = null;
    int effectiveCertificatePort = configuredCertificate.http01LocalPort();
    boolean certificateNeedsPort = configuredNetwork.enabled()
        && configuredCertificate.enabled()
        && configuredCertificate.challenge() == NetworkAutomationConfig.Certificate.Challenge.HTTP_01;
    if (certificateNeedsPort) {
      certificateSelection = selector.select(
          "*",
          configuredCertificate.http01LocalPort(),
          Set.of(effectiveHttp.port()));
      effectiveCertificatePort = certificateSelection.selectedPort();
    }

    boolean routeConfirmationPreserved =
        configuredCertificate.http01PublicRouteConfirmed()
            && effectiveCertificatePort == configuredCertificate.http01LocalPort();
    NetworkAutomationConfig.Certificate effectiveCertificate =
        new NetworkAutomationConfig.Certificate(
            configuredCertificate.enabled(),
            configuredCertificate.domain(),
            configuredCertificate.email(),
            configuredCertificate.client(),
            configuredCertificate.challenge(),
            configuredCertificate.stagingFirst(),
            configuredCertificate.autoRun(),
            effectiveCertificatePort,
            routeConfirmationPreserved,
            configuredCertificate.renewBeforeDays());

    NetworkAutomationConfig effectiveNetwork = new NetworkAutomationConfig(
        configuredNetwork.enabled(),
        configuredNetwork.reportFile(),
        configuredNetwork.publicAddress(),
        effectiveFrp,
        effectiveCertificate);

    LinkedHashMap<String, Object> snapshot = new LinkedHashMap<>();
    LinkedHashMap<String, Object> http =
        new LinkedHashMap<>(selectionSnapshot(httpSelection));
    http.put(
        "conflictPolicy",
        configuredHttp.portConflictPolicy().name().toLowerCase(java.util.Locale.ROOT));
    http.put("fallbackRangeStart", configuredHttp.fallbackRangeStart());
    http.put("fallbackRangeEnd", configuredHttp.fallbackRangeEnd());
    snapshot.put("http", Map.copyOf(http));
    LinkedHashMap<String, Object> frp = new LinkedHashMap<>();
    frp.put("configuredLocalPort", configuredFrp.localPort());
    frp.put("selectedLocalPort", effectiveFrp.localPort());
    frp.put("tracksHttpApi", frpTracksHttp);
    frp.put("changed", configuredFrp.localPort() != effectiveFrp.localPort());
    snapshot.put("frp", Map.copyOf(frp));
    if (certificateSelection == null) {
      snapshot.put("certificate", Map.of(
          "status", "not_required",
          "configuredPort", configuredCertificate.http01LocalPort(),
          "selectedPort", configuredCertificate.http01LocalPort(),
          "changed", false,
          "publicRouteConfirmationPreserved",
          configuredCertificate.http01PublicRouteConfirmed()));
    } else {
      LinkedHashMap<String, Object> certificate =
          new LinkedHashMap<>(selectionSnapshot(certificateSelection));
      certificate.put("status", "selected");
      certificate.put("publicRouteConfirmationPreserved", routeConfirmationPreserved);
      snapshot.put("certificate", Map.copyOf(certificate));
    }

    return new Result(effectiveHttp, effectiveNetwork, Map.copyOf(snapshot));
  }

  private static Map<String, Object> selectionSnapshot(
      TcpPortAllocator.Selection selection) {
    LinkedHashMap<String, Object> result = new LinkedHashMap<>();
    result.put("configuredPort", selection.preferredPort());
    result.put("selectedPort", selection.selectedPort());
    result.put("changed", selection.changed());
    result.put("mode", selection.mode());
    result.put("occupiedPorts", selection.occupiedPorts());
    result.put("reservedPorts", selection.reservedPorts());
    return Map.copyOf(result);
  }

  @FunctionalInterface
  interface PortSelector {
    TcpPortAllocator.Selection select(
        String bind,
        int preferredPort,
        Set<Integer> reservedPorts) throws IOException;
  }

  public record Result(
      StarxConfig.HttpConfig http,
      NetworkAutomationConfig networkAutomation,
      Map<String, Object> snapshot) {
    public Result {
      Objects.requireNonNull(http, "http");
      Objects.requireNonNull(networkAutomation, "networkAutomation");
      snapshot = snapshot == null ? Map.of() : Map.copyOf(snapshot);
    }
  }
}
