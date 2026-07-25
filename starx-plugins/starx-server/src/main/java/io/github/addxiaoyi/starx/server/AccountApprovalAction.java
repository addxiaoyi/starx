package io.github.addxiaoyi.starx.server;

import java.util.Locale;

enum AccountApprovalAction {
  BIND_EMAIL("bind_email", "绑定网站邮箱"),
  ENABLE_TOTP("enable_totp", "开启二次验证"),
  BIND_SKIN_ACCOUNT("bind_skin_account", "绑定皮肤站账号");

  private final String apiName;
  private final String title;

  AccountApprovalAction(String apiName, String title) {
    this.apiName = apiName;
    this.title = title;
  }

  String apiName() {
    return this.apiName;
  }

  String title() {
    return this.title;
  }

  static AccountApprovalAction fromCommand(String raw) {
    if (raw == null) return null;
    return switch (raw.trim().toLowerCase(Locale.ROOT)) {
      case "approve-email" -> BIND_EMAIL;
      case "approve-2fa" -> ENABLE_TOTP;
      case "approve-skin" -> BIND_SKIN_ACCOUNT;
      default -> null;
    };
  }
}
