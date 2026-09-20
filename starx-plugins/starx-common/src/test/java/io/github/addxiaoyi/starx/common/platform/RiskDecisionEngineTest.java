package io.github.addxiaoyi.starx.common.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RiskDecisionEngineTest {
  private final RiskDecisionEngine engine = new RiskDecisionEngine();

  @Test
  void trustedDeviceAndRegionAvoidUnnecessaryStepUp() {
    RiskDecisionEngine.Decision decision = engine.decide(
        new RiskDecisionEngine.Input(true, true, true, 20, 0, true));

    assertEquals(RiskDecisionEngine.Action.ALLOW, decision.action());
    assertEquals(20, decision.score());
  }

  @Test
  void newDeviceRequiresTotpWhenItIsAvailable() {
    RiskDecisionEngine.Decision decision = engine.decide(
        new RiskDecisionEngine.Input(false, false, true, 25, 0, true));

    assertEquals(RiskDecisionEngine.Action.REQUIRE_TOTP, decision.action());
    assertEquals(65, decision.score());
  }

  @Test
  void criticalRiskUsesWebsiteApprovalButKeepsTotpFallback() {
    RiskDecisionEngine.Input input = new RiskDecisionEngine.Input(false, false, true, 55, 0, true, true);
    assertEquals(RiskDecisionEngine.Action.REQUIRE_WEB_APPROVAL, engine.decide(input).action());

    RiskDecisionEngine.Input websiteDown = new RiskDecisionEngine.Input(false, false, true, 55, 0, false, false);
    assertEquals(RiskDecisionEngine.Action.REQUIRE_TOTP, engine.decide(websiteDown).action());
  }

  @Test
  void neverRequiresTotpWhenPlayerHasNotEnabledIt() {
    RiskDecisionEngine.Input websiteUp =
        new RiskDecisionEngine.Input(false, false, false, 100, 0, true, true);
    assertEquals(
        RiskDecisionEngine.Action.REQUIRE_WEB_APPROVAL,
        engine.decide(websiteUp).action());

    RiskDecisionEngine.Input websiteDown =
        new RiskDecisionEngine.Input(false, false, false, 100, 0, false);
    assertEquals(RiskDecisionEngine.Action.ALLOW, engine.decide(websiteDown).action());
  }

  @Test
  void unboundGameAccountDoesNotRequireWebsiteApproval() {
    RiskDecisionEngine.Decision decision = engine.decide(
        new RiskDecisionEngine.Input(false, false, false, 100, 0, true, false));

    assertEquals(RiskDecisionEngine.Action.ALLOW, decision.action());
  }

  @Test
  void disabledTotpFallsBackToWebsiteApprovalWhenTheAccountIsBound() {
    RiskDecisionEngine.Decision decision = engine.decide(
        new RiskDecisionEngine.Input(false, true, true, 10, 1, true, true, false));

    assertEquals(RiskDecisionEngine.Action.REQUIRE_WEB_APPROVAL, decision.action());
  }

  @Test
  void recentPasswordFailureStepsUpAFamiliarRegionNewDevice() {
    RiskDecisionEngine.Decision decision = engine.decide(
        new RiskDecisionEngine.Input(false, true, true, 10, 1, false));

    assertEquals(RiskDecisionEngine.Action.REQUIRE_TOTP, decision.action());
    assertEquals(50, decision.score());
    assertEquals(java.util.List.of("new_device", "recent_password_failures"), decision.reasons());
  }

  @Test
  void rejectsInvalidFailureCounts() {
    org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
        engine.decide(new RiskDecisionEngine.Input(true, true, true, 10, -1, false)));
  }
}
