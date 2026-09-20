package io.github.addxiaoyi.starx.velocity.module.auth;

final class AuthAdmissionPolicy {

  private AuthAdmissionPolicy() {
  }

  static boolean canAutoLogin(boolean premium, boolean trustedExternalIdentity) {
    return premium || trustedExternalIdentity;
  }

  static boolean isPremiumAutoLogin(boolean premium, boolean premiumBypass) {
    return premium && premiumBypass;
  }

  static boolean isFloodgateAutoLogin(boolean trustedExternalIdentity, boolean floodgateBypass) {
    return trustedExternalIdentity && floodgateBypass;
  }

  static boolean isSkinSiteAutoLogin(boolean trustedWebsiteBinding, boolean skinSiteBypass) {
    return trustedWebsiteBinding && skinSiteBypass;
  }
}
