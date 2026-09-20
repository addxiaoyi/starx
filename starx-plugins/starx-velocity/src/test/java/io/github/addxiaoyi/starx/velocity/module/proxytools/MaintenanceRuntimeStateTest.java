package io.github.addxiaoyi.starx.velocity.module.proxytools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class MaintenanceRuntimeStateTest {

  @Test
  void restoredStateIsBroadcastDuringColdStart() {
    List<Boolean> broadcasts = new ArrayList<>();
    MaintenanceRuntimeState state = new MaintenanceRuntimeState(broadcasts::add);

    state.restore(true);

    assertTrue(state.enabled());
    assertEquals(List.of(true), broadcasts);
  }

  @Test
  void unchangedCommandsDoNotCreateDuplicateWritesOrBroadcasts() {
    List<Boolean> broadcasts = new ArrayList<>();
    MaintenanceRuntimeState state = new MaintenanceRuntimeState(broadcasts::add);
    state.restore(false);

    assertFalse(state.change(false));
    assertEquals(List.of(false), broadcasts);
  }

  @Test
  void periodicReconciliationRebroadcastsTheAuthoritativeState() {
    List<Boolean> broadcasts = new ArrayList<>();
    MaintenanceRuntimeState state = new MaintenanceRuntimeState(broadcasts::add);
    state.restore(true);

    state.rebroadcast();

    assertEquals(List.of(true, true), broadcasts);
  }
}
