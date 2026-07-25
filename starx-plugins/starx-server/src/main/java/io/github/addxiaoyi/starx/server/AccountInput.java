package io.github.addxiaoyi.starx.server;

import java.util.Locale;
import java.util.regex.Pattern;

final class AccountInput {
  private static final Pattern EMAIL = Pattern.compile(
      "^[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?"
          + "(?:\\.[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?)+$",
      Pattern.CASE_INSENSITIVE);

  private AccountInput() {
  }

  static String email(String input) {
    String value = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
    if (value.length() > 254 || !EMAIL.matcher(value).matches()) {
      throw new IllegalArgumentException("请输入有效的邮箱地址");
    }
    return value;
  }

  static String password(String input) {
    if (input == null || input.isBlank()) {
      throw new IllegalArgumentException("密码不能为空");
    }
    if (input.length() > 128) {
      throw new IllegalArgumentException("密码长度不能超过 128 个字符");
    }
    return input;
  }

  static String totpCode(String input) {
    String code = input == null ? "" : input.trim();
    if (!code.matches("\\d{6}")) {
      throw new IllegalArgumentException("请输入 6 位动态验证码");
    }
    return code;
  }
}
