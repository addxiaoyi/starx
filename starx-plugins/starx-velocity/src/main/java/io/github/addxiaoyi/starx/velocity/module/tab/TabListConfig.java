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

import io.github.addxiaoyi.starx.api.tab.style.TabImageStyle;
import java.util.List;
import java.util.Objects;

/**
 * 标签页列表配置
 */
public final class TabListConfig {

    private final boolean enabled;
    private final String header;
    private final String footer;
    private final List<String> headerAnimationFrames;
    private final List<String> footerAnimationFrames;
    private final long animationInterval;
    private final boolean showPlayerCount;
    private final boolean showServerName;
    private final boolean showPing;
    private final boolean showGameMode;
    private final boolean showWorld;
    private final TabImageStyle backgroundImageStyle;
    private final String backgroundImageUrl;
    private final boolean usePlayerAvatars;
    private final int refreshInterval;

    private TabListConfig(Builder builder) {
        this.enabled = builder.enabled;
        this.header = builder.header;
        this.footer = builder.footer;
        this.headerAnimationFrames = builder.headerAnimationFrames;
        this.footerAnimationFrames = builder.footerAnimationFrames;
        this.animationInterval = builder.animationInterval;
        this.showPlayerCount = builder.showPlayerCount;
        this.showServerName = builder.showServerName;
        this.showPing = builder.showPing;
        this.showGameMode = builder.showGameMode;
        this.showWorld = builder.showWorld;
        this.backgroundImageStyle = builder.backgroundImageStyle;
        this.backgroundImageUrl = builder.backgroundImageUrl;
        this.usePlayerAvatars = builder.usePlayerAvatars;
        this.refreshInterval = builder.refreshInterval;
    }

    public boolean enabled() {
        return enabled;
    }

    public String header() {
        return header;
    }

    public String footer() {
        return footer;
    }

    public List<String> headerAnimationFrames() {
        return headerAnimationFrames;
    }

    public List<String> footerAnimationFrames() {
        return footerAnimationFrames;
    }

    public long animationInterval() {
        return animationInterval;
    }

    public boolean showPlayerCount() {
        return showPlayerCount;
    }

    public boolean showServerName() {
        return showServerName;
    }

    public boolean showPing() {
        return showPing;
    }

    public boolean showGameMode() {
        return showGameMode;
    }

    public boolean showWorld() {
        return showWorld;
    }

    public TabImageStyle backgroundImageStyle() {
        return backgroundImageStyle;
    }

    public String backgroundImageUrl() {
        return backgroundImageUrl;
    }

    public boolean usePlayerAvatars() {
        return usePlayerAvatars;
    }

    public int refreshInterval() {
        return refreshInterval;
    }

    /**
     * 创建默认配置
     */
    public static TabListConfig defaultConfig() {
        return builder().build();
    }

    /**
     * 创建构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 构建器
     */
    public static final class Builder {
        private boolean enabled = true;
        private String header = "§6§l★ §fStarX §6§l★";
        private String footer = "§7当前在线: §f%player_count%";
        private List<String> headerAnimationFrames = List.of();
        private List<String> footerAnimationFrames = List.of();
        private long animationInterval = 1000;
        private boolean showPlayerCount = true;
        private boolean showServerName = true;
        private boolean showPing = true;
        private boolean showGameMode = false;
        private boolean showWorld = false;
        private TabImageStyle backgroundImageStyle = TabImageStyle.defaultStyle();
        private String backgroundImageUrl = "";
        private boolean usePlayerAvatars = false;
        private int refreshInterval = 50;

        private Builder() {}

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder header(String header) {
            this.header = Objects.requireNonNull(header, "header");
            return this;
        }

        public Builder footer(String footer) {
            this.footer = Objects.requireNonNull(footer, "footer");
            return this;
        }

        public Builder headerAnimationFrames(List<String> headerAnimationFrames) {
            this.headerAnimationFrames = Objects.requireNonNull(headerAnimationFrames, "headerAnimationFrames");
            return this;
        }

        public Builder footerAnimationFrames(List<String> footerAnimationFrames) {
            this.footerAnimationFrames = Objects.requireNonNull(footerAnimationFrames, "footerAnimationFrames");
            return this;
        }

        public Builder animationInterval(long animationInterval) {
            this.animationInterval = Math.max(50, animationInterval);
            return this;
        }

        public Builder showPlayerCount(boolean showPlayerCount) {
            this.showPlayerCount = showPlayerCount;
            return this;
        }

        public Builder showServerName(boolean showServerName) {
            this.showServerName = showServerName;
            return this;
        }

        public Builder showPing(boolean showPing) {
            this.showPing = showPing;
            return this;
        }

        public Builder showGameMode(boolean showGameMode) {
            this.showGameMode = showGameMode;
            return this;
        }

        public Builder showWorld(boolean showWorld) {
            this.showWorld = showWorld;
            return this;
        }

        public Builder backgroundImageStyle(TabImageStyle backgroundImageStyle) {
            this.backgroundImageStyle = Objects.requireNonNull(backgroundImageStyle, "backgroundImageStyle");
            return this;
        }

        public Builder backgroundImageUrl(String backgroundImageUrl) {
            this.backgroundImageUrl = Objects.requireNonNull(backgroundImageUrl, "backgroundImageUrl");
            return this;
        }

        public Builder usePlayerAvatars(boolean usePlayerAvatars) {
            this.usePlayerAvatars = usePlayerAvatars;
            return this;
        }

        public Builder refreshInterval(int refreshInterval) {
            this.refreshInterval = Math.max(10, refreshInterval);
            return this;
        }

        public TabListConfig build() {
            return new TabListConfig(this);
        }
    }
}
