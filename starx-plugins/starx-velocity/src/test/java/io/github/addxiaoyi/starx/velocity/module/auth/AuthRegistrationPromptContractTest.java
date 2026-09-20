package io.github.addxiaoyi.starx.velocity.module.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class AuthRegistrationPromptContractTest {
  @Test
  void bindingCodeIsGeneratedOnlyForRegisteredLoginPrompt() throws Exception {
    String source = Files.readString(sourcePath());
    int methodStart = source.indexOf("private void showAuthPrompt");
    int methodEnd = source.indexOf("private void showTotpPrompt", methodStart);
    String prompt = source.substring(methodStart, methodEnd);

    int registeredBranch = prompt.indexOf("if (registered) {");
    int bindingCode = prompt.indexOf("this.bindingVerification.generateCode");
    int loginReturn = prompt.indexOf("return;", bindingCode);
    int registrationPrompt = prompt.indexOf(
        "messages.registerTitle()", loginReturn);
    int registrationCard = prompt.indexOf(
        "AuthLoginCard.renderRegistration", loginReturn);

    assertTrue(registeredBranch >= 0);
    assertTrue(bindingCode > registeredBranch,
        "Unregistered players must not request a binding verification code");
    assertTrue(loginReturn > bindingCode);
    assertTrue(registrationPrompt > loginReturn,
        "The registration prompt must remain outside the registered-login branch");
    assertTrue(registrationCard > loginReturn,
        "First-time registration must render the full authentication card");
    assertEquals(1,
        prompt.split("this\\.bindingVerification\\.generateCode", -1).length - 1);
  }

  private static Path sourcePath() {
    Path module = Path.of(
        "src/main/java/io/github/addxiaoyi/starx/velocity/module/auth/AuthModule.java");
    if (Files.isRegularFile(module)) {
      return module;
    }
    return Path.of("starx-plugins/starx-velocity", module.toString());
  }
}
