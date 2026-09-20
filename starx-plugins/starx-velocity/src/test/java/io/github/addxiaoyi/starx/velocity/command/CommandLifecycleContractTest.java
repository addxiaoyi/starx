package io.github.addxiaoyi.starx.velocity.command;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.velocity.ProjectPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class CommandLifecycleContractTest {

  @Test
  void everyCommandModuleUnregistersCommandsItRegisters() throws Exception {
    Path modules = ProjectPaths.velocityProject().resolve(
        "src/main/java/io/github/addxiaoyi/starx/velocity/module");
    try (var files = Files.walk(modules)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
        String source = Files.readString(file);
        if (source.contains("getCommandManager().register(")) {
          assertTrue(source.contains("getCommandManager().unregister("), file.toString());
        }
      }
    }
  }
}
