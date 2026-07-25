package io.github.addxiaoyi.starx.common.auth;

@FunctionalInterface
public interface EmailSender {
  void sendVerificationCode(String email, String code);
}
