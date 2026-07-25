package io.github.addxiaoyi.starx.limbo;

import com.velocitypowered.api.proxy.ProxyServer;
import io.github.addxiaoyi.starx.uworld.StarxUworldFactory;
import java.nio.file.Path;
import org.slf4j.Logger;

/** @deprecated Use {@link StarxUworldFactory}. */
@Deprecated(forRemoval = true)
public final class StarxLimboFactory extends StarxUworldFactory {

  public StarxLimboFactory(Logger logger, ProxyServer server, Path dataDirectory) {
    super(logger, server, dataDirectory);
  }
}
