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
import java.util.function.Function;
import io.github.addxiaoyi.starx.api.tab.style.FlowingAnimation;
import io.github.addxiaoyi.starx.api.tab.style.PulseAnimation;
import io.github.addxiaoyi.starx.api.tab.style.ScrollingTextAnimation;
import io.github.addxiaoyi.starx.api.tab.style.LoopingAnimation;
import io.github.addxiaoyi.starx.api.tab.style.GradientAnimation;

/**
 * 标签页动画接口
 * 支持流动动画效果
 */
public interface TabAnimation {

    /**
     * 获取动画帧列表
     * @return 动画帧列表
     */
    List<Component> frames();

    /**
     * 获取动画帧间隔（毫秒）
     * @return 帧间隔
     */
    long frameInterval();

    /**
     * 获取当前帧
     * @param timestamp 当前时间戳
     * @return 当前帧的组件
     */
    default Component currentFrame(long timestamp) {
        List<Component> frames = frames();
        if (frames.isEmpty()) {
            return Component.empty();
        }
        
        long interval = frameInterval();
        if (interval <= 0) {
            return frames.get(0);
        }
        
        int index = (int) ((timestamp / interval) % frames.size());
        return frames.get(index);
    }

    /**
     * 创建流动动画
     * @param frames 动画帧
     * @param interval 帧间隔（毫秒）
     * @return 动画实例
     */
    static TabAnimation flowing(List<Component> frames, long interval) {
        return new FlowingAnimation(frames, interval);
    }

    /**
     * 创建循环动画
     * @param frames 动画帧
     * @param interval 帧间隔（毫秒）
     * @param loopCount 循环次数（0 = 无限循环）
     * @return 动画实例
     */
    static TabAnimation looping(List<Component> frames, long interval, int loopCount) {
        return new LoopingAnimation(frames, interval, loopCount);
    }

    /**
     * 创建渐变动画
     * @param start 开始组件
     * @param end 结束组件
     * @param steps 渐变步数
     * @param interval 帧间隔（毫秒）
     * @return 动画实例
     */
    static TabAnimation gradient(Component start, Component end, int steps, long interval) {
        return new GradientAnimation(start, end, steps, interval);
    }

    /**
     * 创建脉冲动画
     * @param component 组件
     * @param pulseInterval 脉冲间隔（毫秒）
     * @return 动画实例
     */
    static TabAnimation pulse(Component component, long pulseInterval) {
        return new PulseAnimation(component, pulseInterval);
    }

    /**
     * 创建滚动文本动画
     * @param text 文本
     * @param width 显示宽度
     * @param interval 帧间隔（毫秒）
     * @return 动画实例
     */
    static TabAnimation scrolling(String text, int width, long interval) {
        return new ScrollingTextAnimation(text, width, interval);
    }
}
