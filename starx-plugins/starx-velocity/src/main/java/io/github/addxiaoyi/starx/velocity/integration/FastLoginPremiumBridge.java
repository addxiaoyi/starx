package io.github.addxiaoyi.starx.velocity.integration;

import com.velocitypowered.api.proxy.Player;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Reads FastLogin's verified session without a compile-time optional dependency. */
public final class FastLoginPremiumBridge {
  private final Object proxy;
  private final Logger logger;

  public FastLoginPremiumBridge(Object proxy, Logger logger) {
    this.proxy = proxy;
    this.logger = logger;
  }

  public boolean isVerified(Player player) {
    try {
      Object pluginManager = invoke(this.proxy, "getPluginManager");
      Optional<?> container = (Optional<?>) invoke(pluginManager, "getPlugin", "fastlogin");
      if (container.isEmpty()) return false;
      Object fastLogin = invoke(container.get(), "getInstance");
      Object sessions = invoke(fastLogin, "getSession");
      if (!(sessions instanceof Map<?, ?> map)) return false;
      InetSocketAddress address = player.getRemoteAddress();
      Object session = map.get(address);
      if (session == null) return false;
      Object profile = invoke(session, "getProfile");
      boolean premium = (boolean) invoke(profile, "isPremium");
      UUID verifiedUuid = (UUID) invoke(profile, "getId");
      String verifiedName = (String) invoke(profile, "getName");
      return premium && player.getUniqueId().equals(verifiedUuid)
          && player.getUsername().equalsIgnoreCase(verifiedName);
    } catch (ReflectiveOperationException | ClassCastException error) {
      logger.log(Level.FINE, "FastLogin premium state unavailable; keeping password authentication", error);
      return false;
    }
  }

  private static Object invoke(Object target, String name, Object... args)
      throws ReflectiveOperationException {
    for (Method method : target.getClass().getMethods()) {
      if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
      return method.invoke(target, args);
    }
    throw new NoSuchMethodException(target.getClass().getName() + '#' + name);
  }
}
