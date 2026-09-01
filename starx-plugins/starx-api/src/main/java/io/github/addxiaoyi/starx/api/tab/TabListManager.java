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

package io.github.addxiaoyi.starx.api.tab;

import net.kyori.adventure.text.Component;
import java.util.*;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 标签页列表管理器
 * 提供高级的标签页列表管理功能
 */
public interface TabListManager {

    /**
     * 获取全局标签页列表
     * @return 全局标签页列表
     */
    TabList getGlobalTabList();

    /**
     * 获取玩家专属标签页列表
     * @param playerId 玩家ID
     * @return 玩家标签页列表
     */
    TabList getPlayerTabList(String playerId);

    /**
     * 获取或创建玩家标签页列表
     * @param playerId 玩家ID
     * @return 玩家标签页列表
     */
    TabList getOrCreatePlayerTabList(String playerId);

    /**
     * 设置全局标签页列表
     * @param tabList 标签页列表
     */
    void setGlobalTabList(TabList tabList);

    /**
     * 设置玩家标签页列表
     * @param playerId 玩家ID
     * @param tabList 标签页列表
     */
    void setPlayerTabList(String playerId, TabList tabList);

    /**
     * 移除玩家标签页列表
     * @param playerId 玩家ID
     */
    void removePlayerTabList(String playerId);

    /**
     * 设置全局头部
     * @param header 头部组件
     */
    void setGlobalHeader(Component header);

    /**
     * 设置全局尾部
     * @param footer 尾部组件
     */
    void setGlobalFooter(Component footer);

    /**
     * 设置全局头部动画
     * @param animation 动画
     */
    void setGlobalHeaderAnimation(TabAnimation animation);

    /**
     * 设置全局尾部动画
     * @param animation 动画
     */
    void setGlobalFooterAnimation(TabAnimation animation);

    /**
     * 设置全局背景图片
     * @param image 图片
     */
    void setGlobalBackgroundImage(TabImage image);

    /**
     * 设置全局背景颜色
     * @param color 颜色
     */
    void setGlobalBackgroundColor(String color);

    /**
     * 为玩家设置自定义头部
     * @param playerId 玩家ID
     * @param header 头部组件
     */
    void setPlayerHeader(String playerId, Component header);

    /**
     * 为玩家设置自定义尾部
     * @param playerId 玩家ID
     * @param footer 尾部组件
     */
    void setPlayerFooter(String playerId, Component footer);

    /**
     * 为玩家设置自定义头部动画
     * @param playerId 玩家ID
     * @param animation 动画
     */
    void setPlayerHeaderAnimation(String playerId, TabAnimation animation);

    /**
     * 为玩家设置自定义尾部动画
     * @param playerId 玩家ID
     * @param animation 动画
     */
    void setPlayerFooterAnimation(String playerId, TabAnimation animation);

    /**
     * 为玩家设置自定义背景图片
     * @param playerId 玩家ID
     * @param image 图片
     */
    void setPlayerBackgroundImage(String playerId, TabImage image);

    /**
     * 为玩家设置自定义背景颜色
     * @param playerId 玩家ID
     * @param color 颜色
     */
    void setPlayerBackgroundColor(String playerId, String color);

    /**
     * 设置玩家条目
     * @param playerId 玩家ID
     * @param entry 条目
     */
    void setPlayerEntry(String playerId, TabList.TabPlayerEntry entry);

    /**
     * 设置玩家条目（异步）
     * @param playerId 玩家ID
     * @param entryFuture 条目Future
     */
    CompletableFuture<Void> setPlayerEntryAsync(String playerId, CompletableFuture<TabList.TabPlayerEntry> entryFuture);

    /**
     * 移除玩家条目
     * @param playerId 玩家ID
     */
    void removePlayerEntry(String playerId);

    /**
     * 添加玩家加入监听器
     * @param listener 监听器
     */
    void addPlayerJoinListener(Consumer<String> listener);

    /**
     * 添加玩家离开监听器
     * @param listener 监听器
     */
    void addPlayerQuitListener(Consumer<String> listener);

    /**
     * 添加玩家切换监听器
     * @param listener 监听器
     */
    void addPlayerSwitchListener(Consumer<String> listener);

    /**
     * 移除玩家加入监听器
     * @param listener 监听器
     */
    void removePlayerJoinListener(Consumer<String> listener);

    /**
     * 移除玩家离开监听器
     * @param listener 监听器
     */
    void removePlayerQuitListener(Consumer<String> listener);

    /**
     * 移除玩家切换监听器
     * @param listener 监听器
     */
    void removePlayerSwitchListener(Consumer<String> listener);

