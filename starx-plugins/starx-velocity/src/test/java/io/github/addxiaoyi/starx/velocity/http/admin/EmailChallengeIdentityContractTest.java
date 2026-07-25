package io.github.addxiaoyi.starx.velocity.http.admin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EmailChallengeIdentityContractTest {
  @Test
  void confirmedEmailBindsToChallengeUuidNotBodyUsername() throws Exception {
    String source = Files.readString(Path.of(
        "src/main/java/io/github/addxiaoyi/starx/velocity/http/admin/EmailChallengeHandler.java"));

    assertTrue(source.contains("this.auth.bindEmail(playerId, email)"));
    assertFalse(source.contains("request.username"));
    assertFalse(source.matches("(?s).*class Request.*String username;.*"));
  }
}
