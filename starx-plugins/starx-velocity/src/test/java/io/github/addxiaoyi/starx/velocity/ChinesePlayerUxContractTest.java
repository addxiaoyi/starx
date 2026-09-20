package io.github.addxiaoyi.starx.velocity;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class ChinesePlayerUxContractTest {

  private static final Pattern ENGLISH_PLAYER_TEXT = Pattern.compile(
      "(?:Component\\.text|AuthResult\\.(?:failure|success)|sendMessage)[^\"\\r\\n]*\"([A-Za-z][^\"]*)\"");

  @Test
  void bundledPlayerFacingMessagesDoNotFallBackToEnglish() throws Exception {
    Path plugins = ProjectPaths.velocityProject().getParent();
    List<Path> roots = List.of(
        plugins.resolve("starx-velocity/src/main/java"),
        plugins.resolve("starx-common/src/main/java/io/github/addxiaoyi/starx/common/auth"),
        plugins.resolve("starx-server/src/main/java"));
    List<String> violations = new ArrayList<>();

    for (Path root : roots) {
      try (var paths = Files.walk(root)) {
        for (Path path : paths.filter(file -> file.toString().endsWith(".java")).toList()) {
          List<String> lines = Files.readAllLines(path);
          for (int index = 0; index < lines.size(); index++) {
            var matcher = ENGLISH_PLAYER_TEXT.matcher(lines.get(index));
            if (matcher.find() && !isLocalizedFragment(matcher.group(1))) {
              violations.add(path.getFileName() + ":" + (index + 1) + " -> " + matcher.group(1));
            }
          }
        }
      }
    }

    assertTrue(violations.isEmpty(),
        () -> "默认玩家提示必须为中文：\n" + String.join("\n", violations));
  }

  private static boolean isLocalizedFragment(String text) {
    return text.equals("ms")
        || text.codePoints().anyMatch(codePoint -> codePoint >= 0x3400 && codePoint <= 0x9fff)
        || text.matches(".*\\\\u[3-9a-fA-F][0-9a-fA-F]{3}.*");
  }
}
