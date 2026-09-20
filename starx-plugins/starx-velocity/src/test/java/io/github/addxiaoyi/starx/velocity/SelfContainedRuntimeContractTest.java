package io.github.addxiaoyi.starx.velocity;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class SelfContainedRuntimeContractTest {

  private static final Map<String, String> FORBIDDEN_SOURCE_MARKERS = Map.ofEntries(
      Map.entry("URLClassLoader", "运行时 JAR 类加载器"),
      Map.entry("JarURLConnection", "运行时 JAR 网络连接"),
      Map.entry("ip-api.com", "硬编码公网 GeoIP 服务"));

  @Test
  void velocityRuntimeNeverDownloadsOrDynamicallyLoadsPluginCode() throws Exception {
    Path sourceRoot = ProjectPaths.velocityProject().resolve("src/main/java");
    List<String> violations = new ArrayList<>();

    try (var files = Files.walk(sourceRoot)) {
      files.filter(path -> path.toString().endsWith(".java"))
          .forEach(path -> inspect(path, violations));
    }

    assertTrue(
        violations.isEmpty(),
        () -> "StarX Velocity 不得运行时下载或动态加载插件代码：\n" + String.join("\n", violations));
  }

  private static void inspect(Path path, List<String> violations) {
    try {
      String source = Files.readString(path);
      FORBIDDEN_SOURCE_MARKERS.forEach((marker, reason) -> {
        if (source.contains(marker)) {
          violations.add(ProjectPaths.velocityProject().relativize(path) + ": " + reason);
        }
      });
    } catch (IOException error) {
      throw new IllegalStateException("无法读取源码文件: " + path, error);
    }
  }
}
