/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  com.velocitypowered.api.command.Command
 *  com.velocitypowered.api.command.CommandManager
 *  com.velocitypowered.api.command.CommandSource
 *  com.velocitypowered.api.command.SimpleCommand
 *  com.velocitypowered.api.command.SimpleCommand$Invocation
 *  com.velocitypowered.api.proxy.Player
 *  com.velocitypowered.api.proxy.ProxyServer
 *  net.kyori.adventure.text.Component
 *  net.kyori.adventure.text.TextComponent
 *  net.kyori.adventure.text.format.NamedTextColor
 *  net.kyori.adventure.text.format.TextColor
 */
package io.github.addxiaoyi.starx.velocity.module.admin;

import com.velocitypowered.api.command.Command;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.addxiaoyi.starx.common.auth.BindingVerificationService;
import io.github.addxiaoyi.starx.common.database.JdbcAnnouncementRepository;
import io.github.addxiaoyi.starx.common.database.JdbcBindingRepository;
import io.github.addxiaoyi.starx.common.database.JdbcPunishmentRepository;
import io.github.addxiaoyi.starx.common.database.JdbcReportRepository;
import io.github.addxiaoyi.starx.common.database.JdbcStaffNoteRepository;
import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import io.github.addxiaoyi.starx.common.model.Announcement;
import io.github.addxiaoyi.starx.common.model.PlayerBinding;
import io.github.addxiaoyi.starx.common.model.Punishment;
import io.github.addxiaoyi.starx.common.model.Report;
import io.github.addxiaoyi.starx.common.model.StaffNote;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

