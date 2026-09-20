package io.github.addxiaoyi.starx.api.extension;

import java.time.Instant;
import java.util.Objects;

/**
 * Health status for an extension, used for monitoring and diagnostics.
 */
public interface StarxExtensionHealth {

    /**
     * Health status levels.
     */
    enum Status {
        /** Extension is healthy and operating normally */
        HEALTHY,
        /** Extension is degraded but still functional */
        DEGRADED,
        /** Extension is not functioning properly */
        UNHEALTHY,
        /** Extension health is unknown */
        UNKNOWN
    }

    /**
     * Gets the current health status.
     *
     * @return current health status
     */
    Status status();

    /**
     * Gets the timestamp of the last health check.
     *
     * @return timestamp of last health check
     */
    Instant lastChecked();

    /**
     * Gets the health check message.
     *
     * @return health check message
     */
    String message();

    /**
     * Gets additional details about the health status.
     *
     * @return health details
     */
    java.util.Map<String, Object> details();

    /**
     * Checks if the extension is healthy.
     *
     * @return true if healthy
     */
    default boolean isHealthy() {
        return status() == Status.HEALTHY;
    }

    /**
     * Simple implementation of extension health.
     */
    class SimpleHealth implements StarxExtensionHealth {
        private final Status status;
        private final Instant lastChecked;
        private final String message;
        private final java.util.Map<String, Object> details;

        public SimpleHealth(Status status, String message) {
            this(status, message, java.util.Collections.emptyMap());
        }

        public SimpleHealth(Status status, String message, java.util.Map<String, Object> details) {
            this.status = Objects.requireNonNull(status, "status");
            this.message = Objects.requireNonNull(message, "message");
            this.lastChecked = Instant.now();
            this.details = java.util.Collections.unmodifiableMap(
                new java.util.HashMap<>(details != null ? details : java.util.Collections.emptyMap()));
        }

        @Override
        public Status status() {
            return status;
        }

        @Override
        public Instant lastChecked() {
            return lastChecked;
        }

        @Override
        public String message() {
            return message;
        }

        @Override
        public java.util.Map<String, Object> details() {
            return details;
        }
    }

    /**
     * Builder for creating health status instances.
     */
    class Builder {
        private Status status = Status.UNKNOWN;
        private String message = "";
        private final java.util.Map<String, Object> details = new java.util.HashMap<>();

        public Builder status(Status status) {
            this.status = Objects.requireNonNull(status, "status");
            return this;
        }

        public Builder message(String message) {
            this.message = Objects.requireNonNull(message, "message");
            return this;
        }

        public Builder detail(String key, Object value) {
            Objects.requireNonNull(key, "key");
            details.put(key, value);
            return this;
        }

        public Builder details(java.util.Map<String, ?> map) {
            if (map != null) {
                details.putAll(map);
            }
            return this;
        }

        public StarxExtensionHealth build() {
            return new SimpleHealth(status, message, details);
        }
    }

    /**
     * Creates a new health builder.
     *
     * @return new builder instance
     */
    static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a healthy status.
     *
     * @return healthy status
     */
    static StarxExtensionHealth healthy() {
        return healthy("Extension is operating normally");
    }

    /**
     * Creates a healthy status with a message.
     *
     * @param message health message
     * @return healthy status
     */
    static StarxExtensionHealth healthy(String message) {
        return new SimpleHealth(Status.HEALTHY, message);
    }

    /**
     * Creates a degraded status.
     *
     * @param message degradation message
     * @return degraded status
     */
    static StarxExtensionHealth degraded(String message) {
        return new SimpleHealth(Status.DEGRADED, message);
    }

    /**
     * Creates an unhealthy status.
     *
     * @param message failure message
     * @return unhealthy status
     */
    static StarxExtensionHealth unhealthy(String message) {
        return new SimpleHealth(Status.UNHEALTHY, message);
    }

    /**
     * Creates an unknown status.
     *
     * @return unknown status
     */
    static StarxExtensionHealth unknown() {
        return new SimpleHealth(Status.UNKNOWN, "Health status unknown");
    }
}
