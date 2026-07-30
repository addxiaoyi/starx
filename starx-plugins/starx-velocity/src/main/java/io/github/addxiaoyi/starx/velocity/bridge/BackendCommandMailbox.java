package io.github.addxiaoyi.starx.velocity.bridge;

import io.github.addxiaoyi.starx.api.bridge.BridgeMessage;
import io.github.addxiaoyi.starx.api.bridge.BridgeProtocol;
import io.github.addxiaoyi.starx.api.bridge.PlatformKind;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

public final class BackendCommandMailbox {
  private static final String SERVER_PATTERN = "[A-Za-z0-9_.-]{1,64}";
  private static final Duration DEFAULT_COMMAND_TTL = Duration.ofMinutes(2);
  private static final Duration DEFAULT_IDLE_TTL = Duration.ofMinutes(10);
  private static final int DEFAULT_MAX_MAILBOXES = 512;

  private final int capacity;
  private final Clock clock;
  private final Duration commandTtl;
  private final Duration idleTtl;
  private final int maxMailboxes;
  private final Object retentionLock = new Object();
  private final ConcurrentMap<String, Mailbox> queues = new ConcurrentHashMap<>();

  public BackendCommandMailbox(int capacity) {
    this(capacity, Clock.systemUTC(), DEFAULT_COMMAND_TTL,
        DEFAULT_IDLE_TTL, DEFAULT_MAX_MAILBOXES);
  }

  BackendCommandMailbox(int capacity, Clock clock, Duration commandTtl) {
    this(capacity, clock, commandTtl, DEFAULT_IDLE_TTL, DEFAULT_MAX_MAILBOXES);
  }

  BackendCommandMailbox(
      int capacity,
      Clock clock,
      Duration commandTtl,
      Duration idleTtl,
      int maxMailboxes) {
    if (capacity < 1 || capacity > 256) {
      throw new IllegalArgumentException("mailbox capacity must be between 1 and 256");
    }
    this.clock = Objects.requireNonNull(clock, "clock");
    this.commandTtl = requirePositive(commandTtl, "commandTtl");
    this.idleTtl = requirePositive(idleTtl, "idleTtl");
    if (maxMailboxes < 1 || maxMailboxes > 16_384) {
      throw new IllegalArgumentException("maxMailboxes must be between 1 and 16384");
    }
    this.capacity = capacity;
    this.maxMailboxes = maxMailboxes;
  }

  public boolean offer(String serverName, BridgeMessage command) {
    String server = requireServerName(serverName);
    requireProxyCommand(command);
    while (true) {
      Instant now = this.clock.instant();
      Mailbox target = mailboxForOffer(server, now);
      if (target == null) return false;
      AtomicReference<Boolean> accepted = new AtomicReference<>();
      this.queues.computeIfPresent(server, (name, current) -> {
        if (current != target) return current;
        current.touch(now);
        pruneExpired(current, now);
        boolean queued = current.commands.offer(
            new QueuedCommand(command, now.plus(this.commandTtl)));
        if (queued) {
          current.accepted.increment();
        } else {
          current.rejected.increment();
        }
        accepted.set(queued);
        return current;
      });
      if (accepted.get() != null) return accepted.get();
    }
  }

  public Optional<BridgeMessage> poll(String serverName) {
    String server = requireServerName(serverName);
    while (true) {
      Mailbox target = this.queues.get(server);
      if (target == null) return Optional.empty();
      AtomicReference<Optional<BridgeMessage>> polled = new AtomicReference<>();
      this.queues.computeIfPresent(server, (name, current) -> {
        if (current != target) return current;
        Instant now = this.clock.instant();
        current.touch(now);
        while (true) {
          QueuedCommand queued = current.commands.poll();
          if (queued == null) {
            polled.set(Optional.empty());
            break;
          }
          if (!queued.expiresAt().isAfter(now)) {
            current.rejected.increment();
            continue;
          }
          current.delivered.increment();
          polled.set(Optional.of(queued.command()));
          break;
        }
        return current;
      });
      if (polled.get() != null) return polled.get();
    }
  }

