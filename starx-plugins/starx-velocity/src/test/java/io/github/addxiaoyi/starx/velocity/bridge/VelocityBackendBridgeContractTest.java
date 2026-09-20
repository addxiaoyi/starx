package io.github.addxiaoyi.starx.velocity.bridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.messages.ChannelMessageSource;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import io.github.addxiaoyi.starx.api.bridge.BridgeMessage;
import io.github.addxiaoyi.starx.api.bridge.BridgeProtocol;
import io.github.addxiaoyi.starx.api.bridge.PlatformKind;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import java.lang.reflect.Proxy;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

final class VelocityBackendBridgeContractTest {

  @Test
  void trustsOnlyBackendServerConnections() {
    ChannelMessageSource playerLikeSource = new ChannelMessageSource() {
    };
    ServerConnection serverConnection = (ServerConnection) Proxy.newProxyInstance(
        getClass().getClassLoader(),
        new Class<?>[] {ServerConnection.class},
        (proxy, method, args) -> defaultValue(method.getReturnType()));

    assertFalse(VelocityBackendBridge.isTrustedSource(playerLikeSource));
    assertTrue(VelocityBackendBridge.isTrustedSource(serverConnection));
  }

  @Test
  void requestsStatusAfterTheBackendRegistersItsChannel() {
    BridgeMessage hello = new BridgeMessage(
        BridgeProtocol.BACKEND_HELLO,
        "backend",
        PlatformKind.PAPER,
        "",
        Map.of("capabilities", "bridge.v1"));

    BridgeMessage followUp = VelocityBackendBridge.followUpFor(
        hello,
        "request-9").orElseThrow();

    assertEquals(BridgeProtocol.STATUS_REQUEST, followUp.type());
    assertEquals("request-9", followUp.correlationId());
    assertEquals(PlatformKind.VELOCITY, followUp.platform());
  }

  @Test
  void doesNotLoopAfterAStatusResponse() {
    BridgeMessage status = BridgeMessage.statusResponse(
        "backend",
        PlatformKind.PAPER,
        "request-9",
        Map.of("online", "1"));

    assertTrue(VelocityBackendBridge.followUpFor(status, "request-10").isEmpty());
  }

  @Test
  void playerCarriedUpdatesReplaceTheEmptyServerTransportLabel() {
    BridgeMessage heartbeat = BridgeMessage.statusResponse(
        "backend",
        PlatformKind.PAPER,
        "request-9",
        Map.of("online", "1", "transport", "heartbeat-http"));

    BridgeMessage carried = VelocityBackendBridge.markTransport(
        heartbeat, "player-carrier");

    assertEquals("player-carrier", carried.attributes().get("transport"));
    assertEquals("1", carried.attributes().get("online"));
  }

  @Test
  void dispatchesSkinRequestThroughPlayersCurrentServer() {
    ServerConnection connection = (ServerConnection) Proxy.newProxyInstance(
        getClass().getClassLoader(),
        new Class<?>[] {ServerConnection.class},
        (proxy, method, args) -> method.getName().equals("sendPluginMessage")
            ? true
            : defaultValue(method.getReturnType()));
    Player player = (Player) Proxy.newProxyInstance(
        getClass().getClassLoader(),
        new Class<?>[] {Player.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getCurrentServer" -> Optional.of(connection);
          case "getUniqueId" -> UUID.fromString("4f06bce0-32d7-4d4d-bb17-9f7e92ae8701");
          case "getUsername" -> "Alex";
          default -> defaultValue(method.getReturnType());
        });

    VelocityBackendBridge.DispatchResult result = VelocityBackendBridge.dispatchSkinRequest(
        player, MinecraftChannelIdentifier.from(BridgeProtocol.CHANNEL), "skin-1");

    assertEquals(VelocityBackendBridge.DispatchResult.SENT, result);
    assertTrue(result.accepted());
  }

  @Test
  void dispatchesSkinRequestThroughTheServerFromTheConnectedEvent() {
    RegisteredServer connectedServer = registeredServer(true);
    Player player = (Player) Proxy.newProxyInstance(
        getClass().getClassLoader(),
        new Class<?>[] {Player.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getCurrentServer" -> Optional.empty();
          case "getUniqueId" -> UUID.fromString("4f06bce0-32d7-4d4d-bb17-9f7e92ae8701");
          case "getUsername" -> "Alex";
          default -> defaultValue(method.getReturnType());
        });

    VelocityBackendBridge.DispatchResult result = VelocityBackendBridge.dispatchSkinRequest(
        player,
        connectedServer,
        MinecraftChannelIdentifier.from(BridgeProtocol.CHANNEL),
        "skin-connected-1",
        new BackendCommandMailbox(4));

    assertEquals(VelocityBackendBridge.DispatchResult.SENT, result);
    assertTrue(result.accepted());
  }

