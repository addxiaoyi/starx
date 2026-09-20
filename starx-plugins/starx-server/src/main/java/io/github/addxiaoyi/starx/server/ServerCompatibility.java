package io.github.addxiaoyi.starx.server;

import io.github.addxiaoyi.starx.api.compat.CompatibilityCheck;
import io.github.addxiaoyi.starx.api.compat.CompatibilityReport;
import io.github.addxiaoyi.starx.api.compat.CompatibilityRules;
import io.github.addxiaoyi.starx.api.compat.CompatibilityStatus;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

final class ServerCompatibility {
  private ServerCompatibility() {
  }

  static CompatibilityReport evaluate(JavaPlugin plugin, ServerPlatform platform) throws IOException {
    List<CompatibilityCheck> checks = new ArrayList<>();
    checks.add(CompatibilityRules.javaRuntime(System.getProperty("java.version", "")));
    checks.add(new CompatibilityCheck(
        "platform",
        platform.name(),
        Bukkit.getName() + " " + Bukkit.getVersion(),
        "Paper/Folia",
        CompatibilityStatus.SUPPORTED,
        "通用后端入口与调度器已针对该执行模型启用"));
    checks.add(CompatibilityRules.minecraftRuntime(Bukkit.getMinecraftVersion()));
    checks.add(CompatibilityRules.integration(
        "skinsrestorer", "SkinsRestorer", pluginVersion("SkinsRestorer")));
    checks.add(CompatibilityRules.integration(
        "placeholderapi", "PlaceholderAPI", pluginVersion("PlaceholderAPI")));

    CompatibilityReport report = new CompatibilityReport(
        platform.name().toLowerCase(Locale.ROOT),
        Bukkit.getVersion(),
        System.getProperty("java.version", ""),
        Instant.now(),
        checks);
    Path data = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
    String reportName = plugin.getConfig().getString(
        "compatibility.report-file", "compatibility-report.json");
    Path target = data.resolve(
        reportName == null ? "compatibility-report.json" : reportName).normalize();
    if (!target.startsWith(data)) {
      throw new IllegalArgumentException(
          "compatibility.report-file escapes the plugin directory");
    }
    report.writeAtomically(target);
    log(plugin, report);
    if (plugin.getConfig().getBoolean("compatibility.strict-platform", true)
        && report.blocksStrictStartup()) {
      throw new IllegalStateException(
          "Unsupported StarX backend runtime; see " + target.getFileName());
    }
    return report;
  }

  private static String pluginVersion(String name) {
    Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
    return plugin == null ? "" : plugin.getPluginMeta().getVersion();
  }

  private static void log(JavaPlugin plugin, CompatibilityReport report) {
    plugin.getLogger().info("StarX compatibility: " + report.overallStatus());
    for (CompatibilityCheck check : report.checks()) {
      if (check.status() != CompatibilityStatus.SUPPORTED) {
        plugin.getLogger().warning(
            check.component() + "=" + check.detectedVersion() + " status="
                + check.status() + " " + check.message());
      }
    }
  }
}