    /**
     * 获取所有玩家ID
     * @return 玩家ID集合
     */
    Set<String> getPlayerIds();

    /**
     * 获取玩家数量
     * @return 玩家数量
     */
    int getPlayerCount();

    /**
     * 刷新所有标签页列表
     */
    void refreshAll();

    /**
     * 刷新指定玩家的标签页列表
     * @param playerId 玩家ID
     */
    void refreshPlayer(String playerId);

    /**
     * 判断全局或任意玩家专属列表是否需要高频动画刷新。
     *
     * @return 存在动画时返回 {@code true}
     */
    default boolean hasAnimations() {
        return getGlobalTabList() != null && getGlobalTabList().hasAnimations();
    }

    /**
     * 清除所有玩家数据
     */
    void clear();

    /**
     * 销毁管理器
     */
    void destroy();

    /**
     * 创建默认管理器
     * @return 默认管理器
     */
    static TabListManager create() {
        return new DefaultTabListManager();
    }

    /**
     * 创建默认管理器
     * @param globalTabList 全局标签页列表
     * @return 默认管理器
     */
    static TabListManager create(TabList globalTabList) {
        return new DefaultTabListManager(globalTabList);
    }

    /**
     * 默认管理器实现
     */
    class DefaultTabListManager implements TabListManager {

        private TabList globalTabList;
        private final Map<String, TabList> playerTabLists = new ConcurrentHashMap<>();
        private final Map<String, TabList.TabPlayerEntry> playerEntries = new ConcurrentHashMap<>();
        
        private final List<Consumer<String>> joinListeners = new ArrayList<>();
        private final List<Consumer<String>> quitListeners = new ArrayList<>();
        private final List<Consumer<String>> switchListeners = new ArrayList<>();

        DefaultTabListManager() {
            this.globalTabList = DefaultTabList.builder().build();
        }

        DefaultTabListManager(TabList globalTabList) {
            this.globalTabList = Objects.requireNonNull(globalTabList, "globalTabList");
        }

        @Override
        public TabList getGlobalTabList() {
            return globalTabList;
        }

        @Override
        public TabList getPlayerTabList(String playerId) {
            Objects.requireNonNull(playerId, "playerId");
            return playerTabLists.get(playerId);
        }

        @Override
        public TabList getOrCreatePlayerTabList(String playerId) {
            Objects.requireNonNull(playerId, "playerId");
            return playerTabLists.computeIfAbsent(playerId, k -> DefaultTabList.builder().build());
        }

        @Override
        public void setGlobalTabList(TabList tabList) {
            this.globalTabList = Objects.requireNonNull(tabList, "tabList");
        }

        @Override
        public void setPlayerTabList(String playerId, TabList tabList) {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(tabList, "tabList");
            playerTabLists.put(playerId, tabList);
        }

        @Override
        public void removePlayerTabList(String playerId) {
            Objects.requireNonNull(playerId, "playerId");
            TabList removed = playerTabLists.remove(playerId);
            if (removed != null) {
                removed.destroy();
            }
        }

        @Override
        public void setGlobalHeader(Component header) {
            globalTabList.setHeader(header);
        }

        @Override
        public void setGlobalFooter(Component footer) {
            globalTabList.setFooter(footer);
        }

        @Override
        public void setGlobalHeaderAnimation(TabAnimation animation) {
            globalTabList.setHeaderAnimation(animation);
        }

        @Override
        public void setGlobalFooterAnimation(TabAnimation animation) {
            globalTabList.setFooterAnimation(animation);
        }

        @Override
        public void setGlobalBackgroundImage(TabImage image) {
            globalTabList.setBackgroundImage(image);
        }

        @Override
        public void setGlobalBackgroundColor(String color) {
            globalTabList.setBackgroundColor(color);
        }

        @Override
        public void setPlayerHeader(String playerId, Component header) {
            getOrCreatePlayerTabList(playerId).setHeader(header);
        }

        @Override
        public void setPlayerFooter(String playerId, Component footer) {
            getOrCreatePlayerTabList(playerId).setFooter(footer);
        }

        @Override
        public void setPlayerHeaderAnimation(String playerId, TabAnimation animation) {
            getOrCreatePlayerTabList(playerId).setHeaderAnimation(animation);
        }

        @Override
        public void setPlayerFooterAnimation(String playerId, TabAnimation animation) {
            getOrCreatePlayerTabList(playerId).setFooterAnimation(animation);
        }

        @Override
        public void setPlayerBackgroundImage(String playerId, TabImage image) {
            getOrCreatePlayerTabList(playerId).setBackgroundImage(image);
        }

        @Override
        public void setPlayerBackgroundColor(String playerId, String color) {
            getOrCreatePlayerTabList(playerId).setBackgroundColor(color);
        }

