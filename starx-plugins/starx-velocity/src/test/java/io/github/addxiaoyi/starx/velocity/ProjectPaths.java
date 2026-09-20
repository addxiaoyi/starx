package io.github.addxiaoyi.starx.velocity;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ProjectPaths {

  private ProjectPaths() {
  }

  public static Path velocityProject() {
    Path workingDir = Path.of("").toAbsolutePath().normalize();
    Path directSource = workingDir.resolve("src/main/java");
    if (Files.isDirectory(directSource)) {
      return workingDir;
    }

    Path nestedProject = workingDir.resolve("starx-plugins/starx-velocity");
    if (Files.isDirectory(nestedProject.resolve("src/main/java"))) {
      return nestedProject;
    }

    throw new IllegalStateException("无法定位 starx-velocity 项目目录，当前目录: " + workingDir);
  }
}
