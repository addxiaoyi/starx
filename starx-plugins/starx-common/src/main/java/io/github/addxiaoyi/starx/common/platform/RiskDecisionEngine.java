package io.github.addxiaoyi.starx.common.platform;

import java.util.ArrayList;
import java.util.List;

public final class RiskDecisionEngine {
  private static final int NEW_DEVICE_RISK = 25;
  private static final int NEW_REGION_RISK = 15;
  private static final int PASSWORD_FAILURE_RISK = 15;
  private static final int MAX_COUNTED_PASSWORD_FAILURES = 4;
  private static final int TOTP_THRESHOLD = 50;
  private static final int WEB_APPROVAL_THRESHOLD = 80;

  public Decision decide(Input input) {
    if (input == null) throw new IllegalArgumentException("Risk input is required");
    if (input.ipRisk() < 0 || input.ipRisk() > 100) {
      throw new IllegalArgumentException("IP risk must be between 0 and 100");
    }
    if (input.recentPasswordFailures() < 0) {
      throw new IllegalArgumentException("Recent password failures must not be negative");
    }

    int score = input.ipRisk();
    List<String> reasons = new ArrayList<>();
    if (!input.trustedDevice()) {
      score += NEW_DEVICE_RISK;
      reasons.add("new_device");
    }
    if (!input.familiarRegion()) {
      score += NEW_REGION_RISK;
      reasons.add("new_region");
    }
    if (input.recentPasswordFailures() > 0) {
      score += Math.min(input.recentPasswordFailures(), MAX_COUNTED_PASSWORD_FAILURES)
          * PASSWORD_FAILURE_RISK;
      reasons.add("recent_password_failures");
    }
    score = Math.min(100, score);

    Action action = Action.ALLOW;
    if (score >= WEB_APPROVAL_THRESHOLD && input.websiteAvailable()) {
      action = Action.REQUIRE_WEB_APPROVAL;
    } else if (score >= TOTP_THRESHOLD && input.totpEnabled()) {
      action = Action.REQUIRE_TOTP;
    } else if (score >= TOTP_THRESHOLD && input.websiteAvailable()) {
      action = Action.REQUIRE_WEB_APPROVAL;
    }
    return new Decision(action, score, reasons);
  }

  public enum Action {
    ALLOW,
    REQUIRE_TOTP,
    REQUIRE_WEB_APPROVAL
  }

  public record Input(
      boolean trustedDevice,
      boolean familiarRegion,
      boolean totpEnabled,
      int ipRisk,
      int recentPasswordFailures,
      boolean websiteAvailable
  ) {
    public Input(
        boolean trustedDevice, boolean familiarRegion, boolean totpEnabled,
        int ipRisk, boolean websiteAvailable) {
      this(trustedDevice, familiarRegion, totpEnabled, ipRisk, 0, websiteAvailable);
    }
  }

  public record Decision(Action action, int score, List<String> reasons) {
    public Decision {
      reasons = List.copyOf(reasons);
    }
  }
}
