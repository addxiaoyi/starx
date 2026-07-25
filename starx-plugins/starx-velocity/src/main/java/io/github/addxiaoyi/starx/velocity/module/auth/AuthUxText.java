package io.github.addxiaoyi.starx.velocity.module.auth;

import io.github.addxiaoyi.starx.common.auth.AuthResult;

final class AuthUxText {

  private AuthUxText() {
  }

  static String playerMessage(AuthResult result) {
    String message = result.message();
    if (message != null && !message.isBlank()
        && message.codePoints().anyMatch(codePoint ->
            Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN)) {
      return message;
    }
    return result.success()
        ? "认证成功，正在进入服务器。"
        : "认证未通过，请检查输入后重试。";
  }
}
