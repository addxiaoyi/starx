package io.github.addxiaoyi.starx.velocity.bridge;

import io.github.addxiaoyi.starx.api.bridge.BridgeMessage;
import io.github.addxiaoyi.starx.api.bridge.BridgeProtocol;
import io.github.addxiaoyi.starx.api.bridge.PlatformKind;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import java.time.Clock;
import java.time.Duration;

public final class BackendCommandMailbox {
  private static final String SERVER_PATTERN = "[A-Za-z0-9_.-]{1,64}";
  private static final Duration DEFAULT_TTL = Duration.ofMinutes(2);

  private final int capacity;
  private final Clock clock;
  private final Duration ttl;
  private final ConcurrentMap<String, Mailbox> queues =
      new ConcurrentHashMap<>();

  public BackendCommandMailbox(int capacity) {
    this(capacity, Clock.systemUTC(), DEFAULT_TTL);
  }

  BackendCommandMailbox(int capacity, Clock clock, Duration ttl) {
    if (capacity < 1 || capacity > 256) {
      throw new IllegalArgumentException("mailbox capacity must be between 1 and 256");
    }
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ttl = Objects.requireNonNull(ttl, "ttl");
    if (ttl.isZero() || ttl.isNegative()) {
      throw new IllegalArgumentException("mailbox TTL must be positive");
    }
    this.capacity = capacity;
  }

  public boolean offer(String serverName, BridgeMessage command) {
    String server = requireServerName(serverName);
    requireProxyCommand(command);
    Mailbox mailbox = this.queues.computeIfAbsent(
        server, ignored -> new Mailbox(this.capacity));
    pruneExpired(mailbox);
    boolean accepted = mailbox.commands.offer(
        new QueuedCommand(command, this.clock.instant().plus(this.ttl)));
    if (accepted) {
      mailbox.accepted.increment();
    } else {
      mailbox.rejected.increment();
    }
    return accepted;
  }

  public Optional<BridgeMessage> poll(String serverName) {
    String server = requireServerName(serverName);
    Mailbox mailbox = this.queues.get(server);
    if (mailbox == null) {
      return Optional.empty();
    }
    while (true) {
      QueuedCommand queued = mailbox.commands.poll();
      if (queued == null) return Optional.empty();
      if (!queued.expiresAt().isAfter(this.clock.instant())) {
        mailbox.rejected.increment();
        continue;
      }
      mailbox.delivered.increment();
      return Optional.of(queued.command());
    }
  }

  public Snapshot snapshot(String serverName) {
    String server = requireServerName(serverName);
    Mailbox mailbox = this.queues.get(server);
    if (mailbox == null) {
      return new Snapshot(0, 0, 0, 0);
    }
    pruneExpired(mailbox);
    return new Snapshot(
        mailbox.accepted.sum(),
        mailbox.delivered.sum(),
        mailbox.rejected.sum(),
        mailbox.commands.size());
  }

  public void clear() {
    this.queues.clear();
  }

  private void pruneExpired(Mailbox mailbox) {
    java.time.Instant now = this.clock.instant();
    mailbox.commands.removeIf(command -> {
      boolean expired = !command.expiresAt().isAfter(now);
      if (expired) mailbox.rejected.increment();
      return expired;
    });
  }

  private static void requireProxyCommand(BridgeMessage command) {
    Objects.requireNonNull(command, "command");
    boolean supported = BridgeProtocol.PROXY_HELLO.equals(command.type())
        || BridgeProtocol.STATUS_REQUEST.equals(command.type())
        || BridgeProtocol.SKIN_REQUEST.equals(command.type())
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

  public record Snapshot(long accepted, long delivered, long rejected, int queued) {
  }

  private static final class Mailbox {
    private final ArrayBlockingQueue<QueuedCommand> commands;
    private final LongAdder accepted = new LongAdder();
    private final LongAdder delivered = new LongAdder();
    private final LongAdder rejected = new LongAdder();

    private Mailbox(int capacity) {
      this.commands = new ArrayBlockingQueue<>(capacity);
    }
  }

  private record QueuedCommand(BridgeMessage command, java.time.Instant expiresAt) { }
}
