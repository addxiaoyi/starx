package io.github.addxiaoyi.starx.api.extension;

import io.github.addxiaoyi.starx.api.bridge.PlatformKind;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Runtime context owned by one registered extension. */
public interface StarxExtensionContext {
  /**
   * Returns the documented service value.
   *
   * @return owning StarX service
   */
  StarxService service();

  /**

   * Returns the documented service value.

   *

   * @return immutable extension descriptor

   */
  StarxExtensionDescriptor descriptor();

  /**

   * Returns the documented service value.

   *

   * @return current runtime platform

   */
  PlatformKind platform();

  /**

   * Returns the documented service value.

   *

   * @return immutable runtime capability set

   */
  Set<String> capabilities();

  /**

   * Returns the documented service value.

   *

   * @return logger namespaced to the extension identifier

   */
  System.Logger logger();

  /**
   * Subscribes an extension-owned listener to one event type.
   *
   * @param eventType exact event type or {@link StarxApi#ALL_EVENTS}
   * @param listener event listener
   * @return idempotent subscription handle
   */
  StarxEventSubscription subscribe(String eventType, Consumer<StarxServiceEvent> listener);

  /**
   * Publishes {@code extension.<extension-id>.<eventName>} on the service event stream.
   *
   * @param eventName extension-local event name
   * @param payload immutable event payload values
   */
  void publish(String eventName, Map<String, ?> payload);
}
