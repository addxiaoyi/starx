package io.github.addxiaoyi.starx.common.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TotpSecretExposureContractTest {
  @Test
  void eventPayloadsNeverContainRecoveryCodesOrTotpSecrets() throws Exception {
    String source = Files.readString(Path.of(
        "src/main/java/io/github/addxiaoyi/starx/common/auth/AuthService.java"));

    assertFalse(source.contains("\"recovery_codes\""),
        "Recovery codes must only be returned to the player who enabled or rotated TOTP");
    assertFalse(source.matches("(?s).*eventBus\\.publish\\([^;]*totpSecret.*"),
        "TOTP secrets must never enter the event bus");
  }
}
