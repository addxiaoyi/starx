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

/**
 * 循环动画实现
 */
public final class LoopingAnimation implements TabAnimation {
    private final List<Component> frames;
    private final long frameInterval;
    private final int loopCount;

    public LoopingAnimation(List<Component> frames, long frameInterval, int loopCount) {
        this.frames = new ArrayList<>(frames);
        this.frameInterval = frameInterval;
        this.loopCount = loopCount;
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
     * 获取当前帧（考虑循环次数）
     * @param timestamp 当前时间戳
     * @return 当前帧
     */
    public Component currentFrame(long timestamp) {
        if (loopCount <= 0) {
            // 无限循环
            int index = (int) ((timestamp / frameInterval) % frames.size());
            return frames.get(index);
        }
        
        long totalFrames = (long) frames.size() * loopCount;
        long elapsedFrames = timestamp / frameInterval;
        
        if (elapsedFrames >= totalFrames) {
            // 已完成循环，将返回最后一帧
            return frames.get(frames.size() - 1);
        }
        
        int index = (int) (elapsedFrames % frames.size());
        return frames.get(index);
    }
}