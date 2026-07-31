/*
 * Copyright (C) 2025 StarX Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package io.github.addxiaoyi.starx.velocity.config;

import io.github.addxiaoyi.starx.common.auth.uniauth.UniAuthConfig;
import io.github.addxiaoyi.starx.common.config.DatabaseConfig;
import io.github.addxiaoyi.starx.website.WebsiteSyncConfig;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.yaml.snakeyaml.Yaml;

public final class ConfigLoader {

  private static final String DEFAULT_CONFIG_RESOURCE = "/default-config.yml";
  private static final String BOTH_ROOTS_WARNING =
      "Both uworld and legacy limbo configuration are present; uworld takes precedence";
  private static final String LEGACY_WARNING =
      "Legacy limbo configuration is deprecated; migrate to uworld";

  private ConfigLoader() {
  }

  public static StarxConfig load(Path path) throws IOException {
    return load(path, ignored -> { });
  }

  public static StarxConfig load(Path path, Consumer<String> warningSink) throws IOException {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(warningSink, "warningSink");
    ensureConfigExists(path);

    Map<String, Object> currentRoot = readRoot(path);
    Map<String, Object> sourceModules = child(currentRoot, "modules");
    boolean sourceHasUworldRoot = currentRoot.containsKey("uworld");
    boolean sourceHasLegacyRoot = currentRoot.containsKey("limbo");
    boolean sourceHasLegacyModule = sourceModules.containsKey("starx.limbo");

    Map<String, Object> defaultRoot = readDefaultRoot();
    Map<String, Object> root = ConfigSchemaUpgrader.upgrade(
        path, currentRoot, defaultRoot, warningSink).root();
    Map<String, Object> modulesNode = child(root, "modules");
    Map<String, StarxConfig.ModuleConfig> modules = parseModules(modulesNode);
    validateExclusiveModules(modules);

    warnForMigration(
        sourceHasUworldRoot,
        sourceHasLegacyRoot,
        sourceHasLegacyModule,
        warningSink);

    UworldConfig uworld = parseUworldConfig(child(root, "uworld"));

    String apiKey = stringValue(root, "api-key", "");
    Map<String, Object> httpNode = child(root, "http");
    int httpPort = integer(httpNode, "port", 8788);
    StarxConfig.HttpConfig http = new StarxConfig.HttpConfig(
        stringValue(httpNode, "bind", "127.0.0.1"),
        httpPort,
        stringValue(httpNode, "frp-public-url", ""),
        stringValue(httpNode, "port-conflict-policy", "persist"),
        integer(httpNode, "fallback-range-start", httpPort),
        integer(httpNode, "fallback-range-end", Math.min(65_535, httpPort + 100)));
    Map<String, Object> webhookNode = child(root, "webhook");
    StarxConfig.WebhookConfig webhook = new StarxConfig.WebhookConfig(
        stringValue(webhookNode, "url", ""),
        stringValue(webhookNode, "secret", ""));
    DatabaseConfig database = parseDatabaseConfig(child(root, "database"));
    UniAuthConfig uniauth = parseUniAuthConfig(child(root, "uniauth"));
    StarxConfig.NapcatConfig napcat = parseNapcatConfig(child(root, "napcat"));
    StarxConfig.TotpConfig totp = parseTotpConfig(child(root, "totp"));
    StarxConfig.AuthConfig auth = parseAuthConfig(child(root, "auth"));
    StarxConfig.PlayerListConfig playerList = parsePlayerListConfig(child(root, "player-list"));
    WebsiteSyncConfig websiteSync = VelocityWebsiteSyncConfigParser.parse(
        child(root, "website-sync"));
    NetworkAutomationConfig networkAutomation = parseNetworkAutomationConfig(
        child(root, "network-automation"));

    return new StarxConfig(
        apiKey,
        http,
        webhook,
        database,
        uniauth,
        napcat,
        totp,
        uworld,
        auth,
        playerList,
        websiteSync,
        networkAutomation,
        modules);
  }

  private static void ensureConfigExists(Path path) throws IOException {
    if (Files.exists(path)) {
      return;
    }
    Path parent = path.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    try (InputStream input = ConfigLoader.class.getResourceAsStream(DEFAULT_CONFIG_RESOURCE)) {
      if (input == null) {
        throw new IOException("Missing classpath resource " + DEFAULT_CONFIG_RESOURCE);
      }
      Files.writeString(
          path,
          new String(input.readAllBytes(), StandardCharsets.UTF_8),
          StandardCharsets.UTF_8);
    }
  }

  private static Map<String, Object> readRoot(Path path) throws IOException {
    try (InputStream input = Files.newInputStream(path)) {
      return readRoot(input, path.toString());
    }
  }

  private static Map<String, Object> readDefaultRoot() throws IOException {
    try (InputStream input = ConfigLoader.class.getResourceAsStream(DEFAULT_CONFIG_RESOURCE)) {
      if (input == null) {
        throw new IOException("Missing classpath resource " + DEFAULT_CONFIG_RESOURCE);
      }
      return readRoot(input, DEFAULT_CONFIG_RESOURCE);
    }
  }

  private static Map<String, Object> readRoot(InputStream input, String label) {
    Object loaded = new Yaml().load(input);
    if (loaded == null) {
      return Map.of();
    }
    if (!(loaded instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("Configuration root must be a mapping: " + label);
    }
    return stringKeyMap(map, "configuration root");
  }

  private static Map<String, StarxConfig.ModuleConfig> parseModules(
      Map<String, Object> modulesNode
  ) {
    Map<String, StarxConfig.ModuleConfig> modules = new HashMap<>();
    for (Map.Entry<String, Object> entry : modulesNode.entrySet()) {
      boolean enabled = false;
      Map<String, Object> options = Map.of();
      if (entry.getValue() instanceof Map<?, ?> map) {
        Map<String, Object> module = stringKeyMap(map, "module " + entry.getKey());
        options = module;
        enabled = isUworldModule(entry.getKey())
            ? configBoolean(
                module,
                "enabled",
                false,
                "modules." + entry.getKey() + ".enabled")
            : booleanValue(module, "enabled", false);
      }
      modules.put(entry.getKey(), new StarxConfig.ModuleConfig(enabled, options));
    }
    return modules;
  }

  private static void validateExclusiveModules(
      Map<String, StarxConfig.ModuleConfig> modules
  ) {
    boolean basicQueue = enabled(modules, "starx.queue");
    boolean smartQueue = enabled(modules, "starx.proxytools.smart-queue");
    if (basicQueue && smartQueue) {
      throw new IllegalArgumentException(
          "modules.starx.queue and modules.starx.proxytools.smart-queue are mutually exclusive");
    }
  }

  private static boolean enabled(
      Map<String, StarxConfig.ModuleConfig> modules,
      String name
  ) {
    StarxConfig.ModuleConfig module = modules.get(name);
    return module != null && module.enabled();
  }

  private static UworldConfig parseUworldConfig(Map<String, Object> node) {
    Map<String, Object> authNode = child(node, "auth");
    Map<String, Object> worldNode = child(authNode, "world");
    Map<String, Object> diagnosticsNode = child(node, "diagnostics");

    UworldConfig.World world = new UworldConfig.World(
        stringValue(worldNode, "dimension", "OVERWORLD"),
        decimal(worldNode, "spawn-x", 0.5),
        decimal(worldNode, "spawn-y", 100.0),
        decimal(worldNode, "spawn-z", 0.5),
        (float) decimal(worldNode, "spawn-yaw", 0.0),
        (float) decimal(worldNode, "spawn-pitch", 0.0),
        stringValue(worldNode, "game-mode", "SURVIVAL"),
        stringValue(worldNode, "loader-type", "AUTO"),
        stringValue(worldNode, "file-name", "auth_world.schem"),
        configInteger(worldNode, "offset-x", 0, "uworld.auth.world.offset-x"),
        configInteger(worldNode, "offset-y", 0, "uworld.auth.world.offset-y"),
        configInteger(worldNode, "offset-z", 0, "uworld.auth.world.offset-z"),
        configInteger(worldNode, "view-distance", 4, "uworld.auth.world.view-distance"),
        configInteger(
            worldNode,
            "simulation-distance",
            4,
            "uworld.auth.world.simulation-distance"),
        aliasInteger(
            worldNode,
            "platform-radius",
            "platform-size",
            5,
            "uworld.auth.world"));
    UworldConfig.Auth auth = new UworldConfig.Auth(
        configInteger(authNode, "timeout-seconds", 300, "uworld.auth.timeout-seconds"),
        stringValue(authNode, "target-server", "lobby"),
        world);
    UworldConfig.Diagnostics diagnostics = new UworldConfig.Diagnostics(
        configBoolean(
            diagnosticsNode,
            "enabled",
            false,
            "uworld.diagnostics.enabled"),
        configInteger(
            diagnosticsNode,
            "timeout-seconds",
            120,
            "uworld.diagnostics.timeout-seconds"),
        configInteger(
            diagnosticsNode,
            "platform-radius",
            5,
            "uworld.diagnostics.platform-radius"));
    return new UworldConfig(
        configBoolean(node, "enabled", true, "uworld.enabled"),
        configInteger(
            node,
            "transfer-timeout-seconds",
            15,
            "uworld.transfer-timeout-seconds"),
        auth,
        diagnostics);
  }

  private static void warnForMigration(
      boolean hasUworldRoot,
      boolean hasLegacyRoot,
      boolean hasLegacyModule,
      Consumer<String> warningSink
  ) {
    if (hasUworldRoot && hasLegacyRoot) {
      warningSink.accept(BOTH_ROOTS_WARNING);
      return;
    }
    if (hasLegacyRoot || hasLegacyModule) {
      warningSink.accept(LEGACY_WARNING);
    }
  }

  private static Map<String, Object> child(Map<String, Object> parent, String key) {
    Object value = parent.get(key);
    if (value == null) {
      return Map.of();
    }
    if (!(value instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException(key + " must be a mapping");
    }
    return stringKeyMap(map, key);
  }

  private static Map<String, Object> stringKeyMap(Map<?, ?> map, String label) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw new IllegalArgumentException(label + " contains a non-string key");
      }
      values.put(key, entry.getValue());
    }
    return values;
  }

  private static String stringValue(Map<String, Object> map, String key, String fallback) {
    Object value = map.get(key);
    return value == null ? fallback : value.toString();
  }

  private static NetworkAutomationConfig parseNetworkAutomationConfig(
      Map<String, Object> node
  ) {
    NetworkAutomationConfig defaults = NetworkAutomationConfig.defaults();
    Map<String, Object> publicNode = child(node, "public-address");
    Map<String, Object> frpNode = child(node, "frp");
    Map<String, Object> certificateNode = child(node, "certificate");
    NetworkAutomationConfig.PublicAddress publicAddress = new NetworkAutomationConfig.PublicAddress(
        booleanValue(publicNode, "enabled", defaults.publicAddress().enabled()),
        integer(publicNode, "minimum-agreement", defaults.publicAddress().minimumAgreement()),
        integer(publicNode, "timeout-ms", defaults.publicAddress().timeoutMs()),
        stringList(publicNode, "endpoints", defaults.publicAddress().endpoints(),
            "network-automation.public-address.endpoints"));
    NetworkAutomationConfig.Frp frp = new NetworkAutomationConfig.Frp(
        NetworkAutomationConfig.Frp.Mode.parse(stringValue(frpNode, "mode", "detect")),
        stringValue(frpNode, "public-host", ""),
        stringValue(frpNode, "public-scheme", "http"),
        stringValue(frpNode, "public-url", ""),
        stringValue(frpNode, "proxy-name", "starx-api"),
        stringValue(frpNode, "local-address", "127.0.0.1"),
        integer(frpNode, "local-port", 8788),
        integer(frpNode, "remote-port", 0),
        stringValue(frpNode, "frpc-command", "frpc"),
        stringValue(frpNode, "main-config-file", ""),
        stringValue(frpNode, "managed-config-file", "frp/starx-api.toml"),
        booleanValue(frpNode, "auto-apply", false));
    NetworkAutomationConfig.Certificate certificate = new NetworkAutomationConfig.Certificate(
        booleanValue(certificateNode, "enabled", false),
        stringValue(certificateNode, "domain", ""),
        stringValue(certificateNode, "email", ""),
        NetworkAutomationConfig.Certificate.Client.parse(
            stringValue(certificateNode, "client", "auto")),
        NetworkAutomationConfig.Certificate.Challenge.parse(
            stringValue(certificateNode, "challenge", "http-01")),
        booleanValue(certificateNode, "staging-first", true),
        booleanValue(certificateNode, "auto-run", false),
        integer(certificateNode, "http01-local-port", 8789),
        booleanValue(certificateNode, "http01-public-route-confirmed", false),
        integer(certificateNode, "renew-before-days", 30));
    return new NetworkAutomationConfig(
        booleanValue(node, "enabled", defaults.enabled()),
        stringValue(node, "report-file", defaults.reportFile()),
        publicAddress,
        frp,
        certificate);
  }

  private static List<String> stringList(
      Map<String, Object> node,
      String key,
      List<String> fallback,
      String fullKey
  ) {
    Object value = node.get(key);
    if (value == null) {
      return fallback;
    }
    if (!(value instanceof List<?> list)) {
      throw new IllegalArgumentException(fullKey + " must be a list");
    }
    return list.stream().map(item -> {
      if (item == null) {
        throw new IllegalArgumentException(fullKey + " contains null");
      }
      return item.toString();
    }).toList();
  }

  private static int aliasInteger(
      Map<String, Object> map,
      String preferredKey,
      String legacyKey,
      int fallback,
      String keyPrefix
  ) {
    if (map.containsKey(preferredKey)) {
      return configInteger(map, preferredKey, fallback, keyPrefix + "." + preferredKey);
    }
    return configInteger(map, legacyKey, fallback, keyPrefix + "." + legacyKey);
  }

  private static int configInteger(
      Map<String, Object> map,
      String key,
      int fallback,
      String fullKey
  ) {
    if (!map.containsKey(key)) {
      return fallback;
    }
    Object value = map.get(key);
    if (value == null) {
      throw new IllegalArgumentException(fullKey + " must be an integer");
    }
    if (value instanceof Number number) {
      try {
        return new BigDecimal(number.toString()).intValueExact();
      } catch (ArithmeticException | NumberFormatException error) {
        throw new IllegalArgumentException(fullKey + " must be an integer", error);
      }
    }
    try {
      return Integer.parseInt(value.toString());
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(fullKey + " must be an integer", error);
    }
  }

  private static boolean configBoolean(
      Map<String, Object> map,
      String key,
      boolean fallback,
      String fullKey
  ) {
    if (!map.containsKey(key)) {
      return fallback;
    }
    Object value = map.get(key);
    if (value instanceof Boolean flag) {
      return flag;
    }
    throw new IllegalArgumentException(fullKey + " must be a boolean");
  }

  private static boolean isUworldModule(String moduleId) {
    return "starx.uworld".equals(moduleId) || "starx.limbo".equals(moduleId);
  }

  private static double decimal(Map<String, Object> map, String key, double fallback) {
    Object value = map.get(key);
    if (value == null) {
      return fallback;
    }
    if (value instanceof Number number) {
      return number.doubleValue();
    }
    try {
      return Double.parseDouble(value.toString());
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(key + " must be numeric", error);
    }
  }

  private static int integer(Map<String, Object> map, String key, int fallback) {
    Object value = map.get(key);
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value != null) {
      try {
        return Integer.parseInt(value.toString());
      } catch (NumberFormatException ignored) {
        return fallback;
      }
    }
    return fallback;
  }

  private static long longValue(Map<String, Object> map, String key, long fallback) {
    Object value = map.get(key);
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value != null) {
      try {
        return Long.parseLong(value.toString());
      } catch (NumberFormatException ignored) {
        return fallback;
      }
    }
    return fallback;
  }

  private static boolean booleanValue(
      Map<String, Object> map,
      String key,
      boolean fallback
  ) {
    Object value = map.get(key);
    return value instanceof Boolean flag ? flag : fallback;
  }

  private static UniAuthConfig parseUniAuthConfig(Map<String, Object> node) {
    Map<String, Object> profileSync = child(node, "profile-sync");
    return new UniAuthConfig(
        booleanValue(node, "enabled", false),
        stringValue(node, "api-url", "https://api.example.com/uniauth/"),
        stringValue(node, "api-key", ""),
        integer(node, "timeout-ms", 5000),
        booleanValue(node, "bridge-mode", false),
        new UniAuthConfig.ProfileSyncConfig(
            booleanValue(profileSync, "enabled", false),
            booleanValue(profileSync, "on-login", true),
            booleanValue(profileSync, "sync-email", true),
            booleanValue(profileSync, "sync-external-user-id", true),
            booleanValue(profileSync, "overwrite-local-values", false),
            stringValue(profileSync, "source-system", "uniauth")));
  }

  private static StarxConfig.TotpConfig parseTotpConfig(Map<String, Object> node) {
    return new StarxConfig.TotpConfig(booleanValue(node, "enabled", true));
  }

  private static StarxConfig.AuthConfig parseAuthConfig(Map<String, Object> node) {
    Map<String, Object> uxNode = child(node, "ux");
    Map<String, Object> messagesNode = child(uxNode, "messages");
    Map<String, Object> cardNode = child(uxNode, "card");
    Map<String, Object> offlineIdentityNode = child(node, "offline-identity");
    StarxConfig.AuthUxMessages messages = new StarxConfig.AuthUxMessages(
        stringValue(messagesNode, "login-title", null),
        stringValue(messagesNode, "login-subtitle", null),
        stringValue(messagesNode, "register-title", null),
        stringValue(messagesNode, "register-subtitle", null),
        stringValue(messagesNode, "totp-title", null),
        stringValue(messagesNode, "totp-subtitle", null),
        stringValue(messagesNode, "success-title", null),
        stringValue(messagesNode, "success-subtitle", null),
        stringValue(messagesNode, "login-prompt", null),
        stringValue(messagesNode, "login-action-bar", null),
        stringValue(messagesNode, "register-prompt", null),
        stringValue(messagesNode, "register-action-bar", null),
        stringValue(messagesNode, "totp-prompt", null),
        stringValue(messagesNode, "totp-action-bar", null));
    StarxConfig.AuthCardMessages card = new StarxConfig.AuthCardMessages(
        stringValue(cardNode, "title", null),
        stringValue(cardNode, "player-prefix", null),
        stringValue(cardNode, "uuid-prefix", null),
        stringValue(cardNode, "account-type-prefix", null),
        stringValue(cardNode, "current-ip-prefix", null),
        stringValue(cardNode, "last-ip-prefix", null),
        stringValue(cardNode, "last-login-prefix", null),
        stringValue(cardNode, "playtime-prefix", null),
        stringValue(cardNode, "registered-at-prefix", null),
        stringValue(cardNode, "target-prefix", null),
        stringValue(cardNode, "premium-account", null),
        stringValue(cardNode, "offline-account", null),
        stringValue(cardNode, "first-login-account", null),
        stringValue(cardNode, "new-player-name", null),
        stringValue(cardNode, "no-history", null),
        stringValue(cardNode, "registration-premium-account", null),
        stringValue(cardNode, "registration-offline-account", null),
        stringValue(cardNode, "registration-history", null),
        stringValue(cardNode, "registration-pending-time", null),
        stringValue(cardNode, "unknown-value", null),
        stringValue(cardNode, "target-unavailable", null),
        stringValue(cardNode, "login-link-text", null),
        stringValue(cardNode, "login-link-hover", null),
        stringValue(cardNode, "registration-link-text", null),
        stringValue(cardNode, "registration-link-hover", null),
        stringValue(cardNode, "hour-unit", null),
        stringValue(cardNode, "minute-unit", null));
    StarxConfig.AuthUxConfig ux = new StarxConfig.AuthUxConfig(
        configBoolean(uxNode, "titles-enabled", true, "auth.ux.titles-enabled"),
        configBoolean(uxNode, "action-bar-enabled", true, "auth.ux.action-bar-enabled"),
        configBoolean(uxNode, "sounds-enabled", true, "auth.ux.sounds-enabled"),
        stringValue(uxNode, "prompt-sound", null),
        stringValue(uxNode, "success-sound", null),
        stringValue(uxNode, "error-sound", null),
        messages,
        card);
    return new StarxConfig.AuthConfig(
        configBoolean(
            node,
            "allow-offline-default",
            false,
            "auth.allow-offline-default"),
        ux,
        new StarxConfig.OfflineIdentityConfig(
            stringValue(offlineIdentityNode, "prefix", "."),
            stringValue(offlineIdentityNode, "display-name", "前缀离线账号")),
        integer(node, "password-bypass-minutes", 30),
        stringValue(node, "binding-website-url", "https://star-web.top"));
  }

  private static StarxConfig.PlayerListConfig parsePlayerListConfig(Map<String, Object> node) {
    return new StarxConfig.PlayerListConfig(
        integer(node, "refresh-seconds", 5),
        stringValue(node, "header", null),
        stringValue(node, "footer", null));
  }

  private static StarxConfig.NapcatConfig parseNapcatConfig(Map<String, Object> node) {
    return new StarxConfig.NapcatConfig(
        booleanValue(node, "enabled", false),
        stringValue(node, "ws-url", "ws://127.0.0.1:6700"),
        stringValue(node, "http-url", "http://127.0.0.1:3000"),
        longValue(node, "qq-group-id", 0L),
        stringValue(node, "forward-format", "[MC] {player}: {message}"));
  }

  private static DatabaseConfig parseDatabaseConfig(Map<String, Object> node) {
    DatabaseConfig defaults = DatabaseConfig.defaults();
    return new DatabaseConfig(
        stringValue(node, "type", defaults.type()),
        stringValue(node, "host", defaults.host()),
        integer(node, "port", defaults.port()),
        stringValue(node, "database", defaults.database()),
        stringValue(node, "username", defaults.username()),
        stringValue(node, "password", defaults.password()),
        stringValue(node, "url", defaults.url()),
        integer(node, "pool-max-size", defaults.poolMaxSize()),
        longValue(node, "connection-timeout-ms", defaults.connectionTimeoutMs()));
  }
}
