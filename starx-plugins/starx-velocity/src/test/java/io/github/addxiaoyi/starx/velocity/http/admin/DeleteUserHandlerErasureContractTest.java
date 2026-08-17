package io.github.addxiaoyi.starx.velocity.http.admin;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DeleteUserHandlerErasureContractTest {
  @Test
  void deleteHandlerUsesTheAccountErasureRepository() throws Exception {
    String source = Files.readString(Path.of(
        "src/main/java/io/github/addxiaoyi/starx/velocity/http/admin/DeleteUserHandler.java"));

    assertTrue(source.contains("JdbcAccountErasureRepository"));
    assertTrue(source.contains("accountEraser.eraseAndCompletePending"));
    assertTrue(source.contains("Set<UUID> sessionUuids = knownSessionUuids"));
    assertTrue(source.indexOf("Set<UUID> sessionUuids = knownSessionUuids")
        < source.indexOf("accountEraser.eraseAndCompletePending"));
    assertTrue(source.contains("this.authService.logout"));
    assertTrue(source.contains("knownMinecraftUuidsResolver"));
    assertTrue(source.contains("logoutKnownSessions"));
    assertTrue(source.contains("disconnectKnownSessions"));
    assertTrue(source.contains("disconnect.accept"));
  }
}