  public Snapshot snapshot(String serverName) {
    String server = requireServerName(serverName);
    while (true) {
      Mailbox target = this.queues.get(server);
      if (target == null) return new Snapshot(0, 0, 0, 0);
      AtomicReference<Snapshot> snapshot = new AtomicReference<>();
      this.queues.computeIfPresent(server, (name, current) -> {
        if (current != target) return current;
        Instant now = this.clock.instant();
        current.touch(now);
        pruneExpired(current, now);
        snapshot.set(new Snapshot(
            current.accepted.sum(),
            current.delivered.sum(),
            current.rejected.sum(),
            current.commands.size()));
        return current;
      });
      if (snapshot.get() != null) return snapshot.get();
    }
  }

  public int pruneIdle() {
    Instant now = this.clock.instant();
    AtomicInteger removed = new AtomicInteger();
    for (String server : this.queues.keySet()) {
      this.queues.computeIfPresent(server, (name, mailbox) -> {
        pruneExpired(mailbox, now);
        boolean expired = !mailbox.lastAccess.plus(this.idleTtl).isAfter(now);
        if (expired && mailbox.commands.isEmpty()) {
          removed.incrementAndGet();
          return null;
        }
        return mailbox;
      });
    }
    return removed.get();
  }

  int mailboxCount() {
    return this.queues.size();
  }

  public void clear() {
    this.queues.clear();
  }

  private Mailbox mailboxForOffer(String server, Instant now) {
    Mailbox existing = this.queues.get(server);
    if (existing != null) return existing;
    synchronized (this.retentionLock) {
      existing = this.queues.get(server);
      if (existing != null) return existing;
      pruneIdle();
      if (this.queues.size() >= this.maxMailboxes) return null;
      Mailbox created = new Mailbox(this.capacity, now);
      this.queues.put(server, created);
      return created;
    }
  }

  private void pruneExpired(Mailbox mailbox, Instant now) {
    mailbox.commands.removeIf(command -> {
      boolean expired = !command.expiresAt().isAfter(now);
      if (expired) mailbox.rejected.increment();
      return expired;
    });
  }

  private static Duration requirePositive(Duration value, String field) {
    Duration duration = Objects.requireNonNull(value, field);
    if (duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException(field + " must be positive");
    }
    return duration;
  }

  private static void requireProxyCommand(BridgeMessage command) {
    Objects.requireNonNull(command, "command");
    boolean supported = BridgeProtocol.PROXY_HELLO.equals(command.type())
        || BridgeProtocol.STATUS_REQUEST.equals(command.type())
        || BridgeProtocol.SKIN_REQUEST.equals(command.type())
        || BridgeProtocol.SKIN_UPDATE.equals(command.type())
        || BridgeProtocol.CONFIG_SYNC.equals(command.type());
    if (command.platform() != PlatformKind.VELOCITY || !supported) {
      throw new IllegalArgumentException(
          "Unsupported proxy command for backend mailbox: " + command.type());
    }
  }

  private static String requireServerName(String value) {
    if (value == null || !value.matches(SERVER_PATTERN)) {
      throw new IllegalArgumentException("server name must match " + SERVER_PATTERN);
    }
    return value;
  }

  public record Snapshot(long accepted, long delivered, long rejected, int queued) { }

  private static final class Mailbox {
    private final ArrayBlockingQueue<QueuedCommand> commands;
    private final LongAdder accepted = new LongAdder();
    private final LongAdder delivered = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private volatile Instant lastAccess;

    private Mailbox(int capacity, Instant now) {
      this.commands = new ArrayBlockingQueue<>(capacity);
      this.lastAccess = now;
    }

    private void touch(Instant now) {
      this.lastAccess = now;
    }
  }

  private record QueuedCommand(BridgeMessage command, Instant expiresAt) { }
}
