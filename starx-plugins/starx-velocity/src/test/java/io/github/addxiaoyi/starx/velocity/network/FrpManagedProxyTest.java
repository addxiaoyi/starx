package io.github.addxiaoyi.starx.velocity.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.velocity.config.NetworkAutomationConfig;
import java.nio.file.Path;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

final class FrpManagedProxyTest {

  @Test
  void managedConfigAlwaysDelegatesPortAllocationToFrps() {
    String rendered = FrpManagedProxy.render(config("", "frp.example.com"));

    assertTrue(rendered.contains("name = \"starx-api\""));
    assertTrue(rendered.contains("localPort = 8788"));
    assertTrue(rendered.contains("remotePort = 0"));
    assertFalse(rendered.contains("remotePort = 45123"));
  }

  @Test
  void onlyReadsPortFromTheRequestedProxyStatusLine() {
    String status = """
        other-api tcp running 0.0.0.0:40001
        starx-api tcp running 0.0.0.0:45123
        """;

    assertEquals(OptionalInt.of(45123),
        FrpManagedProxy.parseAssignedPort(status, "starx-api"));
    assertEquals(OptionalInt.empty(),
        FrpManagedProxy.parseAssignedPort(status, "missing-api"));
  }

  @Test
  void readsLabeledAssignedPortAndRejectsInvalidValues() {
    assertEquals(OptionalInt.of(51999), FrpManagedProxy.parseAssignedPort(
        "starx-api status=running remote_port=51999", "starx-api"));
    assertEquals(OptionalInt.empty(), FrpManagedProxy.parseAssignedPort(
        "starx-api status=running remote_port=0", "starx-api"));
    assertEquals(OptionalInt.empty(), FrpManagedProxy.parseAssignedPort(
        "starx-api status=running remote_port=70000", "starx-api"));
  }

  @Test
  void buildsPublicUrlOnlyAfterAValidAssignedPortExists() {
    assertEquals(
        "http://frp.example.com:45123",
        FrpManagedProxy.publicUrl(config("", "frp.example.com"), 45123));
    assertEquals(
        "https://edge.example.com/starx",
        FrpManagedProxy.publicUrl(
            new NetworkAutomationConfig.Frp(
                NetworkAutomationConfig.Frp.Mode.MANAGED,
                "",
                "https",
                "https://edge.example.com/starx/",
                "starx-api",
                "127.0.0.1",
                8788,
                0,
                "frpc",
                "frpc.toml",
                "frp/starx-api.toml",
                false),
            45123));
    assertThrows(IllegalArgumentException.class,
        () -> FrpManagedProxy.publicUrl(config("", ""), 45123));
  }

  @Test
  void validatesExactAndGlobIncludesWithoutTrustingCommentsOrUnrelatedStrings() {
    Path main = Path.of("config/frpc.toml").toAbsolutePath().normalize();
    Path managed = main.getParent().resolve("frp/starx-api.toml").normalize();

    assertTrue(FrpManagedProxy.mainConfigIncludes(
        main, managed, "includes = [\"frp/starx-api.toml\"]\n"));
    assertTrue(FrpManagedProxy.mainConfigIncludes(
        main, managed, "includes = [\"frp/*.toml\"]\n"));
    assertFalse(FrpManagedProxy.mainConfigIncludes(
        main, managed, "# includes = [\"frp/starx-api.toml\"]\n"));
    assertFalse(FrpManagedProxy.mainConfigIncludes(
        main, managed, "note = \"frp/starx-api.toml\"\n"));
    assertFalse(FrpManagedProxy.mainConfigIncludes(
        main, managed, "includes = [\"other/starx-api.toml\"]\n"));
  }

  @Test
  void managedModeRejectsAnyPreselectedRemotePort() {
    assertThrows(IllegalArgumentException.class, () -> new NetworkAutomationConfig.Frp(
        NetworkAutomationConfig.Frp.Mode.MANAGED,
        "frp.example.com",
        "http",
        "",
        "starx-api",
        "127.0.0.1",
        8788,
        45123,
        "frpc",
        "frpc.toml",
        "frp/starx-api.toml",
        false));
  }

  private static NetworkAutomationConfig.Frp config(String publicUrl, String publicHost) {
    return new NetworkAutomationConfig.Frp(
        NetworkAutomationConfig.Frp.Mode.MANAGED,
        publicHost,
        "http",
        publicUrl,
        "starx-api",
        "127.0.0.1",
        8788,
        0,
        "frpc",
        "frpc.toml",
        "frp/starx-api.toml",
        false);
  }
}
