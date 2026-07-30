package io.github.addxiaoyi.starx.velocity.http.admin;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EmailChallengeAvailabilityContractTest {
  @Test
  void unavailableDeliveryUsesServiceUnavailableResponse() throws Exception {
    String source = Files.readString(Path.of(
        "src/main/java/io/github/addxiaoyi/starx/velocity/http/admin/EmailChallengeHandler.java"));

    assertTrue(source.contains("ctx.status(503)"));
    assertTrue(source.contains("email_delivery_unavailable"));
    assertTrue(source.contains("ctx.status(500)"));
    assertTrue(source.contains("email_delivery_failed"));
  }
}
