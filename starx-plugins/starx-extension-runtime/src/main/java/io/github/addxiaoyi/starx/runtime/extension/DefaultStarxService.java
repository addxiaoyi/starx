package io.github.addxiaoyi.starx.runtime.extension;

import io.github.addxiaoyi.starx.api.bridge.PlatformKind;
import io.github.addxiaoyi.starx.api.extension.ApiVersion;
import io.github.addxiaoyi.starx.api.extension.StarxApi;
import io.github.addxiaoyi.starx.api.extension.StarxCapabilities;
import io.github.addxiaoyi.starx.api.extension.StarxEventSubscription;
import io.github.addxiaoyi.starx.api.extension.StarxExtension;
import io.github.addxiaoyi.starx.api.extension.StarxExtensionContext;
import io.github.addxiaoyi.starx.api.extension.StarxExtensionDescriptor;
import io.github.addxiaoyi.starx.api.extension.StarxExtensionRegistration;
import io.github.addxiaoyi.starx.api.extension.StarxExtensionSnapshot;
import io.github.addxiaoyi.starx.api.extension.StarxExtensionState;
import io.github.addxiaoyi.starx.api.extension.StarxService;
import io.github.addxiaoyi.starx.api.extension.StarxServiceEvent;
import io.github.addxiaoyi.starx.api.extension.StarxServiceEventTypes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Platform-neutral StarX extension runtime. This module is not part of the public API contract. */
public final class DefaultStarxService implements StarxService, AutoCloseable {
  private static final System.Logger LOG = System.getLogger(DefaultStarxService.class.getName());
  private final String implementationVersion;
  private final PlatformKind platform;
  private final Set<String> capabilities;
  private final Map<String, Registration> registrations = new ConcurrentHashMap<>();
  private final List<Registration> registrationOrder = new CopyOnWriteArrayList<>();
  private final Map<String, CopyOnWriteArrayList<Consumer<StarxServiceEvent>>> listeners =
      new ConcurrentHashMap<>();
  private final AtomicBoolean closed = new AtomicBoolean();

  public DefaultStarxService(
      String implementationVersion,
      PlatformKind platform,
      Set<String> platformCapabilities) {
    this.implementationVersion = requireText(implementationVersion, "implementationVersion");
    this.platform = Objects.requireNonNull(platform, "platform");
    LinkedHashSet<String> values = new LinkedHashSet<>(StarxCapabilities.coreFor(platform));
    if (platformCapabilities != null) {
      platformCapabilities.forEach(value -> values.add(StarxCapabilities.requireValid(value)));
    }
    this.capabilities = Set.copyOf(values);
  }

  @Override public ApiVersion apiVersion() { return StarxApi.VERSION; }
  @Override public String implementationVersion() { return this.implementationVersion; }
  @Override public PlatformKind platform() { return this.platform; }
  @Override public Set<String> capabilities() { return this.capabilities; }

  @Override
  public StarxExtensionRegistration registerExtension(
      StarxExtensionDescriptor descriptor,
      StarxExtension extension) {
    this.requireOpen();
    Objects.requireNonNull(descriptor, "descriptor");
    Objects.requireNonNull(extension, "extension");
    if (!StarxApi.supports(descriptor.requiredApi())) {
      throw new IllegalArgumentException(
          "Extension " + descriptor.id() + " requires StarX API " + descriptor.requiredApi()
              + " but runtime provides " + StarxApi.VERSION);
    }
    LinkedHashSet<String> missing = new LinkedHashSet<>(descriptor.requiredCapabilities());
    missing.removeAll(this.capabilities);
    if (!missing.isEmpty()) {
      throw new IllegalArgumentException(
          "Extension " + descriptor.id() + " requires unavailable capabilities: " + missing);
    }
    Registration registration = new Registration(descriptor, extension);
    if (this.registrations.putIfAbsent(descriptor.id(), registration) != null) {
      throw new IllegalArgumentException("Duplicate StarX extension id: " + descriptor.id());
    }
    this.registrationOrder.add(registration);
    try {
      extension.onEnable(registration.context);
      registration.enabledAt = Instant.now();
      registration.state = StarxExtensionState.ENABLED;
      this.publishSystemEvent(StarxServiceEventTypes.EXTENSION_ENABLED, Map.of(
          "id", descriptor.id(), "name", descriptor.name(), "version", descriptor.version()));
      return registration;
    } catch (Throwable error) {
      registration.state = StarxExtensionState.FAILED;
      registration.failure = failureMessage(error);
      this.registrations.remove(descriptor.id(), registration);
      this.registrationOrder.remove(registration);
      registration.context.closeSubscriptions();
      try { extension.onDisable(registration.context); } catch (Throwable cleanup) { error.addSuppressed(cleanup); }
      if (error instanceof Error fatal) throw fatal;
      throw new IllegalStateException("Unable to enable StarX extension " + descriptor.id(), error);
    }
  }

