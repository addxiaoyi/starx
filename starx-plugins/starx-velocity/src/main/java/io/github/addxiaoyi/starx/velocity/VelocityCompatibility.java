package io.github.addxiaoyi.starx.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import io.github.addxiaoyi.starx.api.compat.CompatibilityCheck;
import io.github.addxiaoyi.starx.api.compat.CompatibilityReport;
import io.github.addxiaoyi.starx.api.compat.CompatibilityRules;
import io.github.addxiaoyi.starx.api.compat.CompatibilityStatus;
import io.github.addxiaoyi.starx.velocity.config.ConfigLayout;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

final class VelocityCompatibility {
  private static final List<Integration> INTEGRATIONS = List.of(
      new Integration("luckperms", "LuckPerms"),
      new Integration("floodgate", "Floodgate"),
      new Integration("tab", "TAB"),
      new Integration("plan", "Plan"),
      new Integration("geyser", "Geyser"),
      new Integration("raknetify", "RakNetify"),
      new Integration("skinsrestorer", "SkinsRestorer"));

  private VelocityCompatibility() {
  }

  static CompatibilityReport evaluate(
      ProxyServer proxy,
      Path configFile,
      Path dataDirectory,
      Consumer<String> info,
      Consumer<String> warning
  ) throws IOException {
    Settings settings = Settings.load(configFile);
    String velocityVersion = proxy.getVersion().getVersion();
    List<CompatibilityCheck> checks = new ArrayList<>();
    checks.add(CompatibilityRules.javaRuntime(System.getProperty("java.version", "")));
    checks.add(CompatibilityRules.velocityRuntime(velocityVersion));
    for (Integration integration : INTEGRATIONS) {
      checks.add(CompatibilityRules.integration(
          integration.id(), integration.name(), pluginVersion(proxy, integration.id())));
    }
    CompatibilityReport report = new CompatibilityReport(
        "velocity",
        velocityVersion,
        System.getProperty("java.version", ""),
        Instant.now(),
        checks);
    Path data = dataDirectory.toAbsolutePath().normalize();
    Path target = data.resolve(settings.reportFile()).normalize();
    if (!target.startsWith(data)) {
      throw new IllegalArgumentException(
          "compatibility.report-file escapes the plugin directory");
    }
    report.writeAtomically(target);
    info.accept("StarX compatibility: " + report.overallStatus());
    for (CompatibilityCheck check : report.checks()) {
      if (check.status() != CompatibilityStatus.SUPPORTED) {
        warning.accept(check.component() + "=" + check.detectedVersion() + " status="
            + check.status() + " " + check.message());
      }
    }
    if (settings.strictPlatform() && report.blocksStrictStartup()) {
      throw new IllegalStateException(
          "Unsupported Velocity runtime; Uworld requires the certified build. See "
              + target.getFileName());
    }
    return report;
  }

  private static String pluginVersion(ProxyServer proxy, String id) {
    return proxy.getPluginManager().getPlugin(id)
        .flatMap(container -> container.getDescription().getVersion())
        .orElse("");
  }

  private record Integration(String id, String name) {
  }

  record Settings(boolean strictPlatform, String reportFile) {
    static Settings load(Path configFile) throws IOException {
      Map<String, Object> loaded = ConfigLayout.readEffectiveRoot(configFile);
      Object node = loaded.get("compatibility");
      if (!(node instanceof Map<?, ?> compatibility)) {
        return defaults();
      }
      boolean strict = booleanValue(compatibility.get("strict-platform"), true);
      String report = stringValue(
          compatibility.get("report-file"), "compatibility-report.json");
      return new Settings(strict, report);
    }

    static Settings defaults() {
      return new Settings(true, "compatibility-report.json");
    }

    private static boolean booleanValue(Object value, boolean fallback) {
      return value instanceof Boolean bool ? bool : fallback;
    }

    private static String stringValue(Object value, String fallback) {
      if (value == null || value.toString().isBlank()) {
        return fallback;
      }
      return value.toString().trim();
    }
  }
}
