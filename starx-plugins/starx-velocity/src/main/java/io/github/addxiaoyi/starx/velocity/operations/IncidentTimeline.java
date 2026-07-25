package io.github.addxiaoyi.starx.velocity.operations;

import io.github.addxiaoyi.starx.api.event.StarxEvent;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class IncidentTimeline {
  private static final String REDACTED = "[REDACTED]";
  private static final List<String> SENSITIVE_KEYS = List.of(
      "password", "totp", "code", "token", "apikey", "api-key", "secret");

  private final int maxTraces;
  private final int maxEventsPerTrace;
  private final LinkedHashMap<UUID, Deque<Entry>> traces = new LinkedHashMap<>(16, 0.75f, true);

  public IncidentTimeline(int maxTraces, int maxEventsPerTrace) {
    if (maxTraces <= 0 || maxEventsPerTrace <= 0) {
      throw new IllegalArgumentException("Timeline limits must be positive");
    }
    this.maxTraces = maxTraces;
    this.maxEventsPerTrace = maxEventsPerTrace;
  }

  public synchronized void append(StarxEvent event) {
    Objects.requireNonNull(event, "event");
    UUID correlationId = correlationId(event);
    Deque<Entry> entries = this.traces.computeIfAbsent(correlationId, ignored -> new ArrayDeque<>());
    entries.addLast(new Entry(event.eventId(), event.type(), event.timestamp(), redact(event.payload())));
    while (entries.size() > this.maxEventsPerTrace) entries.removeFirst();
    while (this.traces.size() > this.maxTraces) {
      UUID oldest = this.traces.keySet().iterator().next();
      this.traces.remove(oldest);
    }
  }

  public synchronized Optional<Trace> get(UUID correlationId) {
    Objects.requireNonNull(correlationId, "correlationId");
    Deque<Entry> entries = this.traces.get(correlationId);
    return entries == null
        ? Optional.empty()
        : Optional.of(new Trace(correlationId, new ArrayList<>(entries)));
  }

  public synchronized List<Trace> snapshot() {
    List<Trace> result = new ArrayList<>(this.traces.size());
    this.traces.forEach((id, entries) -> result.add(new Trace(id, new ArrayList<>(entries))));
    return List.copyOf(result);
  }

  private static UUID correlationId(StarxEvent event) {
    Object raw = event.payload().get("correlationId");
    if (raw == null) return event.eventId();
    try {
      return UUID.fromString(String.valueOf(raw));
    } catch (IllegalArgumentException error) {
      return event.eventId();
    }
  }

  private static Map<String, Object> redact(Map<String, Object> payload) {
    Map<String, Object> clean = new LinkedHashMap<>();
    payload.forEach((key, value) -> clean.put(key, isSensitive(key) ? REDACTED : value));
    return Map.copyOf(clean);
  }

  private static boolean isSensitive(String key) {
    String normalized = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    return SENSITIVE_KEYS.stream()
        .map(candidate -> candidate.replace("-", ""))
        .anyMatch(normalized::contains);
  }

  public record Entry(UUID eventId, String type, Instant timestamp, Map<String, Object> payload) {
    public Entry {
      payload = Map.copyOf(payload);
    }
  }

  public record Trace(UUID correlationId, List<Entry> events) {
    public Trace {
      events = List.copyOf(events);
    }
  }
}
