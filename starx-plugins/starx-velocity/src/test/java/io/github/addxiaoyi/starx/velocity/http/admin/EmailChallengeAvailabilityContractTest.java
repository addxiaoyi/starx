package io.github.addxiaoyi.starx.velocity.http.admin;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EmailChallengeAvailabilityContractTest {
  @Test
  void unavailableDeliveryUsesServiceUnavailableResponse() throws Exception {
    String source = Files.readString(sourcePath());

    assertTrue(source.contains("ctx.status(503)"));
    assertTrue(source.contains("email_delivery_unavailable"));
    assertTrue(source.contains("ctx.status(500)"));
    assertTrue(source.contains("email_delivery_failed"));
  }

  @Test
  void inFlightChallengeUsesConflictResponseInsteadOfDeliveryFailure() throws Exception {
    String source = Files.readString(sourcePath());

    assertTrue(source.contains("ctx.status(409)"));
    assertTrue(source.contains("email_challenge_in_progress"));
    assertTrue(source.contains("ChallengeInProgressException"));
  }

  private static Path sourcePath() {
    Path current = Path.of("").toAbsolutePath();
    for (int depth = 0; depth < 8 && current != null; depth++) {
      Path candidate = current.resolve(
          "src/main/java/io/github/addxiaoyi/starx/velocity/http/admin/EmailChallengeHandler.java");
      if (Files.isRegularFile(candidate)) return candidate;
      candidate = current.resolve(
          "starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/http/admin/EmailChallengeHandler.java");
      if (Files.isRegularFile(candidate)) return candidate;
      current = current.getParent();
    }
    throw new IllegalStateException("EmailChallengeHandler.java source is unavailable");
  }
}
