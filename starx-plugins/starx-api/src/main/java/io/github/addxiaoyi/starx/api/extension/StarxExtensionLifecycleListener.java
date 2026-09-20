package io.github.addxiaoyi.starx.api.extension;

/**
 * Listener for extension lifecycle events.
 * Implementations can register to receive notifications about extension state changes.
 */
public interface StarxExtensionLifecycleListener {

    /**
     * Called when an extension is about to be registered.
     *
     * @param descriptor extension descriptor
     */
    default void onExtensionRegistering(StarxExtensionDescriptor descriptor) {
    }

    /**
     * Called when an extension has been successfully registered.
     *
     * @param descriptor extension descriptor
     * @param context extension context
     */
    default void onExtensionRegistered(StarxExtensionDescriptor descriptor, StarxExtensionContext context) {
    }

    /**
     * Called when an extension is about to be enabled.
     *
     * @param descriptor extension descriptor
     * @param context extension context
     */
    default void onExtensionEnabling(StarxExtensionDescriptor descriptor, StarxExtensionContext context) {
    }

    /**
     * Called when an extension has been successfully enabled.
     *
     * @param descriptor extension descriptor
     * @param context extension context
     */
    default void onExtensionEnabled(StarxExtensionDescriptor descriptor, StarxExtensionContext context) {
    }

    /**
     * Called when an extension is about to be disabled.
     *
     * @param descriptor extension descriptor
     * @param context extension context
     */
    default void onExtensionDisabling(StarxExtensionDescriptor descriptor, StarxExtensionContext context) {
    }

    /**
     * Called when an extension has been successfully disabled.
     *
     * @param descriptor extension descriptor
     * @param context extension context
     */
    default void onExtensionDisabled(StarxExtensionDescriptor descriptor, StarxExtensionContext context) {
    }

    /**
     * Called when an extension is about to be unregistered.
     *
     * @param descriptor extension descriptor
     * @param context extension context
     */
    default void onExtensionUnregistering(StarxExtensionDescriptor descriptor, StarxExtensionContext context) {
    }

    /**
     * Called when an extension has been successfully unregistered.
     *
     * @param descriptor extension descriptor
     */
    default void onExtensionUnregistered(StarxExtensionDescriptor descriptor) {
    }

    /**
     * Called when an extension encounters an error.
     *
     * @param descriptor extension descriptor
     * @param context extension context (may be null if error occurred during registration)
     * @param error the error that occurred
     */
    default void onExtensionError(StarxExtensionDescriptor descriptor, StarxExtensionContext context, Throwable error) {
    }

    /**
     * Called when an extension's health status changes.
     *
     * @param descriptor extension descriptor
     * @param context extension context
     * @param previousHealth previous health status
     * @param currentHealth current health status
     */
    default void onHealthChanged(StarxExtensionDescriptor descriptor, StarxExtensionContext context,
                                  StarxExtensionHealth previousHealth, StarxExtensionHealth currentHealth) {
    }

    /**
     * Called when an extension's configuration changes.
     *
     * @param descriptor extension descriptor
     * @param context extension context
     * @param previousConfig previous configuration
     * @param currentConfig current configuration
     */
    default void onConfigChanged(StarxExtensionDescriptor descriptor, StarxExtensionContext context,
                                  StarxExtensionConfig previousConfig, StarxExtensionConfig currentConfig) {
    }
}
