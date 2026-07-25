package io.github.addxiaoyi.starx.server;

import io.github.addxiaoyi.starx.api.bridge.BridgeMessage;
import io.github.addxiaoyi.starx.api.bridge.BridgeProtocol;
import io.github.addxiaoyi.starx.api.bridge.PlatformKind;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.Consumer;

public final class BackendBridgeSession {
  private final String nodeId;
  private final ServerPlatform platform;
  private final Supplier<Map<String, String>> statusSupplier;
  private final BackendSkinResolver skinResolver;
  private final Consumer<Boolean> maintenanceConsumer;
  private final Clock clock;
  private volatile Instant lastProxyContact;

  public BackendBridgeSession(
      String nodeId,
      ServerPlatform platform,
      Supplier<Map<String, String>> statusSupplier,
      Clock clock
  ) {
    this(nodeId, platform, statusSupplier, (uuid, name) -> Optional.empty(), ignored -> { }, clock);
  }

  BackendBridgeSession(
      String nodeId,
      ServerPlatform platform,
      Supplier<Map<String, String>> statusSupplier,
      BackendSkinResolver skinResolver,
      Clock clock
  ) {
    this(nodeId, platform, statusSupplier, skinResolver, ignored -> { }, clock);
  }

  BackendBridgeSession(
      String nodeId,
      ServerPlatform platform,
      Supplier<Map<String, String>> statusSupplier,
      BackendSkinResolver skinResolver,
      Consumer<Boolean> maintenanceConsumer,
      Clock clock
  ) {
    if (nodeId == null || nodeId.isBlank()) {
      throw new IllegalArgumentException("Backend node id must not be blank");
    }
    this.nodeId = nodeId;
    this.platform = Objects.requireNonNull(platform, "platform");
    this.statusSupplier = Objects.requireNonNull(statusSupplier, "statusSupplier");
    this.skinResolver = Objects.requireNonNull(skinResolver, "skinResolver");
    this.maintenanceConsumer = Objects.requireNonNull(maintenanceConsumer, "maintenanceConsumer");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public BridgeMessage hello() {
    return new BridgeMessage(
        BridgeProtocol.BACKEND_HELLO,
        this.nodeId,
        this.platform.bridgeKind(),
        "",
        this.fixedAttributes());
  }

  public Optional<BridgeMessage> receive(BridgeMessage message) {
    Objects.requireNonNull(message, "message");
    if (message.platform() != PlatformKind.VELOCITY) {
      return Optional.empty();
    }
    if (!BridgeProtocol.PROXY_HELLO.equals(message.type())
        && !BridgeProtocol.STATUS_REQUEST.equals(message.type())
        && !BridgeProtocol.SKIN_REQUEST.equals(message.type())
        && !BridgeProtocol.CONFIG_SYNC.equals(message.type())) {
      return Optional.empty();
    }

    this.lastProxyContact = this.clock.instant();
    if (BridgeProtocol.PROXY_HELLO.equals(message.type())) {
      return Optional.of(this.hello());
    }
    if (BridgeProtocol.SKIN_REQUEST.equals(message.type())) {
      return Optional.of(this.skinResponse(message));
    }
    if (BridgeProtocol.CONFIG_SYNC.equals(message.type())) {
      this.maintenanceConsumer.accept(parseMaintenance(message));
      return Optional.empty();
    }

    Map<String, String> status = new LinkedHashMap<>();
    Map<String, String> supplied = this.statusSupplier.get();
    if (supplied != null) {
      status.putAll(supplied);
    }
    status.putAll(this.fixedAttributes());
    return Optional.of(BridgeMessage.statusResponse(
        this.nodeId,
        this.platform.bridgeKind(),
        message.correlationId(),
        status));
  }

  private static boolean parseMaintenance(BridgeMessage message) {
    String value = message.attributes().get("maintenance");
    if (!"true".equals(value) && !"false".equals(value)) {
      throw new IllegalArgumentException("Config sync maintenance must be true or false");
    }
    return Boolean.parseBoolean(value);
  }

  private BridgeMessage skinResponse(BridgeMessage request) {
    UUID uuid;
    try {
      uuid = UUID.fromString(request.attributes().getOrDefault("uuid", ""));
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("Skin request contains an invalid UUID", error);
    }
    String name = request.attributes().getOrDefault("name", "").trim();
    if (name.isEmpty()) {
      throw new IllegalArgumentException("Skin request name must not be blank");
    }
    Map<String, String> attributes = new LinkedHashMap<>();
    attributes.put("uuid", uuid.toString());
    attributes.put("name", name);
    Optional<BackendSkinProfile> profile = this.findSkin(uuid, name);
    attributes.put("found", Boolean.toString(profile.isPresent()));
    profile.ifPresent(skin -> {
      attributes.put("provider", skin.provider());
      attributes.put("value", skin.value());
      attributes.put("signature", skin.signature());
    });
    return BridgeMessage.skinResponse(
        this.nodeId, this.platform.bridgeKind(), request.correlationId(), attributes);
  }

  public Optional<Instant> lastProxyContact() {
    return Optional.ofNullable(this.lastProxyContact);
  }

  public String nodeId() {
    return this.nodeId;
  }

  public ServerPlatform platform() {
    return this.platform;
  }

  public Map<String, String> currentStatus() {
    Map<String, String> status = new LinkedHashMap<>();
    Map<String, String> supplied = this.statusSupplier.get();
    if (supplied != null) {
      status.putAll(supplied);
    }
    status.putAll(this.fixedAttributes());
    return Map.copyOf(status);
  }

  public BridgeMessage statusReport(String correlationId) {
    if (correlationId == null || correlationId.isBlank()) {
      throw new IllegalArgumentException("correlationId must not be blank");
    }
    return BridgeMessage.statusResponse(
        this.nodeId,
        this.platform.bridgeKind(),
        correlationId,
        this.currentStatus());
  }

  Optional<BackendSkinProfile> findSkin(UUID uuid, String name) {
    Objects.requireNonNull(uuid, "uuid");
    String playerName = Objects.requireNonNull(name, "name").trim();
    if (playerName.isEmpty()) {
      throw new IllegalArgumentException("Skin request name must not be blank");
    }
    return this.skinResolver.find(uuid, playerName);
  }

  private Map<String, String> fixedAttributes() {
    return Map.of(
        "capabilities", String.join(",", new TreeSet<>(
            ServerCapabilities.forPlatform(this.platform))),
        "execution", this.platform.executionModel());
  }
}
