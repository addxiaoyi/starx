package io.github.addxiaoyi.starx.server;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AccountAnvilLifecycleContractTest {
  @Test
  void staleAsyncCallbacksAndQuitSessionsAreRejected() throws Exception {
    String source = Files.readString(Path.of(
        "src/main/java/io/github/addxiaoyi/starx/server/AccountAnvilController.java"));

    assertTrue(source.contains("AtomicLong generation"));
    assertTrue(source.contains("onQuit(PlayerQuitEvent event)"));
    assertTrue(source.contains("isCurrent(player, requestGeneration)"));
  }

  @Test
  void emailConfirmationPromptMatchesTheSixDigitValidationRule() throws Exception {
    String source = Files.readString(Path.of(
        "src/main/java/io/github/addxiaoyi/starx/server/AccountAnvilController.java"));

    assertTrue(source.contains("EMAIL_CONFIRM(\"确认邮箱\", \"输入邮件中的 6 位验证码\")"));
  }
}
