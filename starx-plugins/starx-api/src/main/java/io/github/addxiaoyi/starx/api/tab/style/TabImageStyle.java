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

package io.github.addxiaoyi.starx.api.tab.style;

import java.util.Objects;

/**
 * 标签页图片样式
 */
public final class TabImageStyle {
    
    /**
     * 图片位置
     */
    public enum Position {
        TOP_LEFT, TOP_CENTER, TOP_RIGHT,
        CENTER_LEFT, CENTER, CENTER_RIGHT,
        BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT,
        FULL_BACKGROUND
    }
    
    /**
     * 图片缩放模式
     */
    public enum ScaleMode {
        NONE,        // 不缩放
        FIT,         // 适应大小
        FILL,        // 填充
        STRETCH,     // 拉伸
        TILE         // 平铺
    }
    
    /**
     * 图片透明度模式
     */
    public enum AlphaMode {
        OPAQUE,      // 不透明
        TRANSLUCENT, // 半透明
        TRANSPARENT  // 透明
    }
    
    private final Position position;
    private final ScaleMode scaleMode;
    private final AlphaMode alphaMode;
    private final float alpha;
    private final int xOffset;
    private final int yOffset;
    private final int width;
    private final int height;
    
    private TabImageStyle(Builder builder) {
        this.position = builder.position;
        this.scaleMode = builder.scaleMode;
        this.alphaMode = builder.alphaMode;
        this.alpha = builder.alpha;
        this.xOffset = builder.xOffset;
        this.yOffset = builder.yOffset;
        this.width = builder.width;
        this.height = builder.height;
    }
    
    public Position position() {
        return position;
    }
    
    public ScaleMode scaleMode() {
        return scaleMode;
    }
    
    public AlphaMode alphaMode() {
        return alphaMode;
    }
    
    public float alpha() {
        return alpha;
    }
    
    public int xOffset() {
        return xOffset;
    }
    
    public int yOffset() {
        return yOffset;
    }
    
    public int width() {
        return width;
    }
    
    public int height() {
        return height;
    }
    
    /**
     * 创建构建器
     * @return 构建器
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * 创建默认样式
     * @return 默认样式
     */
    public static TabImageStyle defaultStyle() {
        return builder().build();
    }
    
    /**
     * 创建全屏背景样式
     * @return 全屏背景样式
     */
    public static TabImageStyle fullBackground() {
        return builder()
            .position(Position.FULL_BACKGROUND)
            .scaleMode(ScaleMode.FILL)
            .build();
    }
    
    /**
     * 创建居中样式
     * @return 居中样式
     */
    public static TabImageStyle center() {
        return builder()
            .position(Position.CENTER)
            .scaleMode(ScaleMode.FIT)
            .build();
    }
    
    /**
     * 构建器
     */
    public static final class Builder {
        private Position position = Position.CENTER;
        private ScaleMode scaleMode = ScaleMode.FIT;
        private AlphaMode alphaMode = AlphaMode.OPAQUE;
        private float alpha = 1.0f;
        private int xOffset = 0;
        private int yOffset = 0;
        private int width = -1;
        private int height = -1;
        
        private Builder() {}
        
        public Builder position(Position position) {
            this.position = Objects.requireNonNull(position, "position");
            return this;
        }
        
        public Builder scaleMode(ScaleMode scaleMode) {
            this.scaleMode = Objects.requireNonNull(scaleMode, "scaleMode");
            return this;
        }
        
        public Builder alphaMode(AlphaMode alphaMode) {
            this.alphaMode = Objects.requireNonNull(alphaMode, "alphaMode");
            return this;
        }
        
        public Builder alpha(float alpha) {
            this.alpha = Math.max(0.0f, Math.min(1.0f, alpha));
            return this;
        }
        
        public Builder xOffset(int xOffset) {
            this.xOffset = xOffset;
            return this;
        }
        
        public Builder yOffset(int yOffset) {
            this.yOffset = yOffset;
            return this;
        }
        
        public Builder width(int width) {
            this.width = width;
            return this;
        }
        
        public Builder height(int height) {
            this.height = height;
            return this;
        }
        
        public Builder size(int width, int height) {
            this.width = width;
            this.height = height;
            return this;
        }
        
        public TabImageStyle build() {
            return new TabImageStyle(this);
        }
    }
}
