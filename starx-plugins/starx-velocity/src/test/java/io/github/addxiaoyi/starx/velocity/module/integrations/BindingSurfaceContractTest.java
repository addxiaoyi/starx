package io.github.addxiaoyi.starx.velocity.module.integrations;

import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.addxiaoyi.starx.velocity.ProjectPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class BindingSurfaceContractTest {

  @Test
  void runtimeDoesNotExposeEphemeralDiscordOrTelegramBindings() throws Exception {
    Path project = ProjectPaths.velocityProject();
    String plugin = Files.readString(project.resolve(
        "src/main/java/io/github/addxiaoyi/starx/velocity/StarxVelocityPlugin.java"));
    String defaults = Files.readString(project.resolve("src/main/resources/default-config.yml"));

    assertFalse(plugin.contains("SocialIntegrationModule"));
    assertFalse(defaults.contains("starx.integrations.social"));
  }
}