        @Override
        public void setPlayerEntry(String playerId, TabList.TabPlayerEntry entry) {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(entry, "entry");
            playerEntries.put(playerId, entry);
            
            // 更新全局标签页列表
            globalTabList.setPlayerEntry(playerId, entry);
            
            // 更新玩家专属标签页列表
            TabList playerTabList = playerTabLists.get(playerId);
            if (playerTabList != null) {
                playerTabList.setPlayerEntry(playerId, entry);
            }
        }

        @Override
        public CompletableFuture<Void> setPlayerEntryAsync(String playerId, CompletableFuture<TabList.TabPlayerEntry> entryFuture) {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(entryFuture, "entryFuture");
            
            return entryFuture.thenAccept(entry -> setPlayerEntry(playerId, entry));
        }

        @Override
        public void removePlayerEntry(String playerId) {
            Objects.requireNonNull(playerId, "playerId");
            playerEntries.remove(playerId);
            globalTabList.removePlayerEntry(playerId);
            
            TabList playerTabList = playerTabLists.get(playerId);
            if (playerTabList != null) {
                playerTabList.removePlayerEntry(playerId);
            }
        }

        @Override
        public void addPlayerJoinListener(Consumer<String> listener) {
            Objects.requireNonNull(listener, "listener");
            joinListeners.add(listener);
        }

        @Override
        public void addPlayerQuitListener(Consumer<String> listener) {
            Objects.requireNonNull(listener, "listener");
            quitListeners.add(listener);
        }

        @Override
        public void addPlayerSwitchListener(Consumer<String> listener) {
            Objects.requireNonNull(listener, "listener");
            switchListeners.add(listener);
        }

        @Override
        public void removePlayerJoinListener(Consumer<String> listener) {
            Objects.requireNonNull(listener, "listener");
            joinListeners.remove(listener);
        }

        @Override
        public void removePlayerQuitListener(Consumer<String> listener) {
            Objects.requireNonNull(listener, "listener");
            quitListeners.remove(listener);
        }

        @Override
        public void removePlayerSwitchListener(Consumer<String> listener) {
            Objects.requireNonNull(listener, "listener");
            switchListeners.remove(listener);
        }

        @Override
        public Set<String> getPlayerIds() {
            return Set.copyOf(playerEntries.keySet());
        }

        @Override
        public int getPlayerCount() {
            return playerEntries.size();
        }

        @Override
        public void refreshAll() {
            globalTabList.refresh();
            for (TabList tabList : playerTabLists.values()) {
                tabList.refresh();
            }
        }

        @Override
        public void refreshPlayer(String playerId) {
            Objects.requireNonNull(playerId, "playerId");
            TabList playerTabList = playerTabLists.get(playerId);
            if (playerTabList != null) {
                playerTabList.refresh();
            }
        }

        @Override
        public boolean hasAnimations() {
            if (globalTabList != null && globalTabList.hasAnimations()) {
                return true;
            }
            for (TabList tabList : playerTabLists.values()) {
                if (tabList.hasAnimations()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void clear() {
            playerEntries.clear();
            for (TabList tabList : playerTabLists.values()) {
                tabList.clearPlayers();
                tabList.destroy();
            }
            playerTabLists.clear();
        }

        @Override
        public void destroy() {
            clear();
            if (globalTabList != null) {
                globalTabList.destroy();
                globalTabList = null;
            }
            joinListeners.clear();
            quitListeners.clear();
            switchListeners.clear();
        }

        /**
         * 触发玩家加入事件
         * @param playerId 玩家ID
         */
        public void firePlayerJoin(String playerId) {
            Objects.requireNonNull(playerId, "playerId");
            for (Consumer<String> listener : joinListeners) {
                try {
                    listener.accept(playerId);
                } catch (Exception e) {
                    // 记录错误但不中断其他监听器
                }
            }
        }

        /**
         * 触发玩家离开事件
         * @param playerId 玩家ID
         */
        public void firePlayerQuit(String playerId) {
            Objects.requireNonNull(playerId, "playerId");
            for (Consumer<String> listener : quitListeners) {
                try {
                    listener.accept(playerId);
                } catch (Exception e) {
                    // 记录错误但不中断其他监听器
                }
            }
        }

        /**
         * 触发玩家切换事件
         * @param playerId 玩家ID
         */
        public void firePlayerSwitch(String playerId) {
            Objects.requireNonNull(playerId, "playerId");
            for (Consumer<String> listener : switchListeners) {
                try {
                    listener.accept(playerId);
                } catch (Exception e) {
                    // 记录错误但不中断其他监听器
                }
            }
        }
    }
}