  @Override
  public Optional<StarxExtensionSnapshot> extension(String id) {
    return Optional.ofNullable(this.registrations.get(requireText(id, "id")))
        .map(Registration::snapshot);
  }

  @Override
  public List<StarxExtensionSnapshot> extensions() {
    return this.registrations.values().stream()
        .map(Registration::snapshot)
        .sorted(Comparator.comparing(snapshot -> snapshot.descriptor().id()))
        .toList();
  }

  @Override
  public StarxEventSubscription subscribe(String eventType, Consumer<StarxServiceEvent> listener) {
    this.requireOpen();
    String type = requireText(eventType, "eventType");
    Consumer<StarxServiceEvent> subscriber = Objects.requireNonNull(listener, "listener");
    CopyOnWriteArrayList<Consumer<StarxServiceEvent>> bucket =
        this.listeners.computeIfAbsent(type, ignored -> new CopyOnWriteArrayList<>());
    bucket.add(subscriber);
    AtomicBoolean active = new AtomicBoolean(true);
    return () -> {
      if (!active.compareAndSet(true, false)) return;
      this.listeners.computeIfPresent(type, (ignored, current) -> {
        current.remove(subscriber);
        return current.isEmpty() ? null : current;
      });
    };
  }

  public void publishSystemEvent(String type, Map<String, ?> payload) {
    this.publish(StarxServiceEvent.create(requireText(type, "type"), "starx", payload));
  }

  @Override
  public void close() {
    if (!this.closed.compareAndSet(false, true)) return;
    List<Registration> reverse = new ArrayList<>(this.registrationOrder);
    Collections.reverse(reverse);
    RuntimeException failure = null;
    for (Registration registration : reverse) {
      try { registration.disable(); } catch (RuntimeException error) {
        if (failure == null) failure = new IllegalStateException("Unable to disable StarX extensions");
        failure.addSuppressed(error);
      }
    }
    this.registrationOrder.clear();
    this.registrations.clear();
    this.listeners.clear();
    if (failure != null) throw failure;
  }

  private void publish(StarxServiceEvent event) {
    if (this.closed.get()) return;
    this.deliver(this.listeners.get(event.type()), event);
    if (!StarxApi.ALL_EVENTS.equals(event.type())) {
      this.deliver(this.listeners.get(StarxApi.ALL_EVENTS), event);
    }
  }

  private void deliver(List<Consumer<StarxServiceEvent>> subscribers, StarxServiceEvent event) {
    if (subscribers == null) return;
    for (Consumer<StarxServiceEvent> listener : subscribers) {
      try { listener.accept(event); } catch (RuntimeException error) {
        LOG.log(System.Logger.Level.WARNING,
            "StarX extension event listener failed for " + event.type(), error);
      }
    }
  }

  private void requireOpen() {
    if (this.closed.get()) throw new IllegalStateException("StarX extension service is closed");
  }

