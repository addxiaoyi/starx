package io.github.addxiaoyi.starx.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import org.junit.jupiter.api.Test;

class AuthResultSecretTest {
  @Test
  void totpSecretsStayOutOfHumanReadableStatusMessage() {
    AuthResult result = AuthResult.totpEnabled("SECRET", List.of("CODE-1", "CODE-2"));

    assertEquals("二步验证已开启", result.message());
    assertEquals("SECRET", result.totpSecret());
    assertEquals(List.of("CODE-1", "CODE-2"), result.recoveryCodes());
    assertFalse(result.message().contains("SECRET"));
    assertFalse(result.message().contains("CODE-1"));
  }

  @Test
  void rotatedRecoveryCodesAreStructuredToo() {
    AuthResult result = AuthResult.recoveryCodesRotated(List.of("NEW-1"));

    assertEquals("恢复码已更新", result.message());
    assertEquals(List.of("NEW-1"), result.recoveryCodes());
    assertFalse(result.message().contains("NEW-1"));
  }
}
