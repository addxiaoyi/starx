/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  com.velocitypowered.api.command.Command
 *  com.velocitypowered.api.command.SimpleCommand
 *  com.velocitypowered.api.command.SimpleCommand$Invocation
 *  com.velocitypowered.api.proxy.ProxyServer
 *  com.velocitypowered.api.proxy.server.RegisteredServer
 *  net.kyori.adventure.text.Component
 *  net.kyori.adventure.text.format.NamedTextColor
 *  net.kyori.adventure.text.format.TextColor
 *  net.kyori.adventure.text.format.TextDecoration
 */
package io.github.addxiaoyi.starx.velocity.module.proxytools;

import io.github.addxiaoyi.starx.api.compat.CompatibilityCheck;
import io.github.addxiaoyi.starx.api.compat.CompatibilityReport;
import io.github.addxiaoyi.starx.api.compat.CompatibilityStatus;
import com.velocitypowered.api.command.Command;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.Collection;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

public final class ProxyInfoModule
implements VelocityModule {
    private static final long STARTUP_TIME = System.currentTimeMillis();
    private final StarxVelocityPlugin plugin;
    private final Config config;
    private CommandMeta commandMeta;

    public ProxyInfoModule(StarxVelocityPlugin plugin, Config config) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public String name() {
        return "starx.info";
    }

    @Override
    public void onEnable() {
        if (!this.config.enabled()) {
            return;
        }
        this.commandMeta = this.plugin.proxy().getCommandManager().metaBuilder("starx").plugin(this.plugin).build();
        this.plugin.proxy().getCommandManager().register(this.commandMeta, (Command)new StarxInfoCommand());
    }

    @Override
    public void onDisable() {
        CommandMeta current = this.commandMeta;
        this.commandMeta = null;
        if (current != null) this.plugin.proxy().getCommandManager().unregister(current);
    }

    private String formatUptime() {
        long uptime = System.currentTimeMillis() - STARTUP_TIME;
        long seconds = uptime / 1000L;
        long minutes = seconds / 60L;
        long hours = minutes / 60L;
        long days = hours / 24L;
        return String.format("%dd %dh %dm %ds", days, hours % 24L, minutes % 60L, seconds % 60L);
    }

    private String formatMemory() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        long used = memoryBean.getHeapMemoryUsage().getUsed() / 0x100000L;
        long max = memoryBean.getHeapMemoryUsage().getMax() / 0x100000L;
        return String.format("%dMB / %dMB", used, max);
    }

    public static interface Config {
        public boolean enabled();

        public static Config defaultConfig() {
            return () -> true;
        }

        public static Config disabled() {
            return () -> false;
        }
    }

    private final class StarxInfoCommand
    implements SimpleCommand {
        private StarxInfoCommand() {
        }

        public void execute(SimpleCommand.Invocation invocation) {
            String[] args = (String[])invocation.arguments();
            if (args.length == 0) {
                this.sendHelp(invocation);
                return;
            }
            switch (args[0].toLowerCase()) {
                case "info": {
                    this.sendInfo(invocation);
                    break;
                }
                case "uptime": {
                    this.sendUptime(invocation);
                    break;
                }
                case "servers": {
                    this.sendServers(invocation);
                    break;
                }
                case "doctor": {
                    this.sendDoctor(invocation);
                    break;
                }
                default: {
                    this.sendHelp(invocation);
                }
            }
        }

        private void sendHelp(SimpleCommand.Invocation invocation) {
            invocation.source().sendMessage(Component.text((String)"StarX 代理端命令：", (TextColor)NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true));
            invocation.source().sendMessage((Component)Component.text((String)"  /starx info    - Proxy status info", (TextColor)NamedTextColor.YELLOW));
            invocation.source().sendMessage((Component)Component.text((String)"  /starx uptime  - Proxy uptime", (TextColor)NamedTextColor.YELLOW));
            invocation.source().sendMessage((Component)Component.text((String)"  /starx servers - Server list", (TextColor)NamedTextColor.YELLOW));
            invocation.source().sendMessage((Component)Component.text((String)"  /starx doctor  - Compatibility diagnostics", (TextColor)NamedTextColor.YELLOW));
        }

        private void sendInfo(SimpleCommand.Invocation invocation) {
            ProxyServer proxy = ProxyInfoModule.this.plugin.proxy();
            invocation.source().sendMessage(Component.text((String)"==== StarX Proxy Info ====", (TextColor)NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true));
            invocation.source().sendMessage(Component.text((String)"在线玩家：", (TextColor)NamedTextColor.WHITE).append((Component)Component.text((int)proxy.getPlayerCount(), (TextColor)NamedTextColor.GREEN)));
            invocation.source().sendMessage(Component.text((String)"子服数量：", (TextColor)NamedTextColor.WHITE).append((Component)Component.text((int)proxy.getAllServers().size(), (TextColor)NamedTextColor.GREEN)));
            invocation.source().sendMessage(Component.text((String)"运行时长：", (TextColor)NamedTextColor.WHITE).append((Component)Component.text((String)ProxyInfoModule.this.formatUptime(), (TextColor)NamedTextColor.GREEN)));
            invocation.source().sendMessage(Component.text((String)"内存：", (TextColor)NamedTextColor.WHITE).append((Component)Component.text((String)ProxyInfoModule.this.formatMemory(), (TextColor)NamedTextColor.GREEN)));
        }

        private void sendUptime(SimpleCommand.Invocation invocation) {
            invocation.source().sendMessage(Component.text((String)"代理端运行时长：", (TextColor)NamedTextColor.GOLD).append((Component)Component.text((String)ProxyInfoModule.this.formatUptime(), (TextColor)NamedTextColor.GREEN)));
        }

        private void sendDoctor(SimpleCommand.Invocation invocation) {
            CompatibilityReport report = ProxyInfoModule.this.plugin.compatibilityReport();
            invocation.source().sendMessage(Component.text(
                "StarX compatibility: " + report.overallStatus(), color(report.overallStatus())));
            for (CompatibilityCheck check : report.checks()) {
                invocation.source().sendMessage(Component.text(
                    "  " + check.component() + ": " + check.status()
                        + " detected=" + check.detectedVersion()
                        + " supported=" + check.supportedRange(),
                    color(check.status())));
            }
        }

        private NamedTextColor color(CompatibilityStatus status) {
            return switch (status) {
                case SUPPORTED -> NamedTextColor.GREEN;
                case UNKNOWN -> NamedTextColor.YELLOW;
                case DEGRADED -> NamedTextColor.GOLD;
                case UNSUPPORTED -> NamedTextColor.RED;
            };
        }

        private void sendServers(SimpleCommand.Invocation invocation) {
            ProxyServer proxy = ProxyInfoModule.this.plugin.proxy();
            Collection<RegisteredServer> servers = proxy.getAllServers();
            invocation.source().sendMessage(Component.text((String)("==== Servers (" + servers.size() + ") ===="), (TextColor)NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true));
            for (RegisteredServer server : servers) {
                int playerCount = server.getPlayersConnected().size();
                String status = server.ping() != null ? "Online" : "Offline";
                NamedTextColor color = "Online".equals(status) ? NamedTextColor.GREEN : NamedTextColor.RED;
                invocation.source().sendMessage(Component.text((String)("  " + server.getServerInfo().getName() + ": "), (TextColor)NamedTextColor.WHITE).append((Component)Component.text((String)(status + " (" + playerCount + " players)"), (TextColor)color)));
            }
        }
    }
}
