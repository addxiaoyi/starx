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
 *  net.kyori.adventure.text.BuildableComponent
 *  net.kyori.adventure.text.Component
 *  net.kyori.adventure.text.TextComponent$Builder
 *  net.kyori.adventure.text.format.NamedTextColor
 *  net.kyori.adventure.text.format.TextColor
 */
package io.github.addxiaoyi.starx.velocity.module.vote;

import com.velocitypowered.api.command.Command;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.addxiaoyi.starx.common.database.JdbcVoteRepository;
import io.github.addxiaoyi.starx.common.database.VoteAlreadyCastException;
import io.github.addxiaoyi.starx.common.model.StaffVote;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.kyori.adventure.text.BuildableComponent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

public final class VoteModule
implements VelocityModule {
    private static final long VOTE_DURATION_MS = 120000L;
    private final StarxVelocityPlugin plugin;
    private final JdbcVoteRepository voteRepo;
    private final Function<UUID, UUID> canonicalUuidResolver;
    private final Function<UUID, Set<UUID>> knownMinecraftUuidsResolver;
    private CommandMeta commandMeta;

    public VoteModule(StarxVelocityPlugin plugin, JdbcVoteRepository voteRepo) {
        this(plugin, voteRepo, Function.identity(), Set::of);
    }

    public VoteModule(
        StarxVelocityPlugin plugin,
        JdbcVoteRepository voteRepo,
        Function<UUID, UUID> canonicalUuidResolver,
        Function<UUID, Set<UUID>> knownMinecraftUuidsResolver) {
        this.plugin = plugin;
        this.voteRepo = voteRepo;
        this.canonicalUuidResolver = Objects.requireNonNull(canonicalUuidResolver, "canonicalUuidResolver");
        this.knownMinecraftUuidsResolver = Objects.requireNonNull(
            knownMinecraftUuidsResolver, "knownMinecraftUuidsResolver");
    }

    @Override
    public String name() {
        return "starx.vote";
    }

    @Override
    public void onEnable() {
        ProxyServer proxy = this.plugin.proxy();
        CommandManager reg = proxy.getCommandManager();
        this.commandMeta = reg.metaBuilder("sxvote").plugin(this.plugin).build();
        reg.register(this.commandMeta, (Command)new VoteCastCommand());
    }

    @Override
    public void onDisable() {
        CommandMeta current = this.commandMeta;
        this.commandMeta = null;
        if (current != null) {
            this.plugin.proxy().getCommandManager().unregister(current);
        }
    }

    private final class VoteStartCommand
    implements SimpleCommand {
        private VoteStartCommand() {
        }

        public void execute(SimpleCommand.Invocation inv) {
            executeStart(inv, (String[]) inv.arguments());
        }

        private void executeStart(SimpleCommand.Invocation inv, String[] args) {
            if (!inv.source().hasPermission("starx.vote.start")) {
                inv.source().sendMessage((Component)Component.text((String)"你没有权限执行此命令。", (TextColor)NamedTextColor.RED));
                return;
            }
            CommandSource commandSource = inv.source();
            if (!(commandSource instanceof Player)) {
                inv.source().sendMessage((Component)Component.text((String)"此命令只能由玩家执行。", (TextColor)NamedTextColor.RED));
                return;
            }
            Player staff = (Player)commandSource;
            if (args.length < 2) {
                inv.source().sendMessage((Component)Component.text((String)"用法：/sxvote start <玩家> <原因>", (TextColor)NamedTextColor.YELLOW));
                return;
            }
            Optional<StaffVote> active = VoteModule.this.voteRepo.findActive();
            if (active.isPresent()) {
                inv.source().sendMessage((Component)Component.text((String)"当前已有正在进行的投票。", (TextColor)NamedTextColor.RED));
                return;
            }
            String targetName = args[0];
            String reason = String.join((CharSequence)" ", Arrays.copyOfRange(args, 1, args.length));
            Optional target = VoteModule.this.plugin.proxy().getPlayer(targetName);
            if (target.isEmpty()) {
                inv.source().sendMessage((Component)Component.text((String)"未找到该玩家。", (TextColor)NamedTextColor.RED));
                return;
            }
            UUID targetUuid = VoteModule.this.canonicalUuidResolver.apply(((Player)target.get()).getUniqueId());
            UUID initiatorUuid = VoteModule.this.canonicalUuidResolver.apply(staff.getUniqueId());
            StaffVote vote = new StaffVote(UUID.randomUUID().toString(), targetUuid, targetName, reason, "STAFF_VOTE", "ACTIVE", initiatorUuid, staff.getUsername(), 0, 0, 3, System.currentTimeMillis() + 120000L, System.currentTimeMillis(), null);
            VoteModule.this.voteRepo.create(vote);
            BuildableComponent msg = ((TextComponent.Builder)((TextComponent.Builder)((TextComponent.Builder)((TextComponent.Builder)((TextComponent.Builder)((TextComponent.Builder)((TextComponent.Builder)((TextComponent.Builder)((TextComponent.Builder)((TextComponent.Builder)((TextComponent.Builder)((TextComponent.Builder)((TextComponent.Builder)Component.text().append((Component)Component.text((String)"\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500", (TextColor)NamedTextColor.DARK_GRAY))).append((Component)Component.text((String)"\n[投票] ", (TextColor)NamedTextColor.GOLD))).append((Component)Component.text((String)staff.getUsername(), (TextColor)NamedTextColor.YELLOW))).append((Component)Component.text((String)" 发起了针对 ", (TextColor)NamedTextColor.WHITE))).append((Component)Component.text((String)targetName, (TextColor)NamedTextColor.RED))).append((Component)Component.text((String)" 的投票：", (TextColor)NamedTextColor.WHITE))).append((Component)Component.text((String)reason, (TextColor)NamedTextColor.GRAY))).append((Component)Component.text((String)"\n请输入 ", (TextColor)NamedTextColor.WHITE))).append((Component)Component.text((String)"/sxvote yes", (TextColor)NamedTextColor.GREEN))).append((Component)Component.text((String)" 或 ", (TextColor)NamedTextColor.WHITE))).append((Component)Component.text((String)"/sxvote no", (TextColor)NamedTextColor.RED))).append((Component)Component.text((String)" 参与投票。", (TextColor)NamedTextColor.WHITE))).append((Component)Component.text((String)"\n\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500", (TextColor)NamedTextColor.DARK_GRAY))).build();
            for (Player p : VoteModule.this.plugin.proxy().getAllPlayers()) {
                if (!p.hasPermission("starx.vote.cast")) continue;
                p.sendMessage((Component)msg);
            }
        }

        public List<String> suggest(SimpleCommand.Invocation inv) {
            if (((String[])inv.arguments()).length <= 1) {
                String prefix = ((String[])inv.arguments()).length == 0 ? "" : ((String[])inv.arguments())[0].toLowerCase();
                return VoteModule.this.plugin.proxy().getAllPlayers().stream().map(Player::getUsername).filter(n -> n.toLowerCase().startsWith(prefix)).collect(Collectors.toList());
            }
            return List.of();
        }
    }

    private final class VoteCastCommand
    implements SimpleCommand {
        private VoteCastCommand() {
        }

        public void execute(SimpleCommand.Invocation inv) {
            String[] raw = (String[])inv.arguments();
            if (raw.length > 0 && "start".equalsIgnoreCase(raw[0])) {
                new VoteStartCommand().executeStart(inv, Arrays.copyOfRange(raw, 1, raw.length));
                return;
            }
            if (raw.length > 0 && "info".equalsIgnoreCase(raw[0])) {
                new VoteInfoCommand().executeInfo(inv);
                return;
            }
            StaffVote current;
            if (!inv.source().hasPermission("starx.vote.cast")) {
                inv.source().sendMessage((Component)Component.text((String)"你没有权限执行此命令。", (TextColor)NamedTextColor.RED));
                return;
            }
            CommandSource commandSource = inv.source();
            if (!(commandSource instanceof Player)) {
                inv.source().sendMessage((Component)Component.text((String)"此命令只能由玩家执行。", (TextColor)NamedTextColor.RED));
                return;
            }
            Player voter = (Player)commandSource;
            String[] args = raw;
            if (args.length == 0) {
                inv.source().sendMessage((Component)Component.text((String)"用法：/sxvote <yes|no>", (TextColor)NamedTextColor.YELLOW));
                return;
            }
            String choice = args[0].toLowerCase();
            if (!"yes".equals(choice) && !"no".equals(choice)) {
                inv.source().sendMessage((Component)Component.text((String)"请使用 yes 或 no 投票。", (TextColor)NamedTextColor.RED));
                return;
            }
            Optional<StaffVote> active = VoteModule.this.voteRepo.findActive();
            if (active.isEmpty()) {
                inv.source().sendMessage((Component)Component.text((String)"当前没有进行中的投票。", (TextColor)NamedTextColor.RED));
                return;
            }
            StaffVote vote = active.get();
            UUID voterUuid = voter.getUniqueId();
            Set<UUID> knownUuids = Objects.requireNonNull(
                VoteModule.this.knownMinecraftUuidsResolver.apply(voterUuid),
                "knownMinecraftUuidsResolver returned null");
            if (VoteModule.this.voteRepo.hasVoted(vote.id(), knownUuids)) {
                inv.source().sendMessage((Component)Component.text((String)"你已经投过票了。", (TextColor)NamedTextColor.RED));
                return;
            }
            boolean yes = "yes".equals(choice);
            UUID canonicalUuid = Objects.requireNonNull(
                VoteModule.this.canonicalUuidResolver.apply(voterUuid),
                "canonicalUuidResolver returned null");
            try {
                VoteModule.this.voteRepo.castVote(vote.id(), canonicalUuid, yes);
            } catch (VoteAlreadyCastException error) {
                inv.source().sendMessage((Component)Component.text((String)"你已经投过票了。", (TextColor)NamedTextColor.RED));
                return;
            }
            inv.source().sendMessage((Component)Component.text((String)("投票成功：" + choice.toUpperCase()), (TextColor)NamedTextColor.GREEN));
            int yesCount = VoteModule.this.voteRepo.countYes(vote.id());
            Optional<StaffVote> updated = VoteModule.this.voteRepo.findById(vote.id());
            if (updated.isPresent() && yesCount >= (current = updated.get()).requiredYes()) {
                VoteModule.this.voteRepo.updateStatus(current.id(), "PASSED", System.currentTimeMillis());
                BuildableComponent result = ((TextComponent.Builder)((TextComponent.Builder)((TextComponent.Builder)((TextComponent.Builder)((TextComponent.Builder)Component.text().append((Component)Component.text((String)"[投票] ", (TextColor)NamedTextColor.GOLD))).append((Component)Component.text((String)"投票已通过，目标：", (TextColor)NamedTextColor.GREEN))).append((Component)Component.text((String)current.targetName(), (TextColor)NamedTextColor.RED))).append((Component)Component.text((String)"，原因：", (TextColor)NamedTextColor.WHITE))).append((Component)Component.text((String)current.reason(), (TextColor)NamedTextColor.GRAY))).build();
                for (Player p : VoteModule.this.plugin.proxy().getAllPlayers()) {
                    if (!p.hasPermission("starx.vote.cast")) continue;
                    p.sendMessage((Component)result);
                }
            }
        }

        public List<String> suggest(SimpleCommand.Invocation inv) {
            String[] args = (String[]) inv.arguments();
            if (args.length <= 1) {
                String prefix = args.length == 0 ? "" : args[0].toLowerCase();
                return List.of("start", "info", "yes", "no").stream()
                    .filter(s -> s.startsWith(prefix)).collect(Collectors.toList());
            }
            if ("start".equalsIgnoreCase(args[0]) && args.length == 2) {
                String prefix = args[1].toLowerCase();
                return VoteModule.this.plugin.proxy().getAllPlayers().stream().map(Player::getUsername)
                    .filter(n -> n.toLowerCase().startsWith(prefix)).collect(Collectors.toList());
            }
            return List.of();
        }
    }

    private final class VoteInfoCommand
    implements SimpleCommand {
        private VoteInfoCommand() {
        }

        public void execute(SimpleCommand.Invocation inv) {
            executeInfo(inv);
        }

        private void executeInfo(SimpleCommand.Invocation inv) {
            if (!inv.source().hasPermission("starx.vote.cast")) {
                inv.source().sendMessage((Component)Component.text((String)"你没有权限执行此命令。", (TextColor)NamedTextColor.RED));
                return;
            }
            Optional<StaffVote> active = VoteModule.this.voteRepo.findActive();
            if (active.isEmpty()) {
                inv.source().sendMessage((Component)Component.text((String)"当前没有进行中的投票。", (TextColor)NamedTextColor.GRAY));
                return;
            }
            StaffVote vote = active.get();
            long remaining = Math.max(0L, vote.expiresAt() - System.currentTimeMillis()) / 1000L;
            inv.source().sendMessage((Component)Component.text((String)"==== 当前投票 ====", (TextColor)NamedTextColor.GOLD));
            inv.source().sendMessage((Component)Component.text((String)("目标：" + vote.targetName()), (TextColor)NamedTextColor.WHITE));
            inv.source().sendMessage((Component)Component.text((String)("原因：" + vote.reason()), (TextColor)NamedTextColor.GRAY));
            inv.source().sendMessage(Component.text((String)("赞成：" + vote.yesVotes()), (TextColor)NamedTextColor.GREEN).append((Component)Component.text((String)(" | 反对：" + vote.noVotes()), (TextColor)NamedTextColor.RED)));
            inv.source().sendMessage((Component)Component.text((String)("通过所需票数：" + vote.requiredYes()), (TextColor)NamedTextColor.YELLOW));
            inv.source().sendMessage((Component)Component.text((String)("剩余时间：" + remaining + " 秒"), (TextColor)NamedTextColor.AQUA));
        }
    }
}
