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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 默认标签页列表实现
 */
public class DefaultTabList implements TabList {

    private Component header = Component.empty();
    private Component footer = Component.empty();
    private TabAnimation headerAnimation = null;
    private TabAnimation footerAnimation = null;
    private TabImage backgroundImage = null;
    private String backgroundColor = null;

    private final Map<String, TabPlayerEntry> entries = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "TabList-Animation-Thread");
        thread.setDaemon(true);
        return thread;
    });

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean destroyed = new AtomicBoolean(false);

    private final List<Runnable> refreshCallbacks = new ArrayList<>();

    public DefaultTabList() {
    }

    @Override
    public void setHeader(Component header) {
        this.header = Objects.requireNonNull(header, "header");
        this.headerAnimation = null;
        refresh();
    }

    @Override
    public void setHeaderAnimation(TabAnimation animation) {
        this.headerAnimation = Objects.requireNonNull(animation, "animation");
        this.header = Component.empty();
        startAnimation();
    }

    @Override
    public void setFooter(Component footer) {
        this.footer = Objects.requireNonNull(footer, "footer");
        this.footerAnimation = null;
        refresh();
    }

    @Override
    public void setFooterAnimation(TabAnimation animation) {
        this.footerAnimation = Objects.requireNonNull(animation, "animation");
        this.footer = Component.empty();
        startAnimation();
    }

    @Override
    public void setBackgroundImage(TabImage image) {
        this.backgroundImage = image;
        refresh();
    }

    @Override
    public void setBackgroundColor(String color) {
        this.backgroundColor = color;
        refresh();
    }

    @Override
    public void setPlayerEntry(String playerId, TabPlayerEntry entry) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(entry, "entry");
        entries.put(playerId, entry);
        refresh();
    }

    @Override
    public void removePlayerEntry(String playerId) {
        Objects.requireNonNull(playerId, "playerId");
        entries.remove(playerId);
        refresh();
    }

    @Override
    public void clearPlayers() {
        entries.clear();
        refresh();
    }

    @Override
    public Optional<Component> getHeader() {
        if (headerAnimation != null) {
            return Optional.of(headerAnimation.currentFrame(System.currentTimeMillis()));
        }
        return header.equals(Component.empty()) ? Optional.empty() : Optional.of(header);
    }

    @Override
    public Optional<Component> getFooter() {
        if (footerAnimation != null) {
            return Optional.of(footerAnimation.currentFrame(System.currentTimeMillis()));
        }
        return footer.equals(Component.empty()) ? Optional.empty() : Optional.of(footer);
    }

    @Override
    public Optional<TabPlayerEntry> getPlayerEntry(String playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return Optional.ofNullable(entries.get(playerId));
    }

    @Override
    public Map<String, TabPlayerEntry> getAllEntries() {
        return Map.copyOf(entries);
    }

    @Override
    public void refresh() {
        if (destroyed.get()) return;
        
        // 执行所有刷新回调
        for (Runnable callback : refreshCallbacks) {
            try {
                callback.run();
            } catch (Exception e) {
                // 记录错误但不中断其他回调
            }
        }
    }

    @Override
    public void destroy() {
        if (destroyed.compareAndSet(false, true)) {
            stopAnimation();
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            refreshCallbacks.clear();
            entries.clear();
        }
    }

    /**
     * 添加刷新回调
     * @param callback 回调函数
     */
    public void addRefreshCallback(Runnable callback) {
        Objects.requireNonNull(callback, "callback");
        refreshCallbacks.add(callback);
    }

    /**
     * 移除刷新回调
     * @param callback 回调函数
     */
    public void removeRefreshCallback(Runnable callback) {
        Objects.requireNonNull(callback, "callback");
        refreshCallbacks.remove(callback);
    }

    /**
     * 获取背景图片
     * @return 背景图片
     */
    public Optional<TabImage> getBackgroundImage() {
        return Optional.ofNullable(backgroundImage);
    }

    /**
     * 获取背景颜色
     * @return 背景颜色
     */
    public Optional<String> getBackgroundColor() {
        return Optional.ofNullable(backgroundColor);
    }

    /**
     * 启动动画
     */
    private void startAnimation() {
        if (destroyed.get() || running.get()) return;
        
        if (headerAnimation == null && footerAnimation == null) {
            return;
        }

        if (running.compareAndSet(false, true)) {
            long minInterval = Math.min(
                headerAnimation != null ? headerAnimation.frameInterval() : Long.MAX_VALUE,
                footerAnimation != null ? footerAnimation.frameInterval() : Long.MAX_VALUE
            );
            
            scheduler.scheduleAtFixedRate(() -> {
                if (!destroyed.get()) {
                    refresh();
                }
            }, 0, minInterval, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 停止动画
     */
    private void stopAnimation() {
        running.set(false);
    }

    /**
     * 创建构建器
     * @return 构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 构建器
     */
    public static class Builder {
        private Component header = Component.empty();
        private Component footer = Component.empty();
        private TabAnimation headerAnimation = null;
        private TabAnimation footerAnimation = null;
        private TabImage backgroundImage = null;
        private String backgroundColor = null;
        private final Map<String, TabPlayerEntry> entries = new HashMap<>();

        public Builder header(Component header) {
            this.header = Objects.requireNonNull(header, "header");
            this.headerAnimation = null;
            return this;
        }

        public Builder headerAnimation(TabAnimation animation) {
            this.headerAnimation = Objects.requireNonNull(animation, "animation");
            this.header = Component.empty();
            return this;
        }

        public Builder footer(Component footer) {
            this.footer = Objects.requireNonNull(footer, "footer");
            this.footerAnimation = null;
            return this;
        }

        public Builder footerAnimation(TabAnimation animation) {
            this.footerAnimation = Objects.requireNonNull(animation, "animation");
            this.footer = Component.empty();
            return this;
        }

        public Builder backgroundImage(TabImage image) {
            this.backgroundImage = image;
            return this;
        }

        public Builder backgroundColor(String color) {
            this.backgroundColor = color;
            return this;
        }

        public Builder addPlayerEntry(String playerId, TabPlayerEntry entry) {
            this.entries.put(Objects.requireNonNull(playerId, "playerId"), 
                           Objects.requireNonNull(entry, "entry"));
            return this;
        }

        public Builder addPlayerEntry(String playerId, Component displayName) {
            this.entries.put(Objects.requireNonNull(playerId, "playerId"),
                           TabPlayerEntry.builder(displayName).build());
            return this;
        }

        public DefaultTabList build() {
            DefaultTabList tabList = new DefaultTabList();
            tabList.header = this.header;
            tabList.footer = this.footer;
            tabList.headerAnimation = this.headerAnimation;
            tabList.footerAnimation = this.footerAnimation;
            tabList.backgroundImage = this.backgroundImage;
            tabList.backgroundColor = this.backgroundColor;
            tabList.entries.putAll(this.entries);
            
            if (tabList.headerAnimation != null || tabList.footerAnimation != null) {
                tabList.startAnimation();
            }
            
            return tabList;
        }
    }
}
