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

import io.github.addxiaoyi.starx.api.tab.TabAnimation;
import net.kyori.adventure.text.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 滚动文本动画
 */
public final class ScrollingTextAnimation implements TabAnimation {
    private final List<Component> frames;
    private final long frameInterval;

    public ScrollingTextAnimation(String text, int width, long frameInterval) {
        Objects.requireNonNull(text, "text");
        this.frameInterval = Math.max(50, frameInterval);
        this.frames = generateFrames(text, width);
    }

    private List<Component> generateFrames(String text, int width) {
        List<Component> result = new ArrayList<>();
        
        // 填充空格，确保文本可以完整滚动
        String paddedText = text + "  " + text;
        int maxLen = Math.max(width, 1);
        
        for (int i = 0; i < paddedText.length() - maxLen + 1; i++) {
            String frame = paddedText.substring(i, i + maxLen);
            result.add(Component.text(frame));
        }
        
        return result;
    }

    @Override
    public List<Component> frames() {
        return List.copyOf(frames);
    }

    @Override
    public long frameInterval() {
        return frameInterval;
    }

    /**
     * 创建滚动动画
     * @param text 文本
     * @param interval 帧间隔
     * @return 动画实例
     */
    public static ScrollingTextAnimation create(String text, long interval) {
        return new ScrollingTextAnimation(text, 30, interval);
    }
}