  @Test
  void notifiesConsumersWhenTheBackendChannelBecomesReady() throws Exception {
    com.velocitypowered.api.proxy.ProxyServer proxy = (com.velocitypowered.api.proxy.ProxyServer)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {com.velocitypowered.api.proxy.ProxyServer.class},
            (ignored, method, args) -> defaultValue(method.getReturnType()));
    StarxVelocityPlugin plugin = new StarxVelocityPlugin(
        proxy, Logger.getLogger("skin-bridge-test"), Path.of("build", "skin-bridge-test"));
    VelocityBackendBridge bridge = new VelocityBackendBridge(plugin);
    AtomicReference<Player> readyPlayer = new AtomicReference<>();
    AtomicReference<RegisteredServer> readyServer = new AtomicReference<>();
    bridge.onBackendReady((player, server) -> {
      readyPlayer.set(player);
      readyServer.set(server);
    });

    Player player = (Player) Proxy.newProxyInstance(
        getClass().getClassLoader(),
        new Class<?>[] {Player.class},
        (ignored, method, args) -> switch (method.getName()) {
          case "getUniqueId" -> UUID.fromString("4f06bce0-32d7-4d4d-bb17-9f7e92ae8701");
          case "getUsername" -> "Alex";
          default -> defaultValue(method.getReturnType());
        });
    ServerInfo serverInfo = new ServerInfo(
        "factions", new InetSocketAddress("127.0.0.1", 25565));
    RegisteredServer server = (RegisteredServer) Proxy.newProxyInstance(
        getClass().getClassLoader(),
        new Class<?>[] {RegisteredServer.class},
        (ignored, method, args) -> switch (method.getName()) {
          case "getServerInfo" -> serverInfo;
          case "sendPluginMessage" -> true;
          default -> defaultValue(method.getReturnType());
        });
    ServerConnection connection = (ServerConnection) Proxy.newProxyInstance(
        getClass().getClassLoader(),
        new Class<?>[] {ServerConnection.class},
        (ignored, method, args) -> switch (method.getName()) {
          case "getPlayer" -> player;
          case "getServer" -> server;
          case "getServerInfo" -> serverInfo;
          case "sendPluginMessage" -> true;
          default -> defaultValue(method.getReturnType());
        });
    BridgeMessage hello = BridgeMessage.hello("backend", PlatformKind.PAPER);
    PluginMessageEvent event = new PluginMessageEvent(
        connection,
        player,
        MinecraftChannelIdentifier.from(BridgeProtocol.CHANNEL),
        BridgeProtocol.encode(hello));
    Method handler = VelocityBackendBridge.class.getDeclaredMethod(
        "onPluginMessage", PluginMessageEvent.class);
    handler.setAccessible(true);
    handler.invoke(bridge, event);

