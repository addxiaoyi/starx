package io.github.addxiaoyi.starx.velocity.module.auth;

final class AuthAdmissionPolicy {

  private AuthAdmissionPolicy() {
  }

  static boolean canAutoLogin(boolean premium, boolean trustedExternalIdentity) {
    return premium || trustedExternalIdentity;
  }
}
