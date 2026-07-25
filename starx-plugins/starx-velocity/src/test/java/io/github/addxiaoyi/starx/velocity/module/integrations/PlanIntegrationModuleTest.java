package io.github.addxiaoyi.starx.velocity.module.integrations;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class PlanIntegrationModuleTest {

  @Test
  void collectsBuiltInMetricsByDefault() {
    assertTrue(PlanIntegrationModule.Config.defaultConfig().enabled());
  }

  @Test
  void summarizesSamplesWithoutExposingTheFullHistory() {
    Map<String, Object> summary = PlanIntegrationModule.summarizeDataPoints(List.of(
        Map.of("timestamp", "2026-07-18T00:00:00Z", "online_players", 2),
        Map.of("timestamp", "2026-07-18T00:01:00Z", "online_players", 3)));

    assertEquals(2, summary.get("sampleCount"));
    assertEquals("2026-07-18T00:01:00Z", summary.get("lastCollectedAt"));
    assertEquals(2, summary.size());
  }
}
