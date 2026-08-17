package io.github.addxiaoyi.starx.velocity.module.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.addxiaoyi.starx.common.event.LocalEventBus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CredentialChangeDisconnectServiceTest {
  @Test
  void schedulesDisconnectForTheChangedAccount() {
    LocalEventBus events = new LocalEventBus();
    List<Runnable> scheduled = new ArrayList<>();
    List<UUID> disconnected = new ArrayList<>();
    CredentialChangeDisconnectService service = new CredentialChangeDisconnectService(
        events, scheduled::add, disconnected::add);
    UUID playerId = UUID.randomUUID();

    events.publish("player:credentials:changed", Map.of("uuid", playerId));

    assertEquals(1, scheduled.size());
    assertEquals(List.of(), disconnected);
    scheduled.getFirst().run();
    assertEquals(List.of(playerId), disconnected);
    service.close();
  }

  @Test
  void ignoresMalformedEventsAndStopsAfterClose() {
    LocalEventBus events = new LocalEventBus();
    List<Runnable> scheduled = new ArrayList<>();
    CredentialChangeDisconnectService service = new CredentialChangeDisconnectService(
        events, scheduled::add, ignored -> { });

    events.publish("player:credentials:changed", Map.of("uuid", "not-a-uuid"));
    service.close();
    events.publish("player:credentials:changed", Map.of("uuid", UUID.randomUUID()));

    assertEquals(List.of(), scheduled);
  }

  @Test
  void schedulesDisconnectsForSecurityRevokedSessionsOnly() {
    LocalEventBus events = new LocalEventBus();
    List<Runnable> scheduled = new ArrayList<>();
    List<UUID> disconnected = new ArrayList<>();
    CredentialChangeDisconnectService service = new CredentialChangeDisconnectService(
        events, scheduled::add, disconnected::add);
    UUID accountId = UUID.randomUUID();
    UUID revokedId = UUID.randomUUID();
    UUID retainedId = UUID.randomUUID();

    events.publish("player:security:changed", Map.of(
        "uuid", accountId,
        "revokedSessionUuids", List.of(revokedId),
        "disconnectSessions", true,
        "sessionRetained", true));

    assertEquals(1, scheduled.size());
    scheduled.getFirst().run();
    assertEquals(List.of(revokedId), disconnected);
    assertFalse(disconnected.contains(accountId));
    assertFalse(disconnected.contains(retainedId));
    service.close();
  }

  @Test
  void ignoresSecurityChangesThatDoNotRequirePhysicalDisconnect() {
    LocalEventBus events = new LocalEventBus();
    List<Runnable> scheduled = new ArrayList<>();
    CredentialChangeDisconnectService service = new CredentialChangeDisconnectService(
        events, scheduled::add, ignored -> { });

    events.publish("player:security:changed", Map.of(
        "disconnectSessions", false,
        "revokedSessionUuids", List.of(UUID.randomUUID())));

    assertEquals(List.of(), scheduled);
    service.close();
  }
}
