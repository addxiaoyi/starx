package io.github.addxiaoyi.starx.common.database;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class IpSessionDialectContractTest {
  @Test
  void ipSessionsUsePortableNaturalKeyAndSql() throws Exception {
    String repository = Files.readString(Path.of(
        "src/main/java/io/github/addxiaoyi/starx/common/database/JdbcIpSessionRepository.java"));
    String manager = Files.readString(Path.of(
        "src/main/java/io/github/addxiaoyi/starx/common/database/DatabaseManager.java"));

    assertFalse(repository.contains("ON CONFLICT"));
    assertFalse(repository.contains("excluded."));
    assertFalse(repository.contains("id, player_uuid"));
    assertFalse(manager.contains("AUTOINCREMENT"));
    assertTrue(manager.contains("PRIMARY KEY (player_uuid, ip_address)"));
  }
}
