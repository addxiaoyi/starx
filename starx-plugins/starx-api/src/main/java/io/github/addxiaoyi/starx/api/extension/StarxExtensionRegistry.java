package io.github.addxiaoyi.starx.api.extension;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Registry for managing StarX extensions.
 * Provides lifecycle management, health monitoring, and configuration support.
 * Integrates with the existing StarX extension system.
 */
public interface StarxExtensionRegistry {

    /**
     * Registers an extension with the registry.
     *
     * @param descriptor extension descriptor
     * @param extension extension instance
     * @return registration handle
     * @throws IllegalArgumentException if extension is already registered or descriptor is invalid
     */
    StarxExtensionRegistration register(StarxExtensionDescriptor descriptor, StarxExtension extension);

    /**
     * Gets an extension by identifier.
     *
     * @param id extension identifier
     * @return optional extension registration
     */
    Optional<StarxExtensionRegistration> get(String id);

    /**
     * Gets all registered extensions.
     *
     * @return immutable list of all registrations
     */
    List<StarxExtensionRegistration> getAll();

    /**
     * Gets extensions filtered by capability.
     *
     * @param capability required capability
     * @return immutable list of extensions with the capability
     */
    List<StarxExtensionRegistration> getByCapability(String capability);

    /**
     * Gets extensions filtered by multiple capabilities.
     *
     * @param capabilities required capabilities (all must be present)
     * @return immutable list of extensions with all capabilities
     */
    List<StarxExtensionRegistration> getByCapabilities(Set<String> capabilities);

    /**
     * Checks if an extension is registered.
     *
     * @param id extension identifier
     * @return true if registered
     */
    boolean isRegistered(String id);

    /**
     * Gets the health status of an extension.
     *
     * @param id extension identifier
     * @return optional health status
     */
    Optional<StarxExtensionHealth> getHealth(String id);

    /**
     * Gets the configuration of an extension.
     *
     * @param id extension identifier
     * @return optional configuration
     */
    Optional<StarxExtensionConfig> getConfig(String id);

    /**
     * Updates the configuration of an extension.
     *
     * @param id extension identifier
     * @param config new configuration
     * @return true if configuration was updated
     */
    boolean updateConfig(String id, StarxExtensionConfig config);

    /**
     * Updates the health status of an extension.
     *
     * @param id extension identifier
     * @param health new health status
     * @return true if health was updated
     */
    boolean updateHealth(String id, StarxExtensionHealth health);

    /**
     * Registers a lifecycle listener.
     *
     * @param listener lifecycle listener
     */
    void addLifecycleListener(StarxExtensionLifecycleListener listener);

    /**
     * Removes a lifecycle listener.
     *
     * @param listener lifecycle listener
     * @return true if listener was removed
     */
    boolean removeLifecycleListener(StarxExtensionLifecycleListener listener);

    /**
     * Gets all registered extension identifiers.
     *
     * @return immutable set of extension identifiers
     */
    Set<String> getExtensionIds();

    /**
     * Gets the number of registered extensions.
     *
     * @return extension count
     */
    int size();

    /**
     * Checks if the registry is empty.
     *
     * @return true if no extensions are registered
     */
    boolean isEmpty();

    /**
     * Default implementation of the extension registry.
     */
    class DefaultExtensionRegistry implements StarxExtensionRegistry {
        private final ConcurrentHashMap<String, StarxExtensionRegistration> extensions = new ConcurrentHashMap<>();
        private final CopyOnWriteArrayList<StarxExtensionLifecycleListener> lifecycleListeners = new CopyOnWriteArrayList<>();
        private final StarxService service;

        public DefaultExtensionRegistry(StarxService service) {
            this.service = Objects.requireNonNull(service, "service");
        }

        @Override
        public StarxExtensionRegistration register(StarxExtensionDescriptor descriptor, StarxExtension extension) {
            Objects.requireNonNull(descriptor, "descriptor");
            Objects.requireNonNull(extension, "extension");

            String id = descriptor.id();
            if (extensions.containsKey(id)) {
                throw new IllegalArgumentException("Extension already registered: " + id);
            }

            // Notify listeners
            lifecycleListeners.forEach(listener -> listener.onExtensionRegistering(descriptor));

            // Create registration with snapshot
            StarxExtensionRegistration registration = new DefaultExtensionRegistration(
                descriptor, extension, service, StarxExtensionHealth.healthy(), StarxExtensionConfig.empty()
            );

            extensions.put(id, registration);

            // Notify listeners
            lifecycleListeners.forEach(listener -> listener.onExtensionRegistered(descriptor, registration.context()));

            return registration;
        }

        @Override
        public Optional<StarxExtensionRegistration> get(String id) {
            Objects.requireNonNull(id, "id");
            return Optional.ofNullable(extensions.get(id));
        }

        @Override
        public List<StarxExtensionRegistration> getAll() {
            return Collections.unmodifiableList(new java.util.ArrayList<>(extensions.values()));
        }

        @Override
        public List<StarxExtensionRegistration> getByCapability(String capability) {
            Objects.requireNonNull(capability, "capability");
            return extensions.values().stream()
                .filter(reg -> reg.descriptor().requiredCapabilities().contains(capability))
                .toList();
        }

        @Override
        public List<StarxExtensionRegistration> getByCapabilities(Set<String> capabilities) {
            Objects.requireNonNull(capabilities, "capabilities");
            if (capabilities.isEmpty()) {
                return getAll();
            }
            return extensions.values().stream()
                .filter(reg -> reg.descriptor().requiredCapabilities().containsAll(capabilities))
                .toList();
        }

