package io.github.addxiaoyi.starx.velocity.module.welcome;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.velocity.ProjectPaths;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

final class WelcomeCurrentLoginContractTest {

  @Test
  void welcomeFactsSeparateCurrentAndPreviousAddress() throws Exception {
    String source = Files.readString(ProjectPaths.velocityProject().resolve(
        "src/main/java/io/github/addxiaoyi/starx/velocity/module/welcome/WelcomeModule.java"));

    assertTrue(source.contains("new WelcomeCard.Fact(\"本次 IP\", currentAddress.address())"));
    assertTrue(source.contains("new WelcomeCard.Fact(\"本次位置\", currentAddress.locationLabel())"));
    assertTrue(source.contains("new WelcomeCard.Fact(\"上次 IP\", ip)"));
  }
}
