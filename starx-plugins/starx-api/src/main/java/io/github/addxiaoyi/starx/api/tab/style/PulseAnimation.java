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
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.format.NamedTextColor;
import java.util.List;
import java.util.Objects;

/**
 * 脉冲动画实现
 */
public final class PulseAnimation implements TabAnimation {
    private final Component component;
    private final long pulseInterval;

    public PulseAnimation(Component component, long pulseInterval) {
        this.component = Objects.requireNonNull(component, "component");
        this.pulseInterval = Math.max(50, pulseInterval);
    }

    @Override
    public java.util.List<Component> frames() {
        // 脉冲动画返回单帧，通过currentFrame计算状态
        return List.of(component);
    }

    @Override
    public long frameInterval() {
        return pulseInterval;
    }

    @Override
    public Component currentFrame(long timestamp) {
        // 在明亮和暗淡之间切换
        boolean bright = (timestamp / (pulseInterval / 2)) % 2 == 0;
        return component.decoration(
            bright ? TextDecoration.BOLD : TextDecoration.ITALIC,
            bright ? TextDecoration.State.TRUE : TextDecoration.State.NOT_SET
        );
    }

    /**
     * 创建带颜色的脉冲动画
     * @param text 文本内容
     * @param color 颜色
     * @param pulseInterval 脉冲间隔
     * @return 动画实例
     */
    public static PulseAnimation of(String text, NamedTextColor color, long pulseInterval) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(color, "color");
        return new PulseAnimation(Component.text(text, color), pulseInterval);
    }
}