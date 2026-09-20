package io.github.addxiaoyi.starx.api.extension;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable top-level event delivered by the public extension service.
 *
 * @param type stable event type
 * @param eventId unique event identifier
 * @param timestamp creation timestamp
 * @param source event source identifier
 * @param payload immutable payload values
 */
public record StarxServiceEvent(
    String type,
    UUID eventId,
    Instant timestamp,
    String source,
    Map<String, Object> payload) {
  /**
   * Validates and creates an immutable service event.
   *
   * @param type stable event type
   * @param eventId unique event identifier
   * @param timestamp creation timestamp
   * @param source event source identifier
   * @param payload event payload
   */
  public StarxServiceEvent {
    type = requireText(type, "type", 160);
    eventId = Objects.requireNonNull(eventId, "eventId");
    timestamp = Objects.requireNonNull(timestamp, "timestamp");
    source = requireText(source, "source", 64);
    LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
    if (payload != null) {
      payload.forEach((key, value) -> copy.put(
          requireText(key, "payload key", 96), Objects.requireNonNull(value, "payload value")));
    }
    payload = Map.copyOf(copy);
  }

  /**
   * Creates a new event with a random identifier and the current timestamp.
   *
   * @param type stable event type
   * @param source event source identifier
   * @param payload event payload
   * @return immutable service event
   */
  public static StarxServiceEvent create(String type, String source, Map<String, ?> payload) {
    LinkedHashMap<String, Object> values = new LinkedHashMap<>();
    if (payload != null) payload.forEach(values::put);
    return new StarxServiceEvent(type, UUID.randomUUID(), Instant.now(), source, values);
  }

  private static String requireText(String value, String label, int maxLength) {
    String normalized = Objects.requireNonNull(value, label).trim();
    if (normalized.isEmpty() || normalized.length() > maxLength) {
      throw new IllegalArgumentException(label + " must contain 1-" + maxLength + " characters");
    }
    return normalized;
  }
}
