package io.github.addxiaoyi.starx.velocity.http.admin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BindingUnlinkActorContractTest {
  @Test
  void auditActorIsServerAssignedNotBodyControlled() throws Exception {
    String source = Files.readString(Path.of(
        "src/main/java/io/github/addxiaoyi/starx/velocity/http/admin/BindingUnlinkHandler.java"));

    assertTrue(source.contains("request.kind, \"website\""));
    assertFalse(source.contains("request.actor"));
    assertFalse(source.matches("(?s).*class Request.*String actor;.*"));
  }
}