    assertSame(player, readyPlayer.get());
    assertSame(server, readyServer.get());
  }

  @Test
  void queuesSkinRequestForHeartbeatWhenThePlayerCarrierRejectsIt() {
    ServerInfo serverInfo = new ServerInfo(
        "factions", new InetSocketAddress("127.0.0.1", 25565));
    ServerConnection connection = (ServerConnection) Proxy.newProxyInstance(
        getClass().getClassLoader(),
        new Class<?>[] {ServerConnection.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "sendPluginMessage" -> false;
          case "getServerInfo" -> serverInfo;
          default -> defaultValue(method.getReturnType());
        });
    Player player = (Player) Proxy.newProxyInstance(
        getClass().getClassLoader(),
        new Class<?>[] {Player.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getCurrentServer" -> Optional.of(connection);
          case "getUniqueId" -> UUID.fromString("4f06bce0-32d7-4d4d-bb17-9f7e92ae8701");
          case "getUsername" -> "Alex";
          default -> defaultValue(method.getReturnType());
        });
    BackendCommandMailbox mailbox = new BackendCommandMailbox(4);

    VelocityBackendBridge.DispatchResult result = VelocityBackendBridge.dispatchSkinRequest(
        player,
        MinecraftChannelIdentifier.from(BridgeProtocol.CHANNEL),
        "skin-http-1",
        mailbox);

    assertEquals(VelocityBackendBridge.DispatchResult.QUEUED_HTTP, result);
    assertTrue(result.accepted());
    BridgeMessage queued = mailbox.poll("factions").orElseThrow();
    assertEquals(BridgeProtocol.SKIN_REQUEST, queued.type());
    assertEquals("skin-http-1", queued.correlationId());
  }

  @Test
  void queuesOfflinePlayerSkinRequestWithoutAPlayerCarrier() {
    RegisteredServer server = registeredServer(false);
    BackendCommandMailbox mailbox = new BackendCommandMailbox(4);
    UUID uuid = UUID.fromString("a77946af-36d5-3cf0-beb8-5b784f8498ed");

    VelocityBackendBridge.DispatchResult result = VelocityBackendBridge.dispatchSkinRequest(
        uuid,
        "SkinProbe",
        server,
        MinecraftChannelIdentifier.from(BridgeProtocol.CHANNEL),
        "skin-offline-1",
        mailbox);

    assertEquals(VelocityBackendBridge.DispatchResult.QUEUED_HTTP, result);
    BridgeMessage queued = mailbox.poll("factions").orElseThrow();
    assertEquals(BridgeProtocol.SKIN_REQUEST, queued.type());
    assertEquals(uuid.toString(), queued.attributes().get("uuid"));
    assertEquals("SkinProbe", queued.attributes().get("name"));
  }

  @Test
  void periodicRefreshCountsServersWithAvailableCarriers() {
    RegisteredServer available = registeredServer(true);
    RegisteredServer unavailable = registeredServer(false);

    int sent = VelocityBackendBridge.refreshStatuses(
        List.of(available, unavailable),
        MinecraftChannelIdentifier.from(BridgeProtocol.CHANNEL),
        () -> "status-1");

    assertEquals(1, sent);
  }

  @Test
  void queuesPersistentSkinUpdateForBackendWithoutAPlayerCarrier() {
    BackendCommandMailbox mailbox = new BackendCommandMailbox(4);
    UUID uuid = UUID.fromString("a77946af-36d5-3cf0-beb8-5b784f8498ed");

    VelocityBackendBridge.DispatchResult result = VelocityBackendBridge.dispatchSkinUpdate(
        registeredServer(false),
        MinecraftChannelIdentifier.from(BridgeProtocol.CHANNEL),
        "skin-update-1",
        uuid,
        "SkinProbe",
        "encoded-texture",
        "",
        mailbox);

    assertEquals(VelocityBackendBridge.DispatchResult.QUEUED_HTTP, result);
    BridgeMessage queued = mailbox.poll("factions").orElseThrow();
    assertEquals(BridgeProtocol.SKIN_UPDATE, queued.type());
    assertEquals(uuid.toString(), queued.attributes().get("uuid"));
    assertEquals("encoded-texture", queued.attributes().get("value"));
  }

  @Test
  void queuesMaintenanceForEachBackendWithoutAPlayerCarrier() {
    BackendCommandMailbox mailbox = new BackendCommandMailbox(4);

    VelocityBackendBridge.DispatchResult result = VelocityBackendBridge.dispatchMaintenance(
        registeredServer(false),
        MinecraftChannelIdentifier.from(BridgeProtocol.CHANNEL),
        "maint-7",
        true,
        mailbox);

    assertEquals(VelocityBackendBridge.DispatchResult.QUEUED_HTTP, result);
    BridgeMessage queued = mailbox.poll("factions").orElseThrow();
    assertEquals(BridgeProtocol.CONFIG_SYNC, queued.type());
    assertEquals("true", queued.attributes().get("maintenance"));
  }

  private RegisteredServer registeredServer(boolean sends) {
    ServerInfo serverInfo = new ServerInfo(
        "factions", new InetSocketAddress("127.0.0.1", 25565));
    return (RegisteredServer) Proxy.newProxyInstance(
        getClass().getClassLoader(),
        new Class<?>[] {RegisteredServer.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "sendPluginMessage" -> sends;
          case "getServerInfo" -> serverInfo;
          default -> defaultValue(method.getReturnType());
        });
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive()) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == char.class) {
      return '\0';
    }
    return 0;
  }
}
