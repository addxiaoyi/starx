package io.github.addxiaoyi.starx.api.extension;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Extension configuration API for managing extension-specific settings.
 * Provides type-safe access to configuration values with defaults.
 */
public interface StarxExtensionConfig {

    /**
     * Gets a configuration value by key.
     *
     * @param key configuration key
     * @param <T> value type
     * @return optional configuration value
     */
    <T> Optional<T> get(String key);

    /**
     * Gets a configuration value by key with a default value.
     *
     * @param key configuration key
     * @param defaultValue default value if not present
     * @param <T> value type
     * @return configuration value or default
     */
    <T> T get(String key, T defaultValue);

    /**
     * Gets a required configuration value by key.
     *
     * @param key configuration key
     * @param <T> value type
     * @return configuration value
     * @throws IllegalStateException if value is not present
     */
    <T> T getRequired(String key);

    /**
     * Gets a configuration value as a specific type.
     *
     * @param key configuration key
     * @param type expected type class
     * @param <T> value type
     * @return optional typed configuration value
     */
    <T> Optional<T> getAs(String key, Class<T> type);

    /**
     * Gets a configuration value as a specific type with default.
     *
     * @param key configuration key
     * @param type expected type class
     * @param defaultValue default value if not present or wrong type
     * @param <T> value type
     * @return typed configuration value or default
     */
    <T> T getAs(String key, Class<T> type, T defaultValue);

    /**
     * Gets all configuration keys.
     *
     * @return immutable set of configuration keys
     */
    java.util.Set<String> keys();

    /**
     * Checks if a configuration key exists.
     *
     * @param key configuration key
     * @return true if the key exists
     */
    boolean contains(String key);

    /**
     * Gets a configuration section as a nested config.
     *
     * @param prefix key prefix for the section
     * @return nested configuration
     */
    StarxExtensionConfig section(String prefix);

    /**
     * Gets a configuration section as a nested config with a separator.
     *
     * @param prefix key prefix for the section
     * @param separator separator between prefix and keys
     * @return nested configuration
     */
    StarxExtensionConfig section(String prefix, String separator);

    /**
     * Builder for creating immutable configuration instances.
     */
    class Builder {
        private final Map<String, Object> values;

        public Builder() {
            this.values = new java.util.HashMap<>();
        }

        public Builder(StarxExtensionConfig config) {
            this.values = new java.util.HashMap<>();
            config.keys().forEach(key -> values.put(key, config.get(key).orElse(null)));
        }

        /**
         * Sets a configuration value.
         *
         * @param key configuration key
         * @param value configuration value
         * @param <T> value type
         * @return this builder
         */
        public <T> Builder set(String key, T value) {
            Objects.requireNonNull(key, "key");
            values.put(key, value);
            return this;
        }

        /**
         * Sets a configuration value only if it's not null.
         *
         * @param key configuration key
         * @param value configuration value
         * @param <T> value type
         * @return this builder
         */
        public <T> Builder setIfNonNull(String key, T value) {
            if (value != null) {
                values.put(key, value);
            }
            return this;
        }

        /**
         * Sets all values from a map.
         *
         * @param map map of configuration values
         * @return this builder
         */
        public Builder setAll(Map<String, ?> map) {
            if (map != null) {
                values.putAll(map);
            }
            return this;
        }

        /**
         * Removes a configuration key.
         *
         * @param key configuration key
         * @return this builder
         */
        public Builder remove(String key) {
            values.remove(key);
            return this;
        }

        /**
         * Builds an immutable configuration.
         *
         * @return immutable configuration
         */
        public StarxExtensionConfig build() {
            return new SimpleExtensionConfig(Collections.unmodifiableMap(new java.util.HashMap<>(values)));
        }
    }

    /**
     * Creates a new configuration builder.
     *
     * @return new builder instance
     */
    static Builder builder() {
        return new Builder();
    }

    /**
     * Creates an empty configuration.
     *
     * @return empty configuration
     */
    static StarxExtensionConfig empty() {
        return new SimpleExtensionConfig(Collections.emptyMap());
    }

    /**
     * Creates a configuration from a map.
     *
     * @param map configuration values
     * @return configuration
     */
    static StarxExtensionConfig fromMap(Map<String, ?> map) {
        return new SimpleExtensionConfig(
            map != null ? Collections.unmodifiableMap(new java.util.HashMap<>(map)) : Collections.emptyMap()
        );
    }

    /**
     * Simple immutable implementation of StarxExtensionConfig.
     */
    final class SimpleExtensionConfig implements StarxExtensionConfig {
        private final Map<String, Object> values;
        private final String prefix;
        private final String separator;

        SimpleExtensionConfig(Map<String, Object> values) {
            this(values, "", ".");
        }

        SimpleExtensionConfig(Map<String, Object> values, String prefix, String separator) {
            this.values = values;
            this.prefix = prefix;
            this.separator = separator;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Optional<T> get(String key) {
            Objects.requireNonNull(key, "key");
            String fullKey = buildKey(key);
            Object value = values.get(fullKey);
            return value != null ? Optional.of((T) value) : Optional.empty();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(String key, T defaultValue) {
            String fullKey = buildKey(key);
            Object value = values.get(fullKey);
            return (T) (value != null ? value : defaultValue);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getRequired(String key) {
            String fullKey = buildKey(key);
            Object value = values.get(fullKey);
            if (value == null) {
                throw new IllegalStateException("Required configuration key not found: " + fullKey);
            }
            return (T) value;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Optional<T> getAs(String key, Class<T> type) {
            return get(key).filter(type::isInstance).map(type::cast);
        }

        @Override
        public <T> T getAs(String key, Class<T> type, T defaultValue) {
            return getAs(key, type).orElse(defaultValue);
        }

        @Override
        public java.util.Set<String> keys() {
            java.util.Set<String> result = new java.util.HashSet<>();
            String fullPrefix = prefix.isEmpty() ? "" : prefix + separator;
            for (String key : values.keySet()) {
                if (key.startsWith(fullPrefix)) {
                    String remaining = key.substring(fullPrefix.length());
                    int dotIndex = remaining.indexOf(separator);
                    if (dotIndex == -1) {
                        result.add(remaining);
                    } else {
                        result.add(remaining.substring(0, dotIndex));
                    }
                }
            }
            return Collections.unmodifiableSet(result);
        }

        @Override
        public boolean contains(String key) {
            return values.containsKey(buildKey(key));
        }

        @Override
        public StarxExtensionConfig section(String prefix) {
            return section(prefix, separator);
        }

        @Override
        public StarxExtensionConfig section(String prefix, String separator) {
            String newPrefix = this.prefix.isEmpty() ? prefix : this.prefix + this.separator + prefix;
            return new SimpleExtensionConfig(values, newPrefix, separator);
        }

        private String buildKey(String key) {
            return prefix.isEmpty() ? key : prefix + separator + key;
        }
    }
}
