/*
 * Copyright (C) 2021 - 2025 Elytrium
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.github.addxiaoyi.starx.velocity.module.tab;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.scheduler.ScheduledTask;
import io.github.addxiaoyi.starx.api.tab.DefaultTabList;
import io.github.addxiaoyi.starx.api.tab.TabAnimation;
import io.github.addxiaoyi.starx.api.tab.TabImage;
import io.github.addxiaoyi.starx.api.tab.TabList;
import io.github.addxiaoyi.starx.api.tab.TabListManager;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.kyori.adventure.text.Component;

/**
 * 标签页列表模块
 * 支持动画和图片嵌入的增强版标签列表
 */
public class TabListModule implements VelocityModule {

    private static final long STATIC_REFRESH_INTERVAL_NANOS = Duration.ofSeconds(1).toNanos();

    private final StarxVelocityPlugin plugin;
    private final TabListManager tabListManager;
    private final ConcurrentMap<UUID, ScheduledTask> playerTasks = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, TabContent> lastSentContent = new ConcurrentHashMap<>();
    private ScheduledTask globalRefreshTask;
    private volatile long nextStaticRefreshAt;

    public TabListModule(StarxVelocityPlugin plugin) {
        this.plugin = plugin;
        this.tabListManager = TabListManager.create();
    }

    @Override
    public String name() {
        return "starx.tab-list";
    }

    @Override
    public void onEnable() {
        // 启动全局刷新任务
        this.globalRefreshTask = this.plugin.proxy().getScheduler()
            .buildTask(this.plugin, this::refreshAll)
            .repeat(Duration.ofMillis(50))
            .schedule();

        // 监听玩家事件
        this.plugin.proxy().getEventManager().register(this.plugin, this.playerListener);
        
        this.plugin.logger().info("增强版标签页列表模块已启用");
    }

    @Override
    public void onDisable() {
        ScheduledTask task = this.globalRefreshTask;
        this.globalRefreshTask = null;
        if (task != null) {
            task.cancel();
        }

        for (ScheduledTask playerTask : this.playerTasks.values()) {
            playerTask.cancel();
        }
        this.playerTasks.clear();
        this.lastSentContent.clear();

        this.plugin.proxy().getEventManager().unregisterListener(this.plugin, this.playerListener);

        for (Player player : this.plugin.proxy().getAllPlayers()) {
            player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
        }

        this.tabListManager.destroy();
        this.plugin.logger().info("增强版标签页列表模块已禁用");
    }

    /**
     * 刷新所有玩家
     */
    public void refreshAll() {
        try {
            if (!this.tabListManager.hasAnimations() && !shouldRefreshStaticContent()) {
                return;
            }
            TabContent globalContent = contentFor(this.tabListManager.getGlobalTabList());
            for (Player player : this.plugin.proxy().getAllPlayers()) {
                TabList playerTabList = this.tabListManager.getPlayerTabList(
                    player.getUniqueId().toString());
                sendIfChanged(player, playerTabList == null ? globalContent : contentFor(playerTabList));
            }
        } catch (Exception e) {
            this.plugin.logger().log(java.util.logging.Level.WARNING, "刷新标签列表时出错", e);
        }
    }

    /**
     * 刷新单个玩家
     */
    public void refreshPlayer(Player player) {
        try {
            UUID playerId = player.getUniqueId();
            TabList tabList = this.tabListManager.getPlayerTabList(playerId.toString());
            final TabList effectiveTabList = tabList != null ? tabList : this.tabListManager.getGlobalTabList();
            if (effectiveTabList == null) {
                return;
            }

            sendIfChanged(player, contentFor(effectiveTabList));
        } catch (Exception e) {
            this.plugin.logger().log(java.util.logging.Level.WARNING, 
                "无法刷新玩家 " + player.getUsername() + " 的标签列表", e);
        }
    }

    /**
     * 获取标签页列表管理器
     */
    public TabListManager getTabListManager() {
        return tabListManager;
    }

    /**
     * 创建默认标签页列表
     */
    public DefaultTabList createDefaultTabList() {
        return DefaultTabList.builder()
            .header(Component.text("§6§l★ §fStarX §6§l★"))
            .footer(Component.text("§7当前在线: §f" + this.plugin.proxy().getPlayerCount()))
            .build();
    }

    /**
     * 设置玩家条目
     */
    public void setPlayerEntry(UUID playerId, TabList.TabPlayerEntry entry) {
        this.tabListManager.setPlayerEntry(playerId.toString(), entry);
    }

    /**
     * 移除玩家条目
     */
    public void removePlayerEntry(UUID playerId) {
        this.tabListManager.removePlayerEntry(playerId.toString());
    }

    private static TabContent contentFor(TabList tabList) {
        if (tabList == null) {
            return TabContent.empty();
        }
        return new TabContent(tabList.getHeader(), tabList.getFooter());
    }

    private void sendIfChanged(Player player, TabContent content) {
        TabContent previous = this.lastSentContent.put(player.getUniqueId(), content);
        if (content.equals(previous)) {
            return;
        }
        content.header().ifPresentOrElse(
            header -> content.footer().ifPresentOrElse(
                footer -> player.sendPlayerListHeaderAndFooter(header, footer),
                () -> player.sendPlayerListHeader(header)),
            () -> content.footer().ifPresent(player::sendPlayerListFooter));
    }

    private record TabContent(Optional<Component> header, Optional<Component> footer) {
        private TabContent {
            header = header == null ? Optional.empty() : header;
            footer = footer == null ? Optional.empty() : footer;
        }

        private static TabContent empty() {
            return new TabContent(Optional.empty(), Optional.empty());
        }
    }

    private boolean shouldRefreshStaticContent() {
        long now = System.nanoTime();
        long next = this.nextStaticRefreshAt;
        if (now < next) {
            return false;
        }
        this.nextStaticRefreshAt = now + STATIC_REFRESH_INTERVAL_NANOS;
        return true;
    }

    /**
     * 监听器类
     */
    private final Object playerListener = new Object() {

        @com.velocitypowered.api.event.Subscribe
        public void onPostLogin(com.velocitypowered.api.event.connection.PostLoginEvent event) {
            Player player = event.getPlayer();
            if (player == null) return;
            UUID playerId = player.getUniqueId();
            
            // 延迟刷新以确保登录完成
            ScheduledTask task = TabListModule.this.plugin.proxy().getScheduler()
                .buildTask(TabListModule.this.plugin, () -> {
                    refreshPlayer(player);
                    TabListModule.this.playerTasks.remove(playerId);
                })
                .delay(Duration.ofSeconds(1))
                .schedule();
            
            TabListModule.this.playerTasks.put(playerId, task);
        }

        @com.velocitypowered.api.event.Subscribe
        public void onDisconnect(com.velocitypowered.api.event.connection.DisconnectEvent event) {
            Player player = event.getPlayer();
            if (player == null) return;
            UUID playerId = player.getUniqueId();
            
            ScheduledTask task = TabListModule.this.playerTasks.remove(playerId);
            if (task != null) {
                task.cancel();
            }
            
            TabListModule.this.tabListManager.removePlayerTabList(playerId.toString());
            TabListModule.this.lastSentContent.remove(playerId);
        }
    };
}
