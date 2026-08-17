package io.github.addxiaoyi.starx.velocity.module.welcome;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class LatestWriteGate<K> {
  private static final int LOCK_COUNT = 64;

  private final ConcurrentMap<K, Long> revisions = new ConcurrentHashMap<>();
  private final Object[] locks = new Object[LOCK_COUNT];

  LatestWriteGate() {
    for (int index = 0; index < LOCK_COUNT; index++) {
      this.locks[index] = new Object();
    }
  }

  Ticket claim(K key) {
    Objects.requireNonNull(key, "key");
    synchronized (lockFor(key)) {
      long revision = this.revisions.getOrDefault(key, 0L) + 1L;
      this.revisions.put(key, revision);
      return new Ticket(key, revision);
    }
  }

  boolean run(Ticket ticket, Runnable write) {
    Objects.requireNonNull(ticket, "ticket");
    Objects.requireNonNull(write, "write");
    synchronized (lockFor(ticket.key)) {
      if (!Long.valueOf(ticket.revision).equals(this.revisions.get(ticket.key))) {
        return false;
      }
      try {
        write.run();
      } finally {
        this.revisions.remove(ticket.key, ticket.revision);
      }
      return true;
    }
  }

  void cancel(Ticket ticket) {
    Objects.requireNonNull(ticket, "ticket");
    synchronized (lockFor(ticket.key)) {
      this.revisions.remove(ticket.key, ticket.revision);
    }
  }

  void clear() {
    this.revisions.clear();
  }

  private Object lockFor(K key) {
    return this.locks[(key.hashCode() & Integer.MAX_VALUE) % LOCK_COUNT];
  }

  final class Ticket {
    private final K key;
    private final long revision;

    private Ticket(K key, long revision) {
      this.key = key;
      this.revision = revision;
    }
  }
}
