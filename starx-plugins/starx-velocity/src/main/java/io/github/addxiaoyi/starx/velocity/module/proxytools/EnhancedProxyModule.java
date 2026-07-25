/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  com.velocitypowered.api.command.Command
 *  com.velocitypowered.api.command.SimpleCommand
 *  com.velocitypowered.api.command.SimpleCommand$Invocation
 *  com.velocitypowered.api.proxy.Player
 *  com.velocitypowered.api.proxy.ProxyServer
 *  com.velocitypowered.api.proxy.ServerConnection
 *  com.velocitypowered.api.proxy.server.RegisteredServer
 *  net.kyori.adventure.text.Component
 *  net.kyori.adventure.text.TextComponent
 *  net.kyori.adventure.text.format.NamedTextColor
 *  net.kyori.adventure.text.format.TextColor
 *  net.kyori.adventure.text.format.TextDecoration
 */
package io.github.addxiaoyi.starx.velocity.module.proxytools;

import com.velocitypowered.api.command.Command;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

public final class EnhancedProxyModule
implements VelocityModule {
    private final StarxVelocityPlugin plugin;
    private final Config config;
    private final GListCommand glistCommand;
    private final FindCommand findCommand;
    private final SendCommand sendCommand;
    private final AlertCommand alertCommand;
    private final PingCommand pingCommand;
    private final KickAllCommand kickAllCommand;
    private CommandMeta commandMeta;

    public EnhancedProxyModule(StarxVelocityPlugin plugin, Config config) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
        this.glistCommand = new GListCommand();
        this.findCommand = new FindCommand();
        this.sendCommand = new SendCommand();
        this.alertCommand = new AlertCommand();
        this.pingCommand = new PingCommand();
        this.kickAllCommand = new KickAllCommand();
    }

    @Override
    public String name() {
        return "starx.enhanced";
    }

    @Override
    public void onEnable() {
        if (!this.config.enabled()) {
            return;
        }
        ProxyServer proxy = this.plugin.proxy();
        this.commandMeta = proxy.getCommandManager().metaBuilder("sxnet").plugin(this.plugin).build();
        proxy.getCommandManager().register(this.commandMeta, (Command)new NetworkCommand());
    }

    @Override
    public void onDisable() {
        CommandMeta current = this.commandMeta;
        this.commandMeta = null;
        if (current != null) this.plugin.proxy().getCommandManager().unregister(current);
    }

    private final class NetworkCommand implements SimpleCommand {
        @Override public void execute(SimpleCommand.Invocation invocation) {
            String[] args = invocation.arguments();
            if (args.length == 0) { invocation.source().sendMessage(Component.text("用法：/sxnet <list|find|send|alert|ping|drain> ...", NamedTextColor.YELLOW)); return; }
            String action = args[0].toLowerCase();
            SimpleCommand delegate = switch (action) {
                case "list" -> glistCommand;
                case "find" -> findCommand;
                case "send" -> sendCommand;
                case "alert" -> alertCommand;
                case "ping" -> pingCommand;
                case "drain" -> kickAllCommand;
                default -> null;
            };
            if (delegate == null) { invocation.source().sendMessage(Component.text("未知网络操作。可用：list/find/send/alert/ping/drain", NamedTextColor.RED)); return; }
            delegate.execute(new RoutedInvocation(invocation, java.util.Arrays.copyOfRange(args, 1, args.length), "sxnet"));
        }
        @Override public List<String> suggest(SimpleCommand.Invocation invocation) {
            String[] args = invocation.arguments();
            List<String> actions = List.of("list", "find", "send", "alert", "ping", "drain");
            if (args.length <= 1) {
                String prefix = args.length == 0 ? "" : args[0].toLowerCase();
                return actions.stream().filter(action -> action.startsWith(prefix)).toList();
            }
            SimpleCommand delegate = switch (args[0].toLowerCase()) {
                case "list" -> glistCommand; case "find" -> findCommand; case "send" -> sendCommand;
                case "alert" -> alertCommand; case "ping" -> pingCommand; case "drain" -> kickAllCommand; default -> null;
            };
            return delegate == null ? List.of() : delegate.suggest(
                new RoutedInvocation(invocation, java.util.Arrays.copyOfRange(args, 1, args.length), "sxnet"));
        }
    }

    private record RoutedInvocation(SimpleCommand.Invocation original, String[] arguments, String alias)
        implements SimpleCommand.Invocation {
        @Override public com.velocitypowered.api.command.CommandSource source() { return original.source(); }
    }

    SimpleCommand getGlistCommand() {
        return this.glistCommand;
    }

    SimpleCommand getFindCommand() {
        return this.findCommand;
    }

    SimpleCommand getSendCommand() {
        return this.sendCommand;
    }

    SimpleCommand getAlertCommand() {
        return this.alertCommand;
    }

    SimpleCommand getPingCommand() {
        return this.pingCommand;
    }

    SimpleCommand getKickAllCommand() {
        return this.kickAllCommand;
    }

    public static interface Config {
        public boolean enabled();

        public ServerDisplayConfig servers();

        public ProgressBarConfig progressBar();

        public VanishConfig vanish();

        public static Config simpleDefault() {
            return new Config(){

                @Override
                public boolean enabled() {
                    return true;
                }

                @Override
                public ServerDisplayConfig servers() {
                    return ServerDisplayConfig.defaultConfig();
                }

                @Override
                public ProgressBarConfig progressBar() {
                    return ProgressBarConfig.defaultConfig();
                }

                @Override
                public VanishConfig vanish() {
                    return VanishConfig.defaultConfig();
                }
            };
        }

        public static Config disabled() {
            return new Config(){

                @Override
                public boolean enabled() {
                    return false;
                }

                @Override
                public ServerDisplayConfig servers() {
                    return ServerDisplayConfig.defaultConfig();
                }

                @Override
                public ProgressBarConfig progressBar() {
                    return ProgressBarConfig.defaultConfig();
                }

                @Override
                public VanishConfig vanish() {
                    return VanishConfig.defaultConfig();
                }
            };
        }
    }

    private final class GListCommand
    implements SimpleCommand {
        private GListCommand() {
        }

        public void execute(SimpleCommand.Invocation invocation) {
            if (!invocation.source().hasPermission("starx.commands.glist")) {
                invocation.source().sendMessage((Component)Component.text((String)"你没有权限执行此命令。", (TextColor)NamedTextColor.RED));
                return;
            }
            ProxyServer proxy = EnhancedProxyModule.this.plugin.proxy();
            Collection<RegisteredServer> servers = proxy.getAllServers();
            int totalPlayers = proxy.getPlayerCount();
            invocation.source().sendMessage(((TextComponent)((TextComponent)Component.text((String)"==== Global Server List (", (TextColor)NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true)).append((Component)Component.text((int)totalPlayers, (TextColor)NamedTextColor.GREEN))).append((Component)Component.text((String)" players) ====", (TextColor)NamedTextColor.GOLD)));
            for (RegisteredServer server : servers) {
                String serverName = server.getServerInfo().getName();
                Collection<Player> players = server.getPlayersConnected();
                int playerCount = players.size();
                NamedTextColor color = playerCount > 0 ? NamedTextColor.GREEN : NamedTextColor.GRAY;
                String playerList = players.isEmpty() ? "No players" : players.stream().map(Player::getUsername).collect(Collectors.joining(", "));
                invocation.source().sendMessage(((TextComponent)Component.text((String)("  " + serverName + ": "), (TextColor)NamedTextColor.WHITE).append((Component)Component.text((String)("(" + playerCount + ") "), (TextColor)color))).append((Component)Component.text((String)playerList, (TextColor)NamedTextColor.GRAY)));
            }
        }
    }

    private final class FindCommand
    implements SimpleCommand {
        private FindCommand() {
        }

        public void execute(SimpleCommand.Invocation invocation) {
            if (!invocation.source().hasPermission("starx.commands.find")) {
                invocation.source().sendMessage((Component)Component.text((String)"你没有权限执行此命令。", (TextColor)NamedTextColor.RED));
                return;
            }
            String[] args = (String[])invocation.arguments();
            if (args.length == 0) {
                invocation.source().sendMessage((Component)Component.text((String)"用法：/sxnet find <玩家>", (TextColor)NamedTextColor.YELLOW));
                return;
            }
            String targetName = args[0];
            Optional targetOpt = EnhancedProxyModule.this.plugin.proxy().getPlayer(targetName);
            if (targetOpt.isEmpty()) {
                invocation.source().sendMessage(Component.text((String)"未找到玩家：", (TextColor)NamedTextColor.RED).append((Component)Component.text((String)targetName, (TextColor)NamedTextColor.WHITE)));
                return;
            }
            Player target = (Player)targetOpt.get();
            Optional serverConn = target.getCurrentServer();
            if (serverConn.isEmpty()) {
                invocation.source().sendMessage(((TextComponent)Component.text((String)"玩家 ", (TextColor)NamedTextColor.YELLOW).append((Component)Component.text((String)target.getUsername(), (TextColor)NamedTextColor.GREEN))).append((Component)Component.text((String)" 当前未连接任何子服。", (TextColor)NamedTextColor.YELLOW)));
                return;
            }
            String serverName = ((ServerConnection)serverConn.get()).getServerInfo().getName();
            invocation.source().sendMessage(((TextComponent)((TextComponent)Component.text((String)"玩家 ", (TextColor)NamedTextColor.GOLD).append((Component)Component.text((String)target.getUsername(), (TextColor)NamedTextColor.GREEN))).append((Component)Component.text((String)" 当前位于 ", (TextColor)NamedTextColor.GOLD))).append((Component)Component.text((String)serverName, (TextColor)NamedTextColor.AQUA)));
        }

        public List<String> suggest(SimpleCommand.Invocation invocation) {
            String lastArg = ((String[])invocation.arguments()).length > 0 ? ((String[])invocation.arguments())[((String[])invocation.arguments()).length - 1] : "";
            return EnhancedProxyModule.this.plugin.proxy().getAllPlayers().stream().map(Player::getUsername).filter(name -> name.toLowerCase().startsWith(lastArg.toLowerCase())).sorted().collect(Collectors.toList());
        }
    }

    private final class SendCommand
    implements SimpleCommand {
        private SendCommand() {
        }

        public void execute(SimpleCommand.Invocation invocation) {
            if (!invocation.source().hasPermission("starx.commands.send")) {
                invocation.source().sendMessage((Component)Component.text((String)"你没有权限执行此命令。", (TextColor)NamedTextColor.RED));
                return;
            }
            String[] args = (String[])invocation.arguments();
            if (args.length < 2) {
                invocation.source().sendMessage((Component)Component.text((String)"用法：/sxnet send <玩家> <服务器>", (TextColor)NamedTextColor.YELLOW));
                return;
            }
            String playerName = args[0];
            String serverName = args[1];
            Optional targetOpt = EnhancedProxyModule.this.plugin.proxy().getPlayer(playerName);
            if (targetOpt.isEmpty()) {
                invocation.source().sendMessage(Component.text((String)"未找到玩家：", (TextColor)NamedTextColor.RED).append((Component)Component.text((String)playerName, (TextColor)NamedTextColor.WHITE)));
                return;
            }
            Optional serverOpt = EnhancedProxyModule.this.plugin.proxy().getServer(serverName);
            if (serverOpt.isEmpty()) {
                invocation.source().sendMessage(Component.text((String)"未找到服务器：", (TextColor)NamedTextColor.RED).append((Component)Component.text((String)serverName, (TextColor)NamedTextColor.WHITE)));
                return;
            }
            Player target = (Player)targetOpt.get();
            RegisteredServer server = (RegisteredServer)serverOpt.get();
            target.createConnectionRequest(server).fireAndForget();
            invocation.source().sendMessage(((TextComponent)((TextComponent)Component.text((String)"已将 ", (TextColor)NamedTextColor.GREEN).append((Component)Component.text((String)target.getUsername(), (TextColor)NamedTextColor.AQUA))).append((Component)Component.text((String)" 发送至 ", (TextColor)NamedTextColor.GREEN))).append((Component)Component.text((String)server.getServerInfo().getName(), (TextColor)NamedTextColor.AQUA)));
        }

        public List<String> suggest(SimpleCommand.Invocation invocation) {
            String[] args = (String[])invocation.arguments();
            if (args.length <= 1) {
                String lastArg = args.length == 0 ? "" : args[0];
                return EnhancedProxyModule.this.plugin.proxy().getAllPlayers().stream().map(Player::getUsername).filter(name -> name.toLowerCase().startsWith(lastArg.toLowerCase())).sorted().collect(Collectors.toList());
            }
            String lastArg = args[args.length - 1];
            return EnhancedProxyModule.this.plugin.proxy().getAllServers().stream().map(s -> s.getServerInfo().getName()).filter(name -> name.toLowerCase().startsWith(lastArg.toLowerCase())).sorted().collect(Collectors.toList());
        }
    }

    private final class AlertCommand
    implements SimpleCommand {
        private AlertCommand() {
        }

        public void execute(SimpleCommand.Invocation invocation) {
            if (!invocation.source().hasPermission("starx.commands.alert")) {
                invocation.source().sendMessage((Component)Component.text((String)"你没有权限执行此命令。", (TextColor)NamedTextColor.RED));
                return;
            }
            CharSequence[] args = (String[])invocation.arguments();
            if (args.length == 0) {
                invocation.source().sendMessage((Component)Component.text((String)"用法：/sxnet alert <消息>", (TextColor)NamedTextColor.YELLOW));
                return;
            }
            String message = String.join((CharSequence)" ", args);
            Component alertMessage = ((TextComponent)Component.text((String)"[Alert] ", (TextColor)NamedTextColor.RED).decoration(TextDecoration.BOLD, true)).append((Component)Component.text((String)message, (TextColor)NamedTextColor.GOLD));
            for (RegisteredServer server : EnhancedProxyModule.this.plugin.proxy().getAllServers()) {
                for (Player player : server.getPlayersConnected()) {
                    player.sendMessage(alertMessage);
                }
            }
        }
    }

    private final class PingCommand
    implements SimpleCommand {
        private PingCommand() {
        }

        public void execute(SimpleCommand.Invocation invocation) {
            if (!invocation.source().hasPermission("starx.commands.ping")) {
                invocation.source().sendMessage((Component)Component.text((String)"你没有权限执行此命令。", (TextColor)NamedTextColor.RED));
                return;
            }
            String[] args = (String[])invocation.arguments();
            if (args.length > 0) {
                String targetName = args[0];
                Optional targetOpt = EnhancedProxyModule.this.plugin.proxy().getPlayer(targetName);
                if (targetOpt.isEmpty()) {
                    invocation.source().sendMessage(Component.text((String)"未找到玩家：", (TextColor)NamedTextColor.RED).append((Component)Component.text((String)targetName, (TextColor)NamedTextColor.WHITE)));
                    return;
                }
                Player target = (Player)targetOpt.get();
                invocation.source().sendMessage(((TextComponent)Component.text((String)target.getUsername(), (TextColor)NamedTextColor.AQUA).append((Component)Component.text((String)" 的延迟：", (TextColor)NamedTextColor.GOLD))).append((Component)Component.text((String)(target.getPing() + "ms"), (TextColor)NamedTextColor.GREEN)));
                return;
            }
            if (!(invocation.source() instanceof Player)) {
                invocation.source().sendMessage((Component)Component.text((String)"不带参数时，此命令只能由玩家执行。", (TextColor)NamedTextColor.RED));
                return;
            }
            Player self = (Player)invocation.source();
            invocation.source().sendMessage(Component.text((String)"你的延迟：", (TextColor)NamedTextColor.GOLD).append((Component)Component.text((String)(self.getPing() + "ms"), (TextColor)NamedTextColor.GREEN)));
        }

        public List<String> suggest(SimpleCommand.Invocation invocation) {
            String lastArg = ((String[])invocation.arguments()).length > 0 ? ((String[])invocation.arguments())[((String[])invocation.arguments()).length - 1] : "";
            return EnhancedProxyModule.this.plugin.proxy().getAllPlayers().stream().map(Player::getUsername).filter(name -> name.toLowerCase().startsWith(lastArg.toLowerCase())).sorted().collect(Collectors.toList());
        }
    }

    private final class KickAllCommand
    implements SimpleCommand {
        private KickAllCommand() {
        }

        public void execute(SimpleCommand.Invocation invocation) {
            if (!invocation.source().hasPermission("starx.commands.kickall")) {
                invocation.source().sendMessage((Component)Component.text((String)"你没有权限执行此命令。", (TextColor)NamedTextColor.RED));
                return;
            }
            String[] args = (String[])invocation.arguments();
            if (args.length == 0) {
                invocation.source().sendMessage((Component)Component.text((String)"用法：/sxnet drain <服务器> [原因]", (TextColor)NamedTextColor.YELLOW));
                return;
            }
            String serverName = args[0];
            ProxyServer proxy = EnhancedProxyModule.this.plugin.proxy();
            Optional targetServerOpt = proxy.getServer(serverName);
            if (targetServerOpt.isEmpty()) {
                invocation.source().sendMessage(Component.text((String)"未找到服务器：", (TextColor)NamedTextColor.RED).append((Component)Component.text((String)serverName, (TextColor)NamedTextColor.WHITE)));
                return;
            }
            RegisteredServer targetServer = (RegisteredServer)targetServerOpt.get();
            Collection<RegisteredServer> allServers = proxy.getAllServers();
            RegisteredServer fallback = allServers.stream().filter(s -> !s.getServerInfo().getName().equals(targetServer.getServerInfo().getName())).findFirst().orElse(targetServer);
            int kicked = 0;
            for (Player player : targetServer.getPlayersConnected()) {
                if (player.hasPermission("starx.kickall.bypass")) continue;
                player.createConnectionRequest(fallback).fireAndForget();
                ++kicked;
            }
            invocation.source().sendMessage(((TextComponent)((TextComponent)Component.text((String)"已从服务器移出 ", (TextColor)NamedTextColor.GREEN).append((Component)Component.text((int)kicked, (TextColor)NamedTextColor.AQUA))).append((Component)Component.text((String)" 名玩家：", (TextColor)NamedTextColor.GREEN))).append((Component)Component.text((String)targetServer.getServerInfo().getName(), (TextColor)NamedTextColor.AQUA)));
        }

        public List<String> suggest(SimpleCommand.Invocation invocation) {
            String lastArg = ((String[])invocation.arguments()).length > 0 ? ((String[])invocation.arguments())[((String[])invocation.arguments()).length - 1] : "";
            return EnhancedProxyModule.this.plugin.proxy().getAllServers().stream().map(s -> s.getServerInfo().getName()).filter(name -> name.toLowerCase().startsWith(lastArg.toLowerCase())).sorted().collect(Collectors.toList());
        }
    }

    public static interface VanishConfig {
        public boolean enabled();

        public String playerDecoration();

        public String serverDecoration();

        public static VanishConfig defaultConfig() {
            return new VanishConfig(){

                @Override
                public boolean enabled() {
                    return false;
                }

                @Override
                public String playerDecoration() {
                    return "&o$player";
                }

                @Override
                public String serverDecoration() {
                    return "&o$server";
                }
            };
        }
    }

    public static interface ProgressBarConfig {
        public int count();

        public String complete();

        public String notComplete();

        public static ProgressBarConfig defaultConfig() {
            return new ProgressBarConfig(){

                @Override
                public int count() {
                    return 45;
                }

                @Override
                public String complete() {
                    return "|";
                }

                @Override
                public String notComplete() {
                    return "starx..";
                }
            };
        }
    }

    public static interface ServerDisplayConfig {
        public List<String> hiddenServers();

        public Map<String, List<String>> summarizedServers();

        public Map<String, String> displayNames();

        public static ServerDisplayConfig defaultConfig() {
            return new ServerDisplayConfig(){

                @Override
                public List<String> hiddenServers() {
                    return List.of();
                }

                @Override
                public Map<String, List<String>> summarizedServers() {
                    return Map.of();
                }

                @Override
                public Map<String, String> displayNames() {
                    return Map.of();
                }
            };
        }
    }
}
