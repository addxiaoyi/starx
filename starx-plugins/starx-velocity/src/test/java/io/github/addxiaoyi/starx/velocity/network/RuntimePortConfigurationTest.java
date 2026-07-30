package io.github.addxiaoyi.starx.velocity.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.velocity.config.NetworkAutomationConfig;
import io.github.addxiaoyi.starx.velocity.config.StarxConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class RuntimePortConfigurationTest {

  @Test
  void alignsFrpAndMovesHttp01AwayFromTheBoundHttpPort() throws Exception {
    StarxConfig.HttpConfig configuredHttp =
        new StarxConfig.HttpConfig("127.0.0.1", 8788, "");
    StarxConfig.HttpConfig effectiveHttp =
        new StarxConfig.HttpConfig("127.0.0.1", 8790, "");
    NetworkAutomationConfig configured = configuredNetwork(8788, 8790, true);
    TcpPortAllocator.Selection httpSelection =
        new TcpPortAllocator.Selection(8788, 8790, List.of(8788, 8789), List.of(), false);

    RuntimePortConfiguration.Result result = RuntimePortConfiguration.resolve(
        configuredHttp,
        effectiveHttp,
        configured,
        httpSelection,
        (bind, preferred, reserved) -> {
          assertEquals("*", bind);
          assertTrue(reserved.contains(8790));
          return new TcpPortAllocator.Selection(
              preferred, 8791, List.of(preferred), List.of(8790), false);
        });

    assertEquals(8790, result.networkAutomation().frp().localPort());
    assertEquals(8791, result.networkAutomation().certificate().http01LocalPort());
    assertFalse(result.networkAutomation().certificate().http01PublicRouteConfirmed());
    assertEquals(8790, nested(result.snapshot(), "http", "selectedPort"));
    assertEquals(8791, nested(result.snapshot(), "certificate", "selectedPort"));
  }

  @Test
  void preservesAnExplicitFrpTargetAndSkipsDisabledCertificateAllocation()
      throws Exception {
    StarxConfig.HttpConfig configuredHttp =
        new StarxConfig.HttpConfig("127.0.0.1", 8788, "");
    StarxConfig.HttpConfig effectiveHttp =
        new StarxConfig.HttpConfig("127.0.0.1", 8790, "");
    NetworkAutomationConfig configured = configuredNetwork(9100, 8789, false);
    TcpPortAllocator.Selection httpSelection =
        new TcpPortAllocator.Selection(8788, 8790, List.of(8788), List.of(), false);

    RuntimePortConfiguration.Result result = RuntimePortConfiguration.resolve(
        configuredHttp,
        effectiveHttp,
        configured,
        httpSelection,
        (bind, preferred, reserved) -> {
          throw new AssertionError("disabled certificate must not allocate a port");
        });

    assertEquals(9100, result.networkAutomation().frp().localPort());
    assertEquals(8789, result.networkAutomation().certificate().http01LocalPort());
    assertEquals("not_required", nested(result.snapshot(), "certificate", "status"));
  }

  private static NetworkAutomationConfig configuredNetwork(
      int frpLocalPort,
      int certificatePort,
      boolean certificateEnabled) {
    NetworkAutomationConfig.Frp defaults = NetworkAutomationConfig.Frp.defaults();
    NetworkAutomationConfig.Certificate certificateDefaults =
        NetworkAutomationConfig.Certificate.defaults();
    return new NetworkAutomationConfig(
        true,
        "network-automation.json",
        NetworkAutomationConfig.PublicAddress.defaults(),
        new NetworkAutomationConfig.Frp(
            defaults.mode(),
            defaults.publicHost(),
            defaults.publicScheme(),
            defaults.publicUrl(),
            defaults.proxyName(),
            defaults.localAddress(),
            frpLocalPort,
            defaults.remotePort(),
            defaults.frpcCommand(),
            defaults.mainConfigFile(),
            defaults.managedConfigFile(),
            defaults.autoApply()),
        new NetworkAutomationConfig.Certificate(
            certificateEnabled,
            "panel.example.com",
            "admin@example.com",
            certificateDefaults.client(),
            certificateDefaults.challenge(),
            certificateDefaults.stagingFirst(),
            certificateDefaults.autoRun(),
            certificatePort,
            true,
            certificateDefaults.renewBeforeDays()));
  }

  private static Object nested(Map<String, Object> root, String section, String key) {
    return ((Map<?, ?>) root.get(section)).get(key);
  }
}
