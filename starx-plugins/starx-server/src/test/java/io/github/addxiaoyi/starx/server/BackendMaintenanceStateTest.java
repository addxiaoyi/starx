package io.github.addxiaoyi.starx.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class BackendMaintenanceStateTest {

  @Test
  void startsFromTheLastPersistedBackendConfig() {
    BackendMaintenanceState state = new BackendMaintenanceState(true, ignored -> { });

    assertTrue(state.enabled());
  }

  @Test
  void persistsOnlyActualChanges() {
    List<Boolean> writes = new ArrayList<>();
    BackendMaintenanceState state = new BackendMaintenanceState(false, writes::add);

    assertFalse(state.update(false));
    assertTrue(state.update(true));
    assertFalse(state.update(true));
    assertEquals(List.of(true), writes);
  }
}
