package io.github.addxiaoyi.starx.velocity.http;

import java.util.HashMap;
import java.util.Map;

final class HmacReplayGuard {
  private final int capacity;
  private final Map<String, Long> claims = new HashMap<>();

  HmacReplayGuard(int capacity) {
    if (capacity < 1) {
      throw new IllegalArgumentException("capacity must be positive");
    }
    this.capacity = capacity;
  }

  synchronized boolean claim(String signature, long expiresAt, long now) {
    if (signature == null || signature.isBlank() || expiresAt <= now) {
      return false;
    }
    Long existing = claims.get(signature);
    if (existing != null && existing > now) {
      return false;
    }
    if (existing != null) {
      claims.remove(signature);
    }
    if (claims.size() >= capacity) {
      claims.entrySet().removeIf(entry -> entry.getValue() <= now);
    }
    if (claims.size() >= capacity) {
      return false;
    }
    claims.put(signature, expiresAt);
    return true;
  }

  synchronized void clear() {
    claims.clear();
  }

  synchronized int size() {
    return claims.size();
  }
}