        @Override
        public boolean isRegistered(String id) {
            Objects.requireNonNull(id, "id");
            return extensions.containsKey(id);
        }

        @Override
        public Optional<StarxExtensionHealth> getHealth(String id) {
            return get(id).map(reg -> {
                StarxExtensionSnapshot snapshot = reg.snapshot();
                return switch (snapshot.state()) {
                    case ENABLED -> StarxExtensionHealth.healthy();
                    case ENABLING, DISABLING -> StarxExtensionHealth.degraded(snapshot.failure());
                    case DISABLED -> StarxExtensionHealth.unhealthy(snapshot.failure());
                    case FAILED -> StarxExtensionHealth.unhealthy(snapshot.failure());
                };
            });
        }

        @Override
        public Optional<StarxExtensionConfig> getConfig(String id) {
            return get(id).map(StarxExtensionRegistration::config);
        }

        @Override
        public boolean updateConfig(String id, StarxExtensionConfig config) {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(config, "config");

            StarxExtensionRegistration registration = extensions.get(id);
            if (registration == null) {
                return false;
            }

            StarxExtensionConfig oldConfig = registration.config();
            StarxExtensionRegistration newRegistration = new DefaultExtensionRegistration(
                registration.descriptor(),
                registration.extension(),
                service,
                registration.health(),
                config
            );

            extensions.put(id, newRegistration);

            // Notify listeners
            lifecycleListeners.forEach(listener -> 
                listener.onConfigChanged(registration.descriptor(), registration.context(), oldConfig, config));

            return true;
        }

        @Override
        public boolean updateHealth(String id, StarxExtensionHealth health) {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(health, "health");

            StarxExtensionRegistration registration = extensions.get(id);
            if (registration == null) {
                return false;
            }

            StarxExtensionHealth oldHealth = registration.health();
            StarxExtensionRegistration newRegistration = new DefaultExtensionRegistration(
                registration.descriptor(),
                registration.extension(),
                service,
                health,
                registration.config()
            );

            extensions.put(id, newRegistration);

            // Notify listeners
            lifecycleListeners.forEach(listener -> 
                listener.onHealthChanged(registration.descriptor(), registration.context(), oldHealth, health));

            return true;
        }

        @Override
        public void addLifecycleListener(StarxExtensionLifecycleListener listener) {
            Objects.requireNonNull(listener, "listener");
            lifecycleListeners.add(listener);
        }

        @Override
        public boolean removeLifecycleListener(StarxExtensionLifecycleListener listener) {
            Objects.requireNonNull(listener, "listener");
            return lifecycleListeners.remove(listener);
        }

        @Override
        public Set<String> getExtensionIds() {
            return Collections.unmodifiableSet(new java.util.HashSet<>(extensions.keySet()));
        }

        @Override
        public int size() {
            return extensions.size();
        }

        @Override
        public boolean isEmpty() {
            return extensions.isEmpty();
        }
    }

    /**
     * Default implementation of extension registration that integrates with existing StarX system.
     */
    static class DefaultExtensionRegistration implements StarxExtensionRegistration {
        private final StarxExtensionDescriptor descriptor;
        private final StarxExtension extension;
        private final StarxService service;
        private final StarxExtensionHealth health;
        private final StarxExtensionConfig config;
        private volatile StarxExtensionState state = StarxExtensionState.ENABLING;
        private volatile Instant enabledAt;
        private volatile String failure = "";

        DefaultExtensionRegistration(StarxExtensionDescriptor descriptor, StarxExtension extension,
                                       StarxService service, StarxExtensionHealth health, StarxExtensionConfig config) {
            this.descriptor = descriptor;
            this.extension = extension;
            this.service = service;
            this.health = health;
            this.config = config;
            this.enabledAt = Instant.now();
        }

        @Override
        public StarxExtensionDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public StarxExtensionSnapshot snapshot() {
            return new StarxExtensionSnapshot(descriptor, state, enabledAt, failure);
        }

        @Override
        public void close() {
            if (state == StarxExtensionState.DISABLED || state == StarxExtensionState.FAILED) {
                return;
            }

            state = StarxExtensionState.DISABLING;
            try {
                extension.onDisable(context());
                state = StarxExtensionState.DISABLED;
            } catch (Exception e) {
                state = StarxExtensionState.FAILED;
                failure = e.getMessage() != null ? e.getMessage() : "";
            }
        }

        @Override
        public StarxExtension extension() {
            return extension;
        }

        @Override
        public StarxExtensionContext context() {
            return new DefaultExtensionContext(service, descriptor);
        }

        @Override
        public StarxExtensionHealth health() {
            return health;
        }

        @Override
        public StarxExtensionConfig config() {
            return config;
        }
    }

    /**
     * Default implementation of extension context.
     */
    record DefaultExtensionContext(
        StarxService service,
        StarxExtensionDescriptor descriptor
    ) implements StarxExtensionContext {

        @Override
        public StarxService service() {
            return service;
        }

        @Override
        public StarxExtensionDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public io.github.addxiaoyi.starx.api.bridge.PlatformKind platform() {
            return service.platform();
        }

        @Override
        public Set<String> capabilities() {
            return service.capabilities();
        }

        @Override
        public System.Logger logger() {
            return System.getLogger("StarX.Extension." + descriptor.id());
        }

        @Override
        public StarxEventSubscription subscribe(String eventType, Consumer<StarxServiceEvent> listener) {
            return service.subscribe(eventType, listener);
        }

        @Override
        public void publish(String eventName, Map<String, ?> payload) {
            // Publish extension-specific event on the service event stream
            String fullEventName = "extension." + descriptor.id() + "." + eventName;
            service.publish(fullEventName, payload);
        }
    }
}
