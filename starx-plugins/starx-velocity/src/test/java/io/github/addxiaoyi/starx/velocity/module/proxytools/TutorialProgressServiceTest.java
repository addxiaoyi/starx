package io.github.addxiaoyi.starx.velocity.module.proxytools;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TutorialProgressServiceTest {
  @Test
  void advancesPlayersThroughFiniteStepsAndDoesNotOvershoot() {
    TutorialProgressService service = new TutorialProgressService(3);
    assertEquals(0, service.step("uuid"));
    assertEquals(1, service.advance("uuid"));
    assertEquals(2, service.advance("uuid"));
    assertEquals(3, service.advance("uuid"));
    assertEquals(3, service.advance("uuid"));
    assertTrue(service.completed("uuid"));
  }

  @Test
  void resetRemovesProgress() {
    TutorialProgressService service = new TutorialProgressService(2);
    service.advance("uuid");
    service.reset("uuid");
    assertEquals(0, service.step("uuid"));
    assertFalse(service.completed("uuid"));
  }

  @Test
  void completeJumpsDirectlyToTheFinalStep() {
    TutorialProgressService service = new TutorialProgressService(8);

    assertEquals(8, service.complete("uuid"));
    assertTrue(service.completed("uuid"));
  }

  @Test
  void identityResolversAlwaysRetainTheCurrentUuid() {
    UUID current = UUID.randomUUID();
    UUID legacy = UUID.randomUUID();

    assertEquals(
        Set.of(current),
        TutorialProgressService.normalizeKnownMinecraftUuids(current, Set.of()));
    assertEquals(
        Set.of(current, legacy),
        TutorialProgressService.normalizeKnownMinecraftUuids(current, Set.of(legacy)));
  }

  @Test
  void missingCanonicalUuidFallsBackToTheCurrentUuid() {
    UUID current = UUID.randomUUID();

    assertEquals(current, TutorialProgressService.normalizeCanonicalUuid(current, null));
  }
}
