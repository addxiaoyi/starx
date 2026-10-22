package io.github.addxiaoyi.starx.velocity.module.proxytools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

final class RuntimeHealthReportTest {

  @Test
  void historicalRejectedRequestsDoNotMakeAnIdleRuntimeUnhealthy() {
    RuntimeHealthReport.Report report = RuntimeHealthReport.from(Map.of(
        "websiteSyncRuntime", Map.of(
            "state", "ACTIVE",
            "transport", Map.of(
                "shared", true,
                "activeRequests", 0,
                "maxConcurrent", 4,
                "queueSize", 0,
                "rejectedRequests", 5)),
        "eventBusRuntime", Map.of(
            "queuedEvents", 0,
            "activeWorkers", 0,
            "rejectedEvents", 2),
        "backendHealth", Map.of("lobby", Map.of(
            "bridgeSeen", true,
            "state", "HEALTHY",
            "admissionWeight", 100,
            "missedHeartbeats", 0)),
        "httpApiRuntime", Map.of(
            "activeRequests", 0,
            "queuedRequests", 0,
            "rejectedRequests", 3),
        "crossServerTabRuntime", Map.of(
            "tabWritesPerMinute", 4,
            "playerScansPerMinute", 2,
            "reconcilePerMinute", 1)));

    assertEquals(RuntimeHealthReport.State.OK, report.state());
  }

  @Test
  void reportsWebsiteBackoffAndQueuedRequestsAsWarnings() {
    RuntimeHealthReport.Report report = RuntimeHealthReport.from(Map.of(
        "websiteSyncRuntime", Map.of(
            "state", "BACKOFF",
            "transport", Map.of(
                "shared", true,
                "activeRequests", 4,
                "maxConcurrent", 4,
                "queueSize", 2,
                "rejectedRequests", 0)),
        "eventBusRuntime", Map.of(
            "queuedEvents", 0,
            "activeWorkers", 1,
            "rejectedEvents", 0),
        "backendHealth", Map.of("lobby", Map.of(
            "bridgeSeen", true,
            "state", "OFFLINE",
            "admissionWeight", 0,
            "missedHeartbeats", 3)),
        "httpApiRuntime", Map.of(
            "activeRequests", 1,
            "queuedRequests", 1,
            "rejectedRequests", 0),
        "crossServerTabRuntime", Map.of(
            "tabWritesPerMinute", 0,
            "playerScansPerMinute", 0,
            "reconcilePerMinute", 0)));

    assertEquals(RuntimeHealthReport.State.WARNING, report.state());
    assertEquals(4, report.checks().stream()
        .filter(check -> check.state() == RuntimeHealthReport.State.WARNING)
        .count());
    assertEquals("Backend heartbeat", report.checks().stream()
        .filter(check -> check.state() == RuntimeHealthReport.State.WARNING)
        .map(RuntimeHealthReport.Check::name)
        .filter("Backend heartbeat"::equals)
        .findFirst()
        .orElseThrow());
  }

  @Test
  void handlesMissingOptionalSubsystemMetrics() {
    RuntimeHealthReport.Report report = RuntimeHealthReport.from(Map.of());

    assertEquals(RuntimeHealthReport.State.INFO, report.state());
    assertEquals(6, report.checks().size());
  }

  @Test
  void doesNotTreatBackendsWithoutStarxBridgeAsHeartbeatFailures() {
    RuntimeHealthReport.Report report = RuntimeHealthReport.from(Map.of(
        "backendHealth", Map.of("legacy", Map.of(
            "bridgeSeen", false,
            "state", "HEALTHY",
            "admissionWeight", 100,
            "missedHeartbeats", 0))));

    assertEquals(RuntimeHealthReport.State.INFO, report.state());
    assertEquals(RuntimeHealthReport.State.INFO, report.checks().stream()
        .filter(check -> check.name().equals("Backend heartbeat"))
        .findFirst()
        .orElseThrow()
        .state());
  }

  @Test
  void backendHealthDetailsShareTheAggregateWarningPolicy() {
    RuntimeHealthReport.Check check = RuntimeHealthReport.backendCheck("survival", Map.of(
        "bridgeSeen", true,
        "state", "DRAINING",
        "admissionWeight", 0,
        "missedHeartbeats", 2,
        "nodeId", "survival-node",
        "platform", "PAPER",
        "lastSeen", "2026-10-21T00:00:00Z",
        "capabilities", "skin,tab"));

    assertEquals("survival", check.name());
    assertEquals(RuntimeHealthReport.State.WARNING, check.state());
    assertTrue(check.details().contains("missedHeartbeats=2"));
    assertTrue(check.details().contains("capabilities=skin,tab"));
  }

  @Test
  void reportsCircuitAndWorkerQueuePressureEvenWhenHeartbeatIsActive() {
    RuntimeHealthReport.Report report = RuntimeHealthReport.from(Map.of(
        "websiteSyncRuntime", Map.of(
            "state", "ACTIVE",
            "circuitState", "OPEN",
            "runtime", Map.of(
                "queueSize", 8,
                "workerQueueSaturated", true),
            "transport", Map.of(
                "shared", true,
                "activeRequests", 4,
                "maxConcurrent", 4,
                "queueSize", 0,
                "rejectedRequests", 7))));

    RuntimeHealthReport.Check sync = report.checks().stream()
        .filter(check -> check.name().equals("Website sync"))
        .findFirst()
        .orElseThrow();
    RuntimeHealthReport.Check transport = report.checks().stream()
        .filter(check -> check.name().equals("Website HTTP"))
        .findFirst()
        .orElseThrow();

    assertEquals(RuntimeHealthReport.State.WARNING, report.state());
    assertEquals(RuntimeHealthReport.State.WARNING, sync.state());
    assertEquals(RuntimeHealthReport.State.WARNING, transport.state());
    assertTrue(sync.details().contains("queueSaturated=true"));
    assertTrue(transport.details().contains("active=4/4"));
  }
}