public final class AdminCommandsModule
implements VelocityModule {
    private static final List<String> REPORT_CATEGORIES = List.of("CHEATING", "CHAT_ABUSE", "SPAM", "NAME", "OTHER");
    private static final List<String> NOTE_SEVERITIES = List.of("INFO", "WARNING", "CRITICAL");
    private final StarxVelocityPlugin plugin;
    private final JdbcUserRepository userRepo;
    private final JdbcPunishmentRepository punishmentRepo;
    private final JdbcStaffNoteRepository staffNoteRepo;
    private final JdbcReportRepository reportRepo;
    private final JdbcAnnouncementRepository announcementRepo;
    private final JdbcBindingRepository bindingRepo;
    private final BindingVerificationService bindingVerification;
    private CommandMeta commandMeta;

    public AdminCommandsModule(StarxVelocityPlugin plugin, JdbcUserRepository userRepo, JdbcPunishmentRepository punishmentRepo, JdbcStaffNoteRepository staffNoteRepo, JdbcReportRepository reportRepo, JdbcAnnouncementRepository announcementRepo, JdbcBindingRepository bindingRepo, BindingVerificationService bindingVerification) {
        this.plugin = plugin;
        this.userRepo = userRepo;
        this.punishmentRepo = punishmentRepo;
        this.staffNoteRepo = staffNoteRepo;
        this.reportRepo = reportRepo;
        this.announcementRepo = announcementRepo;
        this.bindingRepo = bindingRepo;
        this.bindingVerification = bindingVerification;
    }

    @Override
    public String name() {
        return "starx.admin";
    }

    @Override
    public void onEnable() {
        ProxyServer proxy = this.plugin.proxy();
        CommandManager reg = proxy.getCommandManager();
        this.commandMeta = reg.metaBuilder("sxadmin").plugin(this.plugin).build();
        reg.register(this.commandMeta, (Command)new AdminCommand());
    }

    @Override
    public void onDisable() {
        CommandMeta current = this.commandMeta;
        this.commandMeta = null;
        if (current != null) this.plugin.proxy().getCommandManager().unregister(current);
    }

    private final class AdminCommand implements SimpleCommand {
        @Override public void execute(SimpleCommand.Invocation invocation) {
            String[] args = invocation.arguments();
            if (args.length == 0) { invocation.source().sendMessage(Component.text("用法：/sxadmin <report|history|note|notes|announce|bind> ...", NamedTextColor.YELLOW)); return; }
            SimpleCommand delegate = switch (args[0].toLowerCase()) {
                case "report" -> new ReportCommand(); case "history" -> new HistoryCommand();
                case "note" -> new NoteCommand(); case "notes" -> new NotesCommand();
                case "announce" -> new AnnounceCommand(); case "bind" -> new BindCommand(); default -> null;
            };
            if (delegate == null) { invocation.source().sendMessage(Component.text("未知管理操作。可用：report/history/note/notes/announce/bind", NamedTextColor.RED)); return; }
            delegate.execute(new RoutedInvocation(invocation, java.util.Arrays.copyOfRange(args, 1, args.length)));
        }
        @Override public List<String> suggest(SimpleCommand.Invocation invocation) {
            String[] args = invocation.arguments();
            List<String> actions = List.of("report", "history", "note", "notes", "announce", "bind");
            if (args.length <= 1) {
                String prefix = args.length == 0 ? "" : args[0].toLowerCase();
                return actions.stream().filter(action -> action.startsWith(prefix)).toList();
            }
            SimpleCommand delegate = switch (args[0].toLowerCase()) {
                case "report" -> new ReportCommand(); case "history" -> new HistoryCommand();
                case "note" -> new NoteCommand(); case "notes" -> new NotesCommand();
                case "announce" -> new AnnounceCommand(); case "bind" -> new BindCommand(); default -> null;
            };
            return delegate == null ? List.of() : delegate.suggest(
                new RoutedInvocation(invocation, java.util.Arrays.copyOfRange(args, 1, args.length)));
        }
    }

    private record RoutedInvocation(SimpleCommand.Invocation original, String[] arguments) implements SimpleCommand.Invocation {
        @Override public CommandSource source() { return original.source(); }
        @Override public String alias() { return "sxadmin"; }
    }

    private final class ReportCommand
    implements SimpleCommand {
        private ReportCommand() {
        }

        public void execute(SimpleCommand.Invocation inv) {
            if (!inv.source().hasPermission("starx.report")) {
                inv.source().sendMessage((Component)Component.text((String)"你没有权限执行此命令。", (TextColor)NamedTextColor.RED));
                return;
            }
            CommandSource commandSource = inv.source();
            if (!(commandSource instanceof Player)) {
                inv.source().sendMessage((Component)Component.text((String)"此命令只能由玩家执行。", (TextColor)NamedTextColor.RED));
                return;
            }
            Player reporter = (Player)commandSource;
            String[] args = (String[])inv.arguments();
            if (args.length < 2) {
                inv.source().sendMessage((Component)Component.text((String)("用法：/sxadmin report <玩家> <分类> [" + String.join((CharSequence)"/", REPORT_CATEGORIES) + "]"), (TextColor)NamedTextColor.YELLOW));
                return;
            }
            String targetName = args[0];
            String category = args[1].toUpperCase();
            if (!REPORT_CATEGORIES.contains(category)) {
                inv.source().sendMessage((Component)Component.text((String)("无效分类，可选值：" + String.join((CharSequence)", ", REPORT_CATEGORIES)), (TextColor)NamedTextColor.RED));
                return;
            }
            String details = args.length > 2 ? String.join((CharSequence)" ", Arrays.copyOfRange(args, 2, args.length)) : "";
            Optional target = AdminCommandsModule.this.plugin.proxy().getPlayer(targetName);
            if (target.isEmpty()) {
                inv.source().sendMessage((Component)Component.text((String)"未找到该玩家。", (TextColor)NamedTextColor.RED));
                return;
            }
            Report r = new Report(UUID.randomUUID().toString(), reporter.getUniqueId(), ((Player)target.get()).getUniqueId(), category, details, "PENDING", null, null);
            AdminCommandsModule.this.reportRepo.create(r);
            inv.source().sendMessage((Component)Component.text((String)"举报已提交。", (TextColor)NamedTextColor.GREEN));
        }

        public List<String> suggest(SimpleCommand.Invocation inv) {
            String[] args = (String[])inv.arguments();
            if (args.length <= 1) {
                String prefix = args.length == 0 ? "" : args[0].toLowerCase();
                return AdminCommandsModule.this.plugin.proxy().getAllPlayers().stream().map(Player::getUsername).filter(n -> n.toLowerCase().startsWith(prefix)).collect(Collectors.toList());
            }
            if (args.length == 2) {
                String prefix = args[1].toLowerCase();
                return REPORT_CATEGORIES.stream().filter(c -> c.toLowerCase().startsWith(prefix)).collect(Collectors.toList());
            }
            return List.of();
        }
    }

    private final class HistoryCommand
    implements SimpleCommand {
        private HistoryCommand() {
        }

        public void execute(SimpleCommand.Invocation inv) {
            List<Report> list;
            if (!inv.source().hasPermission("starx.history")) {
                inv.source().sendMessage((Component)Component.text((String)"你没有权限执行此命令。", (TextColor)NamedTextColor.RED));
                return;
            }
            String[] args = (String[])inv.arguments();
            if (args.length == 0) {
                inv.source().sendMessage((Component)Component.text((String)"用法：/sxadmin history <玩家>", (TextColor)NamedTextColor.YELLOW));
                return;
            }
            String targetName = args[0];
            Optional target = AdminCommandsModule.this.plugin.proxy().getPlayer(targetName);
            if (target.isEmpty()) {
                inv.source().sendMessage((Component)Component.text((String)"未找到该玩家。", (TextColor)NamedTextColor.RED));
                return;
            }
            UUID uuid = ((Player)target.get()).getUniqueId();
            inv.source().sendMessage((Component)Component.text((String)("==== History: " + targetName + " ===="), (TextColor)NamedTextColor.GOLD));
            List<Punishment> punishments = AdminCommandsModule.this.punishmentRepo.findByPlayer(uuid);
            inv.source().sendMessage((Component)Component.text((String)("处罚记录（" + punishments.size() + "）："), (TextColor)NamedTextColor.AQUA));
            for (Punishment punishment : punishments) {
                inv.source().sendMessage((Component)Component.text((String)("  [" + punishment.type() + "] " + punishment.reason() + " - by " + punishment.staffName()), (TextColor)NamedTextColor.GRAY));
            }
            List<StaffNote> notes = AdminCommandsModule.this.staffNoteRepo.findByPlayer(uuid);
            if (!notes.isEmpty()) {
                inv.source().sendMessage((Component)Component.text((String)"管理备注：", (TextColor)NamedTextColor.AQUA));
                for (StaffNote n : notes) {
                    inv.source().sendMessage((Component)Component.text((String)("  [" + n.severity() + "] " + n.note()), (TextColor)NamedTextColor.GRAY));
                }
            }
            if (!(list = AdminCommandsModule.this.reportRepo.findByTarget(uuid)).isEmpty()) {
                inv.source().sendMessage((Component)Component.text((String)("举报记录（" + list.size() + "）："), (TextColor)NamedTextColor.AQUA));
                for (Report r : list) {
                    inv.source().sendMessage((Component)Component.text((String)("  [" + r.status() + "] " + r.category() + " - " + r.details()), (TextColor)NamedTextColor.GRAY));
                }
            }
        }

        public List<String> suggest(SimpleCommand.Invocation inv) {
            if (((String[])inv.arguments()).length <= 1) {
                String prefix = ((String[])inv.arguments()).length == 0 ? "" : ((String[])inv.arguments())[0].toLowerCase();
                return AdminCommandsModule.this.plugin.proxy().getAllPlayers().stream().map(Player::getUsername).filter(n -> n.toLowerCase().startsWith(prefix)).collect(Collectors.toList());
            }
            return List.of();
        }
    }

    private final class NoteCommand
    implements SimpleCommand {
        private NoteCommand() {
        }

        public void execute(SimpleCommand.Invocation inv) {
            if (!inv.source().hasPermission("starx.note")) {
                inv.source().sendMessage((Component)Component.text((String)"你没有权限执行此命令。", (TextColor)NamedTextColor.RED));
                return;
            }
            CommandSource commandSource = inv.source();
            if (!(commandSource instanceof Player)) {
                inv.source().sendMessage((Component)Component.text((String)"此命令只能由玩家执行。", (TextColor)NamedTextColor.RED));
                return;
            }
            Player staff = (Player)commandSource;
            String[] args = (String[])inv.arguments();
            if (args.length < 2) {
                inv.source().sendMessage((Component)Component.text((String)"用法：/sxadmin note <玩家> <内容> [-s INFO|WARNING|CRITICAL]", (TextColor)NamedTextColor.YELLOW));
                return;
            }
            String targetName = args[0];
            String severity = "INFO";
            int contentEnd = args.length;
            if (args.length >= 4 && "-s".equalsIgnoreCase(args[args.length - 2])) {
                severity = args[args.length - 1].toUpperCase();
                if (!NOTE_SEVERITIES.contains(severity)) {
                    inv.source().sendMessage((Component)Component.text((String)("无效严重级别：" + severity), (TextColor)NamedTextColor.RED));
                    return;
                }
                contentEnd = args.length - 2;
            }
            String content = String.join((CharSequence)" ", Arrays.copyOfRange(args, 1, contentEnd));
            Optional target = AdminCommandsModule.this.plugin.proxy().getPlayer(targetName);
            if (target.isEmpty()) {
                inv.source().sendMessage((Component)Component.text((String)"未找到该玩家。", (TextColor)NamedTextColor.RED));
                return;
            }
            StaffNote note = new StaffNote(UUID.randomUUID().toString(), ((Player)target.get()).getUniqueId(), content, severity, staff.getUniqueId(), System.currentTimeMillis());
            AdminCommandsModule.this.staffNoteRepo.addNote(note);
            inv.source().sendMessage((Component)Component.text((String)"管理备注已添加。", (TextColor)NamedTextColor.GREEN));
        }

        public List<String> suggest(SimpleCommand.Invocation inv) {
            String[] args = (String[])inv.arguments();
            if (args.length <= 1) {
                String prefix = args.length == 0 ? "" : args[0].toLowerCase();
                return AdminCommandsModule.this.plugin.proxy().getAllPlayers().stream().map(Player::getUsername).filter(n -> n.toLowerCase().startsWith(prefix)).collect(Collectors.toList());
            }
            if (args.length >= 2 && "-s".equalsIgnoreCase(args[args.length - 1])) {
                return NOTE_SEVERITIES;
            }
            return List.of();
        }
    }

    private final class NotesCommand
    implements SimpleCommand {
        private NotesCommand() {
        }

        public void execute(SimpleCommand.Invocation inv) {
            if (!inv.source().hasPermission("starx.note.list")) {
                inv.source().sendMessage((Component)Component.text((String)"你没有权限执行此命令。", (TextColor)NamedTextColor.RED));
                return;
            }
            String[] args = (String[])inv.arguments();
            if (args.length == 0) {
                inv.source().sendMessage((Component)Component.text((String)"用法：/sxadmin notes <玩家>", (TextColor)NamedTextColor.YELLOW));
                return;
            }
            String targetName = args[0];
            Optional target = AdminCommandsModule.this.plugin.proxy().getPlayer(targetName);
            if (target.isEmpty()) {
                inv.source().sendMessage((Component)Component.text((String)"未找到该玩家。", (TextColor)NamedTextColor.RED));
                return;
            }
            List<StaffNote> notes = AdminCommandsModule.this.staffNoteRepo.findByPlayer(((Player)target.get()).getUniqueId());
            if (notes.isEmpty()) {
                inv.source().sendMessage((Component)Component.text((String)(targetName + " 暂无管理备注。"), (TextColor)NamedTextColor.GRAY));
                return;
            }
            inv.source().sendMessage((Component)Component.text((String)(targetName + " 的管理备注："), (TextColor)NamedTextColor.GOLD));
            for (StaffNote n : notes) {
                inv.source().sendMessage((Component)Component.text((String)("  [" + n.severity() + "] " + n.note()), (TextColor)NamedTextColor.GRAY));
            }
        }

        public List<String> suggest(SimpleCommand.Invocation inv) {
            String prefix = ((String[])inv.arguments()).length == 0 ? "" : ((String[])inv.arguments())[0].toLowerCase();
            return AdminCommandsModule.this.plugin.proxy().getAllPlayers().stream().map(Player::getUsername).filter(n -> n.toLowerCase().startsWith(prefix)).collect(Collectors.toList());
        }
    }

    private final class AnnounceCommand
    implements SimpleCommand {
        private AnnounceCommand() {
        }

        public void execute(SimpleCommand.Invocation inv) {
            String string;
            if (!inv.source().hasPermission("starx.announce")) {
                inv.source().sendMessage((Component)Component.text((String)"你没有权限执行此命令。", (TextColor)NamedTextColor.RED));
                return;
            }
            String[] args = (String[])inv.arguments();
            if (args.length < 2) {
                inv.source().sendMessage((Component)Component.text((String)"用法：/sxadmin announce <标题> <内容>", (TextColor)NamedTextColor.YELLOW));
                return;
            }
            String title = args[0];
            String content = String.join((CharSequence)" ", Arrays.copyOfRange(args, 1, args.length));
            String string2 = UUID.randomUUID().toString();
            CommandSource commandSource = inv.source();
            if (commandSource instanceof Player) {
                Player p = (Player)commandSource;
                string = p.getUniqueId().toString();
            } else {
                string = "console";
            }
            Announcement a = new Announcement(string2, title, content, string, System.currentTimeMillis(), null);
            AdminCommandsModule.this.announcementRepo.create(a);
            Component msg = Component.text((String)("[" + title + "] "), (TextColor)NamedTextColor.GOLD).append((Component)Component.text((String)content, (TextColor)NamedTextColor.WHITE));
            for (Player player : AdminCommandsModule.this.plugin.proxy().getAllPlayers()) {
                player.sendMessage(msg);
            }
            inv.source().sendMessage((Component)Component.text((String)"公告已发送。", (TextColor)NamedTextColor.GREEN));
        }
    }

    private final class BindCommand
    implements SimpleCommand {
        private BindCommand() {
        }

        public void execute(SimpleCommand.Invocation inv) {
            CommandSource commandSource = inv.source();
            if (!(commandSource instanceof Player)) {
                inv.source().sendMessage((Component)Component.text((String)"此命令只能由玩家执行。", (TextColor)NamedTextColor.RED));
                return;
            }
            Player player = (Player)commandSource;
            String[] args = (String[])inv.arguments();
            if (args.length < 1 || !"qq".equalsIgnoreCase(args[0])) {
                inv.source().sendMessage((Component)Component.text((String)"用法：/sxadmin bind qq", (TextColor)NamedTextColor.YELLOW));
                return;
            }
            UUID uuid = player.getUniqueId();
            Optional<PlayerBinding> existing = AdminCommandsModule.this.bindingRepo.findByPlayer(uuid);
            if (existing.isPresent() && existing.get().qqId() != null) {
                inv.source().sendMessage((Component)Component.text((String)"你的账号已绑定 QQ。", (TextColor)NamedTextColor.RED));
                return;
            }
            String code = AdminCommandsModule.this.bindingVerification.generateCode(uuid);
            inv.source().sendMessage(((TextComponent)((TextComponent)((TextComponent)((TextComponent)((TextComponent)((TextComponent)((TextComponent)Component.text((String)"").append((Component)Component.text((String)"\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500", (TextColor)NamedTextColor.DARK_GRAY))).append((Component)Component.text((String)"\n\u2605 Your verification code: ", (TextColor)NamedTextColor.GREEN))).append((Component)Component.text((String)code, (TextColor)NamedTextColor.AQUA))).append((Component)Component.text((String)" \u2605", (TextColor)NamedTextColor.GREEN))).append((Component)Component.text((String)"\nSend this code to the QQ bot via private message", (TextColor)NamedTextColor.GRAY))).append((Component)Component.text((String)("\nto bind your QQ account to " + player.getUsername() + "."), (TextColor)NamedTextColor.GRAY))).append((Component)Component.text((String)"\nCode expires in 5 minutes.", (TextColor)NamedTextColor.RED))).append((Component)Component.text((String)"\n\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500", (TextColor)NamedTextColor.DARK_GRAY)));
        }

        public List<String> suggest(SimpleCommand.Invocation inv) {
            if (((String[])inv.arguments()).length <= 1) {
                String prefix = ((String[])inv.arguments()).length == 0 ? "" : ((String[])inv.arguments())[0].toLowerCase();
                return List.of("qq").stream().filter(s -> s.startsWith(prefix)).collect(Collectors.toList());
            }
            return List.of();
        }
    }
}
