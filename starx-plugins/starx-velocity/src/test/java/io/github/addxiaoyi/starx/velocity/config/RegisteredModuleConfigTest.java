package io.github.addxiaoyi.starx.velocity.config;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.velocity.ProjectPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RegisteredModuleConfigTest {

  private static final Pattern MODULE_ID = Pattern.compile(
      "(?s)String\\s+name\\s*\\(\\s*\\)\\s*\\{.*?return\\s+\"(starx\\.[a-z0-9.-]+)\"\\s*;");
  private static final Pattern REGISTER_NEW = Pattern.compile(
      "moduleManager\\.register\\(new\\s+(?:[a-zA-Z0-9_.]+\\.)?([A-Z][a-zA-Z0-9]+)");
  private static final Pattern REGISTER_VARIABLE = Pattern.compile(
      "moduleManager\\.register\\(([a-z][a-zA-Z0-9]+)\\)");

  @TempDir
  Path tempDir;

  @Test
  void everyVelocityModuleHasAnExplicitDefaultSwitch() throws Exception {
    Set<String> sourceModuleIds = discoverModuleIds();
    Map<String, Object> root = ConfigLayout.readDefaultRoot();
    Map<String, Object> modules = mapping(root.get("modules"));

    Set<String> missing = new HashSet<>(sourceModuleIds);
    missing.removeAll(modules.keySet());
    assertTrue(missing.isEmpty(), () -> "以下 Velocity 模块缺少默认开关: " + missing);
    modules.forEach((id, rawModule) -> {
      Map<String, Object> module = mapping(rawModule);
      assertTrue(module.containsKey("enabled"), () -> id + " 缺少 enabled");
      assertInstanceOf(Boolean.class, module.get("enabled"), () -> id + ".enabled 必须是布尔值");
    });
  }

  @Test
  void defaultTransferTimeoutCoversModernBackendConfiguration() throws Exception {
    Map<String, Object> root = ConfigLayout.readDefaultRoot();
    Map<String, Object> uworld = mapping(root.get("uworld"));
    Number timeout = assertInstanceOf(
        Number.class, uworld.get("transfer-timeout-seconds"));

    assertTrue(timeout.intValue() >= 30,
        "默认转服超时必须覆盖 Paper 1.21.x 的完整配置阶段");
  }

  private Set<String> discoverModuleIds() throws Exception {
    Set<String> ids = new HashSet<>();
    Path project = ProjectPaths.velocityProject();
    Path sourceRoot = project.resolve("src/main/java");
    String bootstrap = Files.readString(sourceRoot.resolve(
        "io/github/addxiaoyi/starx/velocity/StarxVelocityPlugin.java"));
    Set<String> registeredClasses = new HashSet<>();
    var newModule = REGISTER_NEW.matcher(bootstrap);
    while (newModule.find()) {
      registeredClasses.add(newModule.group(1));
    }
    var variable = REGISTER_VARIABLE.matcher(bootstrap);
    while (variable.find()) {
      String variableName = variable.group(1);
      Pattern declaration = Pattern.compile(
          "([A-Z][a-zA-Z0-9]+)\\s+" + Pattern.quote(variableName) + "\\s*=");
      var declared = declaration.matcher(bootstrap);
      if (declared.find()) {
        registeredClasses.add(declared.group(1));
      }
    }
    try (var paths = Files.walk(sourceRoot)) {
      for (Path path : paths.filter(file -> file.toString().endsWith(".java")).toList()) {
        String fileName = path.getFileName().toString();
        String className = fileName.substring(0, fileName.length() - ".java".length());
        if (!registeredClasses.contains(className)) {
          continue;
        }
        var matcher = MODULE_ID.matcher(Files.readString(path));
        while (matcher.find()) {
          ids.add(matcher.group(1));
        }
      }
    }
    assertTrue(ids.size() >= 25, () -> "模块发现数量异常: " + ids);
    return ids;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> mapping(Object value) {
    return (Map<String, Object>) value;
  }
}
