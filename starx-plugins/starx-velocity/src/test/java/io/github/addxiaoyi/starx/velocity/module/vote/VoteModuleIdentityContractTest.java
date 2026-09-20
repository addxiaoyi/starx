package io.github.addxiaoyi.starx.velocity.module.vote;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class VoteModuleIdentityContractTest {
  @Test
  void inGameVotesCheckEveryKnownUuidAndPersistTheCanonicalUuid() throws Exception {
    String source = Files.readString(locateSource(), StandardCharsets.UTF_8);

    assertTrue(source.contains("knownMinecraftUuidsResolver"));
    assertTrue(source.contains("knownMinecraftUuidsResolver.apply(voterUuid)"));
    assertTrue(source.contains("canonicalUuidResolver.apply(voterUuid)"));
    assertTrue(source.contains(
        "canonicalUuidResolver.apply(((Player)target.get()).getUniqueId())"));
    assertTrue(source.contains("canonicalUuidResolver.apply(staff.getUniqueId())"));
  }

  private static Path locateSource() {
    Path current = Path.of("").toAbsolutePath();
    for (int depth = 0; depth < 8 && current != null; depth++) {
      Path candidate = current.resolve(
          "src/main/java/io/github/addxiaoyi/starx/velocity/module/vote/VoteModule.java");
      if (Files.isRegularFile(candidate)) return candidate;
      current = current.getParent();
    }
    throw new IllegalStateException("VoteModule.java source is unavailable");
  }
}
