package io.github.addxiaoyi.starx.common;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ProductionSourceCompletenessTest {

  private static final List<String> PLACEHOLDER_MARKERS = List.of(
      "Exception decompiling",
      "Decompilation failed",
      "// empty catch block",
      "throw new UnsupportedOperationException();");

  @Test
  void commonRuntimeContainsNoDecompilerOrUnimplementedPlaceholders() throws Exception {
    Path project = projectRoot();
    Path sourceRoot = project.resolve("src/main/java");
    List<String> violations = new ArrayList<>();
    try (var paths = Files.walk(sourceRoot)) {
      paths.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
        try {
          String source = Files.readString(path);
          PLACEHOLDER_MARKERS.stream()
              .filter(source::contains)
              .forEach(marker -> violations.add(project.relativize(path) + ": " + marker));
        } catch (Exception error) {
          throw new IllegalStateException("无法读取 " + path, error);
        }
      });
    }
    assertTrue(violations.isEmpty(),
        () -> "生产源码仍含不可执行占位：\n" + String.join("\n", violations));
  }

  private static Path projectRoot() {
    Path workingDir = Path.of("").toAbsolutePath().normalize();
    if (Files.isDirectory(workingDir.resolve("src/main/java"))) {
      return workingDir;
    }
    Path nested = workingDir.resolve("starx-plugins/starx-common");
    if (Files.isDirectory(nested.resolve("src/main/java"))) {
      return nested;
    }
    throw new IllegalStateException("无法定位 starx-common，当前目录: " + workingDir);
  }
}
