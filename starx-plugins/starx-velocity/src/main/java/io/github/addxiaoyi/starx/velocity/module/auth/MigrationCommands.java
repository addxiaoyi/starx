/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  com.velocitypowered.api.command.Command
 *  com.velocitypowered.api.command.SimpleCommand
 *  com.velocitypowered.api.command.SimpleCommand$Invocation
 *  net.kyori.adventure.text.Component
 *  net.kyori.adventure.text.format.NamedTextColor
 *  net.kyori.adventure.text.format.TextColor
 */
package io.github.addxiaoyi.starx.velocity.module.auth;

import com.velocitypowered.api.command.Command;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import io.github.addxiaoyi.starx.common.auth.uniauth.UniAuthClient;
import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import io.github.addxiaoyi.starx.velocity.module.auth.MigrationModule;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

public final class MigrationCommands
implements VelocityModule {
    private final StarxVelocityPlugin plugin;
    private final JdbcUserRepository userRepository;
    private final MigrationModule migrationModule;
    private final UniAuthClient uniAuthClient;
    private CommandMeta commandMeta;

    public MigrationCommands(StarxVelocityPlugin plugin, JdbcUserRepository userRepository, MigrationModule migrationModule, UniAuthClient uniAuthClient) {
        this.plugin = plugin;
        this.userRepository = userRepository;
        this.migrationModule = migrationModule;
        this.uniAuthClient = uniAuthClient;
    }

    @Override
    public String name() {
        return "starx.auth.migration.commands";
    }

    @Override
    public void onEnable() {
        CommandMeta current = this.plugin.proxy().getCommandManager().metaBuilder("sxmigrate").build();
        this.commandMeta = current;
        this.plugin.proxy().getCommandManager().register(
            current,
            (Command)new MigrationCommand());
    }

    @Override
    public void onDisable() {
        CommandMeta current = this.commandMeta;
        this.commandMeta = null;
        if (current != null) this.plugin.proxy().getCommandManager().unregister(current);
    }

    private final class MigrationCommand
    implements SimpleCommand {
        private MigrationCommand() {
        }

        public void execute(SimpleCommand.Invocation invocation) {
            String[] args = (String[])invocation.arguments();
            if (args.length == 0) {
                this.showHelp(invocation);
                return;
            }
            switch (args[0].toLowerCase()) {
                case "import": {
                    this.handleImport(invocation, args);
                    break;
                }
                case "status": {
                    this.handleMigrateStatus(invocation);
                    break;
                }
                default: {
                    this.showHelp(invocation);
                }
            }
        }

        public boolean hasPermission(SimpleCommand.Invocation invocation) {
            return invocation.source().hasPermission("starx.admin.migrate");
        }

        private void showHelp(SimpleCommand.Invocation invocation) {
            invocation.source().sendMessage((Component)Component.text((String)"===== StarX \u8fc1\u79fb\u7ba1\u7406 =====", (TextColor)NamedTextColor.GOLD));
            invocation.source().sendMessage(Component.text((String)"/sxmigrate status", (TextColor)NamedTextColor.YELLOW).append((Component)Component.text((String)" - \u67e5\u770b\u8fc1\u79fb\u72b6\u6001\u7edf\u8ba1", (TextColor)NamedTextColor.GRAY)));
            invocation.source().sendMessage(Component.text((String)"/sxmigrate import starvc [--dry-run]", (TextColor)NamedTextColor.YELLOW).append((Component)Component.text((String)" - \u4ece StarVC \u5bfc\u5165\u7528\u6237\u5143\u6570\u636e", (TextColor)NamedTextColor.GRAY)));
            invocation.source().sendMessage((Component)Component.text((String)"  --dry-run - \u8bd5\u8fd0\u884c\uff0c\u4e0d\u5b9e\u9645\u4fee\u6539\u6570\u636e", (TextColor)NamedTextColor.GRAY));
        }

        private void handleMigrateStatus(SimpleCommand.Invocation invocation) {
            if (MigrationCommands.this.userRepository == null) {
                invocation.source().sendMessage((Component)Component.text((String)"\u7528\u6237\u4ed3\u5e93\u4e0d\u53ef\u7528", (TextColor)NamedTextColor.RED));
                return;
            }
            try {
                int total = MigrationCommands.this.userRepository.countAll();
                int starvcUsers = MigrationCommands.this.userRepository.countBySourceSystem("starvc");
                int starvcPending = MigrationCommands.this.userRepository.countBySourceSystemAndMigrationState("starvc", "pending");
                int starvcCompleted = MigrationCommands.this.userRepository.countBySourceSystemAndMigrationState("starvc", "completed");
                invocation.source().sendMessage((Component)Component.text((String)"===== \u8fc1\u79fb\u72b6\u6001 =====", (TextColor)NamedTextColor.GOLD));
                invocation.source().sendMessage(Component.text((String)"\u603b\u7528\u6237\u6570: ", (TextColor)NamedTextColor.WHITE).append((Component)Component.text((int)total, (TextColor)NamedTextColor.AQUA)));
                invocation.source().sendMessage(Component.text((String)"StarVC \u7528\u6237: ", (TextColor)NamedTextColor.WHITE).append((Component)Component.text((int)starvcUsers, (TextColor)NamedTextColor.AQUA)));
                invocation.source().sendMessage(Component.text((String)"StarVC \u5f85\u8fc1\u79fb: ", (TextColor)NamedTextColor.WHITE).append((Component)Component.text((int)starvcPending, (TextColor)NamedTextColor.YELLOW)));
                invocation.source().sendMessage(Component.text((String)"StarVC \u5df2\u8fc1\u79fb: ", (TextColor)NamedTextColor.WHITE).append((Component)Component.text((int)starvcCompleted, (TextColor)NamedTextColor.GREEN)));
                if (starvcUsers > 0) {
                    double progress = (double)starvcCompleted / (double)starvcUsers * 100.0;
                    invocation.source().sendMessage(Component.text((String)"StarVC \u8fc1\u79fb\u8fdb\u5ea6: ", (TextColor)NamedTextColor.WHITE).append((Component)Component.text((String)String.format("%.1f%%", progress), (TextColor)NamedTextColor.GREEN)));
                }
            }
            catch (Exception e) {
                invocation.source().sendMessage((Component)Component.text((String)("\u83b7\u53d6\u8fc1\u79fb\u72b6\u6001\u5931\u8d25: " + e.getMessage()), (TextColor)NamedTextColor.RED));
                MigrationCommands.this.plugin.logger().log(Level.SEVERE, "\u83b7\u53d6\u8fc1\u79fb\u72b6\u6001\u5931\u8d25", e);
            }
        }

        private void handleImport(SimpleCommand.Invocation invocation, String[] args) {
            if (args.length < 2) {
                this.showMigrateHelp(invocation);
                return;
            }
            String source = args[1].toLowerCase();
            if ("starvc".equals(source)) {
                boolean dryRun = false;
                for (int i = 2; i < args.length; ++i) {
                    if (!"--dry-run".equals(args[i])) continue;
                    dryRun = true;
                    break;
                }
                this.handleStarVCImportMeta(invocation, dryRun);
            } else {
                this.showMigrateHelp(invocation);
            }
        }

        private void showMigrateHelp(SimpleCommand.Invocation invocation) {
            invocation.source().sendMessage((Component)Component.text((String)"===== \u8fc1\u79fb\u547d\u4ee4 =====", (TextColor)NamedTextColor.GOLD));
            invocation.source().sendMessage((Component)Component.text((String)"/sxmigrate import starvc [--dry-run]", (TextColor)NamedTextColor.YELLOW));
        }

        private void handleStarVCImportMeta(SimpleCommand.Invocation invocation, boolean dryRun) {
            if (MigrationModule.isRunning()) {
                invocation.source().sendMessage((Component)Component.text((String)"\u8fc1\u79fb\u6b63\u5728\u8fdb\u884c\u4e2d\uff0c\u8bf7\u7a0d\u540e\u518d\u8bd5", (TextColor)NamedTextColor.RED));
                return;
            }
            if (MigrationCommands.this.migrationModule == null) {
                invocation.source().sendMessage((Component)Component.text((String)"\u8fc1\u79fb\u6a21\u5757\u4e0d\u53ef\u7528", (TextColor)NamedTextColor.RED));
                return;
            }
            if (dryRun) {
                invocation.source().sendMessage((Component)Component.text((String)"\u5f00\u59cb StarVC \u7528\u6237\u5143\u6570\u636e\u5bfc\u5165\uff08\u8bd5\u8fd0\u884c\uff09...", (TextColor)NamedTextColor.YELLOW));
            } else {
                invocation.source().sendMessage((Component)Component.text((String)"\u5f00\u59cb StarVC \u7528\u6237\u5143\u6570\u636e\u5bfc\u5165...", (TextColor)NamedTextColor.YELLOW));
            }
            MigrationCommands.this.plugin.proxy().getScheduler().buildTask((Object)MigrationCommands.this.plugin, () -> {
                try {
                    MigrationModule.MigrationResult result = MigrationCommands.this.migrationModule.importStarVCMeta(dryRun);
                    this.showMigrationResult(invocation, result);
                }
                catch (Exception e) {
                    invocation.source().sendMessage((Component)Component.text((String)("\u5bfc\u5165\u5931\u8d25: " + e.getMessage()), (TextColor)NamedTextColor.RED));
                    MigrationCommands.this.plugin.logger().log(Level.SEVERE, "\u5bfc\u5165\u5931\u8d25", e);
                }
            }).schedule();
        }

        private void showMigrationResult(SimpleCommand.Invocation invocation, MigrationModule.MigrationResult result) {
            invocation.source().sendMessage((Component)Component.text((String)"===== \u8fc1\u79fb\u5b8c\u6210 =====", (TextColor)NamedTextColor.GOLD));
            if (result.dryRun()) {
                invocation.source().sendMessage((Component)Component.text((String)"(\u8bd5\u8fd0\u884c\u6a21\u5f0f\uff0c\u65e0\u5b9e\u9645\u4fee\u6539)", (TextColor)NamedTextColor.GRAY));
            }
            invocation.source().sendMessage(Component.text((String)"\u603b\u8ba1: ", (TextColor)NamedTextColor.WHITE).append((Component)Component.text((int)result.total(), (TextColor)NamedTextColor.AQUA)));
            invocation.source().sendMessage(Component.text((String)"\u5bfc\u5165: ", (TextColor)NamedTextColor.WHITE).append((Component)Component.text((int)result.imported(), (TextColor)NamedTextColor.GREEN)));
            invocation.source().sendMessage(Component.text((String)"\u8df3\u8fc7(\u5df2\u5b58\u5728): ", (TextColor)NamedTextColor.WHITE).append((Component)Component.text((int)result.skippedExisting(), (TextColor)NamedTextColor.GRAY)));
            invocation.source().sendMessage(Component.text((String)"\u8df3\u8fc7(\u65e0\u6548): ", (TextColor)NamedTextColor.WHITE).append((Component)Component.text((int)result.skippedInvalid(), (TextColor)NamedTextColor.YELLOW)));
            invocation.source().sendMessage(Component.text((String)"\u9519\u8bef: ", (TextColor)NamedTextColor.WHITE).append((Component)Component.text((int)result.errors(), (TextColor)NamedTextColor.RED)));
            invocation.source().sendMessage(Component.text((String)"\u8017\u65f6: ", (TextColor)NamedTextColor.WHITE).append((Component)Component.text((String)(result.durationMs() + "ms"), (TextColor)NamedTextColor.AQUA)));
            if (result.total() > 0) {
                double successRate = (double)(result.imported() + result.skippedExisting()) / (double)result.total() * 100.0;
                invocation.source().sendMessage(Component.text((String)"\u6210\u529f\u7387: ", (TextColor)NamedTextColor.WHITE).append((Component)Component.text((String)String.format("%.1f%%", successRate), (TextColor)NamedTextColor.GREEN)));
            }
        }
    }
}
