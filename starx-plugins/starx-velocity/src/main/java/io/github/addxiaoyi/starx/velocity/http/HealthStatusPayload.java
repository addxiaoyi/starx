package io.github.addxiaoyi.starx.velocity.http;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

final class HealthStatusPayload {

  private HealthStatusPayload() {}

  static Map<String, Object> from(
      Instant timestamp,
      long uptimeMillis,
      int onlinePlayers,
      int registeredServers,
      int observedBackends,
      int onlineBackends,
      long heapUsedBytes,
      long heapCommittedBytes,
      long heapMaxBytes,
      int availableProcessors) {
    Map<String, Object> proxy = new LinkedHashMap<>();
    proxy.put("onlinePlayers", nonNegative(onlinePlayers));
    proxy.put("registeredServers", nonNegative(registeredServers));

    Map<String, Object> backends = new LinkedHashMap<>();
    backends.put("observed", nonNegative(observedBackends));
    backends.put("online", nonNegative(onlineBackends));

    Map<String, Object> jvm = new LinkedHashMap<>();
    jvm.put("heapUsedBytes", nonNegative(heapUsedBytes));
    jvm.put("heapCommittedBytes", nonNegative(heapCommittedBytes));
    jvm.put("heapMaxBytes", nonNegative(heapMaxBytes));
    jvm.put("availableProcessors", nonNegative(availableProcessors));

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("status", "ok");
    payload.put("timestamp", timestamp.toString());
    payload.put("uptimeMillis", nonNegative(uptimeMillis));
    payload.put("proxy", proxy);
    payload.put("backends", backends);
    payload.put("jvm", jvm);
    return payload;
  }

  private static int nonNegative(int value) {
    return Math.max(0, value);
  }

  private static long nonNegative(long value) {
    return Math.max(0L, value);
  }
}
