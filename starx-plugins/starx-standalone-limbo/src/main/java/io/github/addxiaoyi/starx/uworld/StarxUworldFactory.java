package io.github.addxiaoyi.starx.uworld;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import io.github.addxiaoyi.starx.limbo.LimboAPI;
import java.nio.file.Path;
import java.util.Objects;
import org.slf4j.Logger;

/** Embedded StarX entry point for the Uworld virtual world runtime. */
public class StarxUworldFactory extends LimboAPI {

  public StarxUworldFactory(Logger logger, ProxyServer server, Path dataDirectory) {
    super(logger, server, dataDirectory);
  }

  public void execute(Player player, Runnable action) {
    Objects.requireNonNull(player, "player");
    Objects.requireNonNull(action, "action");
    if (!(player instanceof ConnectedPlayer connected)) {
      throw new IllegalArgumentException("Player is not a Velocity connected player");
    }
    var eventLoop = connected.getConnection().eventLoop();
    if (eventLoop.inEventLoop()) {
      action.run();
    } else {
      eventLoop.execute(action);
    }
  }
}