  private static String requireText(String value, String label) {
    String normalized = Objects.requireNonNull(value, label).trim();
    if (normalized.isEmpty()) throw new IllegalArgumentException(label + " must not be blank");
    return normalized;
  }

  private static String failureMessage(Throwable error) {
    String message = error.getMessage();
    return message == null || message.isBlank() ? error.getClass().getName() : message;
  }

  private final class Registration implements StarxExtensionRegistration {
    private final StarxExtensionDescriptor descriptor;
    private final StarxExtension extension;
    private final Context context;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private volatile StarxExtensionState state = StarxExtensionState.ENABLING;
    private volatile Instant enabledAt;
    private volatile String failure = "";

    private Registration(StarxExtensionDescriptor descriptor, StarxExtension extension) {
      this.descriptor = descriptor;
      this.extension = extension;
      this.context = new Context(descriptor);
    }

    @Override public StarxExtensionDescriptor descriptor() { return this.descriptor; }
    @Override public StarxExtensionSnapshot snapshot() {
      return new StarxExtensionSnapshot(this.descriptor, this.state, this.enabledAt, this.failure);
    }
    @Override public void close() { this.disable(); }

    private void disable() {
      if (!this.active.compareAndSet(true, false)) return;
      this.state = StarxExtensionState.DISABLING;
      this.context.closeSubscriptions();
      RuntimeException failureException = null;
      try { this.extension.onDisable(this.context); } catch (Throwable error) {
        this.failure = failureMessage(error);
        this.state = StarxExtensionState.FAILED;
        failureException = new IllegalStateException(
            "Unable to disable StarX extension " + this.descriptor.id(), error);
      } finally {
        registrations.remove(this.descriptor.id(), this);
        registrationOrder.remove(this);
      }
      if (failureException == null) this.state = StarxExtensionState.DISABLED;
      publishSystemEvent(
          StarxServiceEventTypes.EXTENSION_DISABLED,
          Map.of("id", this.descriptor.id()));
      if (failureException != null) throw failureException;
    }
  }

  private final class Context implements StarxExtensionContext {
    private final StarxExtensionDescriptor descriptor;
    private final List<StarxEventSubscription> subscriptions = new CopyOnWriteArrayList<>();
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final System.Logger logger;

    private Context(StarxExtensionDescriptor descriptor) {
      this.descriptor = descriptor;
      this.logger = System.getLogger("starx.extension." + descriptor.id());
    }

    @Override public StarxService service() { return DefaultStarxService.this; }
    @Override public StarxExtensionDescriptor descriptor() { return this.descriptor; }
    @Override public PlatformKind platform() { return DefaultStarxService.this.platform; }
    @Override public Set<String> capabilities() { return DefaultStarxService.this.capabilities; }
    @Override public System.Logger logger() { return this.logger; }

    @Override
    public StarxEventSubscription subscribe(String eventType, Consumer<StarxServiceEvent> listener) {
      this.requireOpen();
      StarxEventSubscription subscription = DefaultStarxService.this.subscribe(eventType, listener);
      this.subscriptions.add(subscription);
      return subscription;
    }

    @Override
    public void publish(String eventName, Map<String, ?> payload) {
      this.requireOpen();
      DefaultStarxService.this.publish(StarxServiceEvent.create(
          "extension." + this.descriptor.id() + "." + requireText(eventName, "eventName"),
          this.descriptor.id(), payload));
    }

    private void closeSubscriptions() {
      if (!this.open.compareAndSet(true, false)) return;
      for (StarxEventSubscription subscription : this.subscriptions) {
        try { subscription.close(); } catch (RuntimeException error) {
          LOG.log(System.Logger.Level.WARNING,
              "Unable to close extension subscription for " + this.descriptor.id(), error);
        }
      }
      this.subscriptions.clear();
    }

    private void requireOpen() {
      if (!this.open.get()) {
        throw new IllegalStateException("StarX extension context is closed: " + this.descriptor.id());
      }
    }
  }
}
