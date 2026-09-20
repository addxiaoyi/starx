package io.github.addxiaoyi.starx.velocity.module.proxytools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TutorialJoinPolicyTest {
  @Test
  void promptsAnIncompletePlayerOncePerProxySession() {
    TutorialProgressService progress = new TutorialProgressService(2);
    TutorialJoinPolicy policy = new TutorialJoinPolicy(progress);
    String playerId = UUID.randomUUID().toString();

    assertTrue(policy.shouldPrompt(playerId));
    assertFalse(policy.shouldPrompt(playerId));

    policy.release(playerId);
    assertTrue(policy.shouldPrompt(playerId));
  }

  @Test
  void neverPromptsACompletedPlayer() {
    TutorialProgressService progress = new TutorialProgressService(1);
    TutorialJoinPolicy policy = new TutorialJoinPolicy(progress);
    String playerId = UUID.randomUUID().toString();
    progress.advance(playerId);

    assertFalse(policy.shouldPrompt(playerId));
  }
}
