package io.github.addxiaoyi.starx.velocity.module.proxytools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class RuntimeHealthReport {
  enum State {
    OK,
    WARNING,
    INFO
  }

  record Check(String name, State state, String details) {
  }

  record Report(State state, List<Check> checks) {
    Report {
      checks = List.copyOf(checks);
    }
  }

  private static final int EVENT_QUEUE_WARNING_THRESHOLD = 192;

  private RuntimeHealthReport() {
  }

  static Report from(Map<String, Object> metrics) {
    Objects.requireNonNull(metrics, "metrics");
    List<Check> checks = new ArrayList<>();
    addWebsiteSync(checks, map(metrics.get("websiteSyncRuntime")));
    addTransport(checks, map(metrics.get("websiteSyncRuntime")));
    addBackendHealth(checks, map(metrics.get("backendHealth")));
    addEventBus(checks, map(metrics.get("eventBusRuntime")));
    addHttpApi(checks, map(metrics.get("httpApiRuntime")));
    addTab(checks, map(metrics.get("crossServerTabRuntime")));

    boolean hasWarning = checks.stream().anyMatch(check -> check.state() == State.WARNING);
    boolean hasRuntimeData = checks.stream().anyMatch(check -> check.state() == State.OK);
    State overall = hasWarning ? State.WARNING : hasRuntimeData ? State.OK : State.INFO;
    return new Report(overall, checks);
  }

  private static void addWebsiteSync(List<Check> checks, Map<String, Object> sync) {
    String state = string(sync.get("state"), "unknown");
    String circuit = string(sync.get("circuitState"), "unknown");
    Map<String, Object> runtime = map(sync.get("runtime"));
    boolean queueSaturated = Boolean.TRUE.equals(runtime.get("workerQueueSaturated"));
    State health = switch (state) {
      case "ACTIVE" -> State.OK;
      case "DISABLED", "unknown" -> State.INFO;
      default -> State.WARNING;
    };
    if ("OPEN".equals(circuit) || "HALF_OPEN".equals(circuit) || queueSaturated) {
      health = State.WARNING;
    }
    checks.add(new Check("Website sync", health,
        "state=" + state + " circuit=" + circuit
            + " workerQueue=" + number(runtime.get("queueSize"))
            + " queueSaturated=" + queueSaturated));
  }

  private static void addTransport(List<Check> checks, Map<String, Object> sync) {
    Map<String, Object> transport = map(sync.get("transport"));
    if (transport.isEmpty() || !Boolean.TRUE.equals(transport.get("shared"))) {
      checks.add(new Check("Website HTTP", State.INFO, "shared transport unavailable"));
      return;
    }
    long active = number(transport.get("activeRequests"));
    long limit = number(transport.get("maxConcurrent"));
    long queued = number(transport.get("queueSize"));
    long rejected = number(transport.get("rejectedRequests"));
    State health = queued > 0 || active >= limit && limit > 0 ? State.WARNING : State.OK;
    checks.add(new Check("Website HTTP", health,
        "active=" + active + "/" + limit + " queue=" + queued + " rejectedTotal=" + rejected));
  }

  private static void addEventBus(List<Check> checks, Map<String, Object> eventBus) {
    if (eventBus.isEmpty()) {
      checks.add(new Check("Event bus", State.INFO, "metrics unavailable"));
      return;
    }
    long queued = number(eventBus.get("queuedEvents"));
    long workers = number(eventBus.get("activeWorkers"));
    long rejected = number(eventBus.get("rejectedEvents"));
    State health = queued >= EVENT_QUEUE_WARNING_THRESHOLD ? State.WARNING : State.OK;
    checks.add(new Check("Event bus", health,
        "activeWorkers=" + workers + " queue=" + queued + " rejectedTotal=" + rejected));
  }

  private static void addBackendHealth(List<Check> checks, Map<String, Object> backends) {
    if (backends.isEmpty()) {
      checks.add(new Check("Backend heartbeat", State.INFO, "no registered backend metrics"));
      return;
    }

    List<String> warnings = new ArrayList<>();
    List<String> unseen = new ArrayList<>();
    for (Map.Entry<String, Object> entry : backends.entrySet()) {
      Map<String, Object> backend = map(entry.getValue());
      if (!Boolean.TRUE.equals(backend.get("bridgeSeen"))) {
        unseen.add(entry.getKey());
        continue;
      }
      Check check = backendCheck(entry.getKey(), backend);
      if (check.state() == State.WARNING) {
        warnings.add(entry.getKey() + " " + check.details());
      }
    }

    State health = warnings.isEmpty() ? (unseen.isEmpty() ? State.OK : State.INFO) : State.WARNING;
    String details = warnings.isEmpty()
        ? "connected=" + (backends.size() - unseen.size()) + "/" + backends.size()
        : String.join(", ", warnings);
    if (!unseen.isEmpty()) {
      details += " bridgeUnseen=" + String.join(",", unseen);
    }
    checks.add(new Check("Backend heartbeat", health, details));
  }

  static Check backendCheck(String serverName, Map<String, Object> backend) {
    Objects.requireNonNull(serverName, "serverName");
    Objects.requireNonNull(backend, "backend");
    boolean bridgeSeen = Boolean.TRUE.equals(backend.get("bridgeSeen"));
    String state = string(backend.get("state"), "unknown");
    State health = !bridgeSeen
        ? State.INFO
        : "HEALTHY".equals(state) ? State.OK : State.WARNING;
    String details = "bridge=" + (bridgeSeen ? "connected" : "unseen")
        + " state=" + state
        + " admissionWeight=" + number(backend.get("admissionWeight"))
        + " missedHeartbeats=" + number(backend.get("missedHeartbeats"));
    if (bridgeSeen) {
      details += " node=" + string(backend.get("nodeId"), "unknown")
          + " platform=" + string(backend.get("platform"), "unknown")
          + " lastSeen=" + string(backend.get("lastSeen"), "unknown")
          + " capabilities=" + string(backend.get("capabilities"), "");
    }
    return new Check(serverName, health, details);
  }

  private static void addHttpApi(List<Check> checks, Map<String, Object> httpApi) {
    if (httpApi.isEmpty()) {
      checks.add(new Check("HTTP API", State.INFO, "not running"));
      return;
    }
    long active = number(httpApi.get("activeRequests"));
    long queued = number(httpApi.get("queuedRequests"));
    long rejected = number(httpApi.get("rejectedRequests"));
    State health = queued > 0 ? State.WARNING : State.OK;
    checks.add(new Check("HTTP API", health,
        "active=" + active + " queue=" + queued + " rejectedTotal=" + rejected));
  }

  private static void addTab(List<Check> checks, Map<String, Object> tab) {
    if (tab.isEmpty()) {
      checks.add(new Check("Cross-server TAB", State.INFO, "metrics unavailable"));
      return;
    }
    checks.add(new Check("Cross-server TAB", State.INFO,
        "writes/min=" + number(tab.get("tabWritesPerMinute"))
            + " scans/min=" + number(tab.get("playerScansPerMinute"))
            + " reconciles/min=" + number(tab.get("reconcilePerMinute"))));
  }

  private static Map<String, Object> map(Object value) {
    if (!(value instanceof Map<?, ?> source)) {
      return Map.of();
    }
    Map<String, Object> result = new java.util.LinkedHashMap<>();
    source.forEach((key, entry) -> {
      if (key instanceof String name) {
        result.put(name, entry);
      }
    });
    return Map.copyOf(result);
  }

  private static long number(Object value) {
    return value instanceof Number number ? number.longValue() : 0;
  }

  private static String string(Object value, String fallback) {
    return value instanceof String text && !text.isBlank() ? text : fallback;
  }
}
