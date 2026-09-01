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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 标签页列表API
 * 支持动画、图片嵌入和高度自定义
 */
public interface TabList {

    /**
     * 设置表头
     * @param header 表头组件
     */
    void setHeader(Component header);

    /**
     * 设置表头动画
     * @param animation 表头动画
     */
    void setHeaderAnimation(TabAnimation animation);

    /**
     * 设置表尾
     * @param footer 表尾组件
     */
    void setFooter(Component footer);

    /**
     * 设置表尾动画
     * @param animation 表尾动画
     */
    void setFooterAnimation(TabAnimation animation);

    /**
     * 设置背景图片
     * @param image 背景图片
     */
    void setBackgroundImage(TabImage image);

    /**
     * 设置背景颜色
     * @param color 背景颜色
     */
    void setBackgroundColor(String color);

    /**
     * 设置玩家条目
     * @param playerId 玩家ID
     * @param entry 条目
     */
    void setPlayerEntry(String playerId, TabPlayerEntry entry);

    /**
     * 移除玩家条目
     * @param playerId 玩家ID
     */
    void removePlayerEntry(String playerId);

    /**
     * 清除所有玩家条目
     */
    void clearPlayers();

    /**
     * 获取当前表头
     * @return 表头组件
     */
    Optional<Component> getHeader();

    /**
     * 获取当前表尾
     * @return 表尾组件
     */
    Optional<Component> getFooter();

    /**
     * 当前列表是否包含会随时间变化的内容。
     *
     * @return 存在动画时返回 {@code true}
     */
    default boolean hasAnimations() {
        return false;
    }

    /**
     * 获取玩家条目
     * @param playerId 玩家ID
     * @return 玩家条目
     */
    Optional<TabPlayerEntry> getPlayerEntry(String playerId);

    /**
     * 获取所有玩家条目
     * @return 条目映射
     */
    Map<String, TabPlayerEntry> getAllEntries();

    /**
     * 刷新显示
     */
    void refresh();

    /**
     * 销毁资源
     */
    void destroy();

    /**
     * 玩家条目接口
     */
    interface TabPlayerEntry {

        /**
         * 获取显示名称
         * @return 显示名称组件
         */
        Component displayName();

        /**
         * 获取前缀
         * @return 前缀组件
         */
        Optional<Component> prefix();

        /**
         * 获取后缀
         * @return 后缀组件
         */
        Optional<Component> suffix();

        /**
         * 获取延迟
         * @return 延迟（毫秒）
         */
        int latency();

        /**
         * 获取游戏模式
         * @return 游戏模式
         */
        String gameMode();

        /**
         * 获取头像图片
         * @return 头像图片
         */
        Optional<TabImage> avatar();

        /**
         * 获取背景颜色
         * @return 背景颜色
         */
        Optional<String> backgroundColor();

        /**
         * 获取显示的玩家列表顺序
         * @return 排序值
         */
        int sortOrder();

        /**
         * 创建玩家条目
         * @param displayName 显示名称
         * @return 条目构建器
         */
        static Builder builder(Component displayName) {
            return new Builder(displayName);
        }

        /**
         * 条目构建器
         */
        class Builder {
            private final Component displayName;
            private Component prefix = Component.empty();
            private Component suffix = Component.empty();
            private int latency = 0;
            private String gameMode = "SURVIVAL";
            private TabImage avatarImage = null;
            private String bgColor = null;
            private int sortOrder = 0;

            Builder(Component displayName) {
                this.displayName = Objects.requireNonNull(displayName, "displayName");
            }

            public Builder prefix(Component prefix) {
                this.prefix = prefix;
                return this;
            }

            public Builder suffix(Component suffix) {
                this.suffix = suffix;
                return this;
            }

            public Builder latency(int latency) {
                this.latency = latency;
                return this;
            }

            public Builder gameMode(String gameMode) {
                this.gameMode = gameMode;
                return this;
            }

            public Builder avatar(TabImage avatar) {
                this.avatarImage = avatar;
                return this;
            }

            public Builder backgroundColor(String color) {
                this.bgColor = color;
                return this;
            }

            public Builder sortOrder(int sortOrder) {
                this.sortOrder = sortOrder;
                return this;
            }

            public TabPlayerEntry build() {
                return new BuiltEntry(
                    displayName, prefix, suffix, latency, gameMode, avatarImage, bgColor, sortOrder
                );
            }
        }

        /**
         * 具体条目实现
         */
        static final class BuiltEntry implements TabPlayerEntry {
            private final Component displayName;
            private final Component prefix;
            private final Component suffix;
            private final int latency;
            private final String gameMode;
            private final TabImage avatarImage;
            private final String bgColor;
            private final int sortOrder;

            BuiltEntry(Component displayName, Component prefix, Component suffix, 
                      int latency, String gameMode, TabImage avatarImage, 
                      String bgColor, int sortOrder) {
                this.displayName = Objects.requireNonNull(displayName, "displayName");
                this.prefix = prefix != null ? prefix : Component.empty();
                this.suffix = suffix != null ? suffix : Component.empty();
                this.latency = latency;
                this.gameMode = gameMode != null ? gameMode : "SURVIVAL";
                this.avatarImage = avatarImage;
                this.bgColor = bgColor;
                this.sortOrder = sortOrder;
            }

            @Override
            public Component displayName() {
                return displayName;
            }

            @Override
            public Optional<Component> prefix() {
                return prefix.equals(Component.empty()) ? Optional.empty() : Optional.of(prefix);
            }

            @Override
            public Optional<Component> suffix() {
                return suffix.equals(Component.empty()) ? Optional.empty() : Optional.of(suffix);
            }

            @Override
            public int latency() {
                return latency;
            }

            @Override
            public String gameMode() {
                return gameMode;
            }

            @Override
            public Optional<TabImage> avatar() {
                return Optional.ofNullable(avatarImage);
            }

            @Override
            public Optional<String> backgroundColor() {
                return Optional.ofNullable(bgColor);
            }

            @Override
            public int sortOrder() {
                return sortOrder;
            }
        }
    }
}
