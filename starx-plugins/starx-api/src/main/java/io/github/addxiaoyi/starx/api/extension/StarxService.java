package io.github.addxiaoyi.starx.api.extension;

import io.github.addxiaoyi.starx.api.bridge.PlatformKind;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/** Stable service contract exposed by StarX on Velocity, Paper, and Folia. */
public interface StarxService {
  /**
   * Returns the documented service value.
   *
   * @return public API version implemented by this service
   */
  ApiVersion apiVersion();

  /**

   * Returns the documented service value.

   *

   * @return StarX plugin implementation version

   */
  String implementationVersion();

  /**

   * Returns the documented service value.

   *

   * @return platform exposing this service

   */
  PlatformKind platform();

  /**

   * Returns the documented service value.

   *

   * @return immutable capability identifiers available at startup

   */
  Set<String> capabilities();

  /**
   * Validates and enables one extension.
   *
   * @param descriptor identity and compatibility declaration
   * @param extension lifecycle implementation
   * @return registration handle after successful enable
   * @throws IllegalArgumentException if version, capability, or identifier checks fail
   * @throws IllegalStateException if the extension enable callback fails
   */
  StarxExtensionRegistration registerExtension(
      StarxExtensionDescriptor descriptor,
      StarxExtension extension);

  /**
   * Finds one active extension.
   *
   * @param id extension identifier
   * @return active extension snapshot when present
   */
  Optional<StarxExtensionSnapshot> extension(String id);

  /**

   * Returns the documented service value.

   *

   * @return active extension snapshots sorted by identifier

   */
  List<StarxExtensionSnapshot> extensions();

  /**
   * Subscribes to an exact event type or {@link StarxApi#ALL_EVENTS}. Callbacks run on the
   * publisher's thread and therefore must not assume a Velocity, Bukkit, or Folia game thread.
   *
   * @param eventType exact event type or {@link StarxApi#ALL_EVENTS}
   * @param listener event listener
   * @return idempotent subscription handle
   */
  StarxEventSubscription subscribe(String eventType, Consumer<StarxServiceEvent> listener);
}
