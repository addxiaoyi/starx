package io.github.addxiaoyi.starx.velocity.http.admin;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class BanHandlerPersistenceContractTest {
  @Test
  void usernameBanEndpointPersistsThePunishmentBeforePublishingTheEvent() throws Exception {
    String source = Files.readString(locateSource(), StandardCharsets.UTF_8);
    int start = source.indexOf("private void handleBan(JsonHttpExchange ctx)");
    int end = source.indexOf("private void handleBanPlayer(JsonHttpExchange ctx)", start);

    assertTrue(start >= 0 && end > start);
    String handler = source.substring(start, end);
    assertTrue(handler.contains("punishmentRepo.record"));
    assertTrue(handler.contains("reason.length() > 500"));
  }

  private static Path locateSource() {
    Path current = Path.of("").toAbsolutePath();
    for (int depth = 0; depth < 8 && current != null; depth++) {
      Path candidate = current.resolve(
          "starx-plugins/starx-velocity/src/main/java/"
              + "io/github/addxiaoyi/starx/velocity/http/admin/BanHandler.java");
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("BanHandler.java source is unavailable");
  }
}
