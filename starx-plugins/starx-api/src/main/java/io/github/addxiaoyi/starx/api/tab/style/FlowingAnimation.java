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
 * 流动动画实现
 */
public final class FlowingAnimation implements TabAnimation {
    private final List<Component> frames;
    private final long frameInterval;

    public FlowingAnimation(List<Component> frames, long frameInterval) {
        this.frames = new ArrayList<>(frames);
        this.frameInterval = Math.max(10, frameInterval); // 最小间隔10ms
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
     * 创建流动文字效果
     * @param text 要流动的文字
     * @param interval 帧间隔
     * @return 动画实例
     */
    public static FlowingAnimation create(String text, long interval) {
        Objects.requireNonNull(text, "text");
        
        List<Component> frames = new ArrayList<>();
        int len = text.length();
        
        // 创建滚动效果
        for (int i = 0; i < len; i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < len; j++) {
                sb.append(text.charAt((i + j) % len));
            }
            frames.add(Component.text(sb.toString()));
        }
        
        return new FlowingAnimation(frames, interval);
    }
}