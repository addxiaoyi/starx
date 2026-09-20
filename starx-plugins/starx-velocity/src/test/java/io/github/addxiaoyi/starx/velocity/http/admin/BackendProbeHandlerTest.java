package io.github.addxiaoyi.starx.velocity.http.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.addxiaoyi.starx.api.bridge.BridgeMessage;
import io.github.addxiaoyi.starx.api.bridge.BridgeProtocol;
import io.github.addxiaoyi.starx.velocity.bridge.BackendCommandMailbox;
import org.junit.jupiter.api.Test;

final class BackendProbeHandlerTest {

  @Test
  void queuesAStatusProbeForTheExactRegisteredServer() {
    BackendCommandMailbox mailbox = new BackendCommandMailbox(2);

    BackendProbeHandler.ProbeResult result = BackendProbeHandler.enqueue(
        "factions", "factions"::equals, mailbox, () -> "probe-1");

    assertEquals(BackendProbeHandler.ProbeStatus.QUEUED, result.status());
    assertEquals("factions", result.server());
    assertEquals("probe-1", result.correlationId());
    BridgeMessage command = mailbox.poll("factions").orElseThrow();
    assertEquals(BridgeProtocol.STATUS_REQUEST, command.type());
    assertEquals("probe-1", command.correlationId());
  }

  @Test
  void rejectsInvalidUnknownAndFullServerMailboxes() {
    BackendCommandMailbox mailbox = new BackendCommandMailbox(1);

    assertEquals(
        BackendProbeHandler.ProbeStatus.INVALID_SERVER,
        BackendProbeHandler.enqueue(
            "../factions", name -> true, mailbox, () -> "probe-invalid").status());
    assertEquals(
        BackendProbeHandler.ProbeStatus.UNKNOWN_SERVER,
        BackendProbeHandler.enqueue(
            "lobby", "factions"::equals, mailbox, () -> "probe-unknown").status());

    mailbox.offer("factions", BridgeMessage.statusRequest("proxy", "existing"));
    assertEquals(
        BackendProbeHandler.ProbeStatus.MAILBOX_FULL,
        BackendProbeHandler.enqueue(
            "factions", "factions"::equals, mailbox, () -> "probe-full").status());
  }
}
