package io.github.addxiaoyi.starx.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

final class OptionalIntegrationContractTest {

  @Test
  void velocityDeclaresEveryCapabilityPluginAsOptional() throws Exception {
    Path descriptor = ProjectPaths.velocityProject()
        .resolve("src/main/resources/velocity-plugin.json");
    @SuppressWarnings("unchecked")
    Map<String, Object> root = new Gson().fromJson(Files.readString(descriptor), Map.class);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> dependencies = (List<Map<String, Object>>) root.get("dependencies");
    Map<String, Boolean> optionalById = dependencies.stream().collect(Collectors.toMap(
        dependency -> (String) dependency.get("id"),
        dependency -> (Boolean) dependency.get("optional")));

    assertEquals(Set.of("luckperms", "floodgate", "tab"), optionalById.keySet());
    assertTrue(optionalById.values().stream().allMatch(Boolean.TRUE::equals));
  }

  @Test
  void paperAndFoliaDeclareOptionalServerIntegrationsAsSoftDependencies() throws Exception {
    Path serverProject = ProjectPaths.velocityProject().resolveSibling("starx-server");
    Map<String, Object> plugin = mapping(new Yaml().load(Files.readString(
        serverProject.resolve("src/main/resources/plugin.yml"))));

    assertEquals(List.of("PlaceholderAPI", "SkinsRestorer"), plugin.get("softdepend"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> mapping(Object value) {
    return (Map<String, Object>) value;
  }
}
