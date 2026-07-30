package io.github.addxiaoyi.starx.velocity.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.velocity.config.NetworkAutomationConfig;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class CertificateCommandPlannerTest {

  @Test
  void refusesHttp01UntilPublicPort80RoutingIsExplicitlyConfirmed() {
    CertificateCommandPlanner.Plan plan = CertificateCommandPlanner.plan(
        Path.of("plugins/starx"),
        certificate("panel.example.com", false, true,
            NetworkAutomationConfig.Certificate.Challenge.HTTP_01));

    assertEquals(
        CertificateCommandPlanner.Status.HTTP_ROUTE_UNCONFIRMED,
        plan.status());
    assertFalse(plan.ready());
    assertFalse(plan.autoRunAllowed());
  }

  @Test
  void createsStagingAndProductionCertbotCommandsWithoutAShell() {
    CertificateCommandPlanner.Plan plan = CertificateCommandPlanner.plan(
        Path.of("plugins/starx"),
        certificate("panel.example.com", true, true,
            NetworkAutomationConfig.Certificate.Challenge.HTTP_01));

    assertTrue(plan.ready());
    assertTrue(plan.autoRunAllowed());
    assertEquals("certbot", plan.productionCommand().getFirst());
    assertTrue(plan.productionCommand().contains("--http-01-port"));
    assertTrue(plan.productionCommand().contains("8789"));
    assertFalse(plan.productionCommand().contains("--test-cert"));
    assertTrue(plan.stagingCommand().contains("--test-cert"));
    assertTrue(plan.fullChain().toString().endsWith("fullchain.pem"));
    assertTrue(plan.privateKey().toString().endsWith("privkey.pem"));
  }

  @Test
  void requiresDnsProviderAutomationForDns01() {
    CertificateCommandPlanner.Plan plan = CertificateCommandPlanner.plan(
        Path.of("plugins/starx"),
        certificate("*.example.com", false, false,
            NetworkAutomationConfig.Certificate.Challenge.DNS_01));

    assertEquals(
        CertificateCommandPlanner.Status.DNS_PROVIDER_REQUIRED,
        plan.status());
    assertTrue(plan.productionCommand().isEmpty());
  }

  @Test
  void rejectsWildcardWithHttp01AndIpAddressDomains() {
    CertificateCommandPlanner.Plan wildcard = CertificateCommandPlanner.plan(
        Path.of("plugins/starx"),
        certificate("*.example.com", true, false,
            NetworkAutomationConfig.Certificate.Challenge.HTTP_01));
    assertEquals(
        CertificateCommandPlanner.Status.WILDCARD_REQUIRES_DNS,
        wildcard.status());

    CertificateCommandPlanner.Plan ip = CertificateCommandPlanner.plan(
        Path.of("plugins/starx"),
        certificate("8.8.8.8", true, false,
            NetworkAutomationConfig.Certificate.Challenge.HTTP_01));
    assertEquals(CertificateCommandPlanner.Status.INVALID_DOMAIN, ip.status());
  }

  private static NetworkAutomationConfig.Certificate certificate(
      String domain,
      boolean routeConfirmed,
      boolean autoRun,
      NetworkAutomationConfig.Certificate.Challenge challenge) {
    return new NetworkAutomationConfig.Certificate(
        true,
        domain,
        "admin@example.com",
        NetworkAutomationConfig.Certificate.Client.AUTO,
        challenge,
        true,
        autoRun,
        8789,
        routeConfirmed,
        30);
  }
}
