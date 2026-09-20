package io.github.addxiaoyi.starx.velocity.module.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.addxiaoyi.starx.common.auth.AuthResult;
import org.junit.jupiter.api.Test;

final class AuthUxTextTest {

  @Test
  void preservesChineseAuthenticationMessages() {
    assertEquals("密码错误", AuthUxText.playerMessage(AuthResult.failure("密码错误")));
  }

  @Test
  void hidesEnglishMessagesReturnedByExternalAuthentication() {
    assertEquals(
        "认证未通过，请检查输入后重试。",
        AuthUxText.playerMessage(AuthResult.failure("Invalid password")));
  }

  @Test
  void providesChineseFallbackForBlankSuccess() {
    assertEquals(
        "认证成功，正在进入服务器。",
        AuthUxText.playerMessage(AuthResult.success("")));
  }
}
