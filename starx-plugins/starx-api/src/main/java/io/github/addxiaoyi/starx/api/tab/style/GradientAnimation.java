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
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 渐变动画
 */
public final class GradientAnimation implements TabAnimation {
    private final List<Component> frames;
    private final long frameInterval;

    public GradientAnimation(Component start, Component end, int steps, long frameInterval) {
        this.frameInterval = Math.max(50, frameInterval);
        this.frames = generateGradientFrames(start, end, steps);
    }

    private List<Component> generateGradientFrames(Component start, Component end, int steps) {
        List<Component> result = new ArrayList<>();
        
        if (steps <= 0) {
            result.add(start);
            return result;
        }
        
        String startText = LegacyComponentSerializer.legacySection().serialize(start);
        String endText = LegacyComponentSerializer.legacySection().serialize(end);
        
        TextColor startColor = start.color() != null 
            ? (TextColor) start.color() 
            : NamedTextColor.WHITE;
        TextColor endColor = end.color() != null 
            ? (TextColor) end.color() 
            : NamedTextColor.WHITE;
        
        for (int i = 0; i <= steps; i++) {
            float ratio = (float) i / steps;
            TextColor color = interpolateColor(startColor, endColor, ratio);
            result.add(Component.text("▸", color));
        }
        
        return result;
    }

    private TextColor interpolateColor(TextColor start, TextColor end, float ratio) {
        int r1 = (start.red() & 0xFF) << 16 | (start.green() & 0xFF) << 8 | (start.blue() & 0xFF);
        int r2 = (end.red() & 0xFF) << 16 | (end.green() & 0xFF) << 8 | (end.blue() & 0xFF);
        
        int r = (int) ((r1 >> 16 & 0xFF) + ((r2 >> 16 & 0xFF) - (r1 >> 16 & 0xFF)) * ratio);
        int g = (int) ((r1 >> 8 & 0xFF) + ((r2 >> 8 & 0xFF) - (r1 >> 8 & 0xFF)) * ratio);
        int b = (int) ((r1 & 0xFF) + ((r2 & 0xFF) - (r1 & 0xFF)) * ratio);
        
        return TextColor.color(r, g, b);
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
     * 创建彩虹渐变动画
     * @param text 文本
     * @param steps 渐变步数
     * @param interval 帧间隔
     * @return 动画实例
     */
    public static GradientAnimation rainbow(String text, int steps, long interval) {
        Objects.requireNonNull(text, "text");
        
        List<Component> rainbowFrames = new ArrayList<>();
        for (int i = 0; i <= steps; i++) {
            float hue = (float) i / steps;
            TextColor color = hsvToRgb(hue, 1.0f, 1.0f);
            rainbowFrames.add(Component.text(text, color));
        }
        
        return new GradientAnimation(rainbowFrames, interval);
    }

    private static TextColor hsvToRgb(float h, float s, float v) {
        int horiz = (int) (h * 6);
        float f = h * 6 - horiz;
        float p = v * (1 - s);
        float q = v * (1 - f * s);
        float t = v * (1 - (1 - f) * s);
        
        int r, g, b;
        switch (horiz % 6) {
            case 0 -> { r = (int) (v * 255); g = (int) (t * 255); b = (int) (p * 255); }
            case 1 -> { r = (int) (q * 255); g = (int) (v * 255); b = (int) (p * 255); }
            case 2 -> { r = (int) (p * 255); g = (int) (v * 255); b = (int) (t * 255); }
            case 3 -> { r = (int) (p * 255); g = (int) (q * 255); b = (int) (v * 255); }
            case 4 -> { r = (int) (t * 255); g = (int) (p * 255); b = (int) (v * 255); }
            case 5 -> { r = (int) (v * 255); g = (int) (p * 255); b = (int) (q * 255); }
            default -> { r = 0; g = 0; b = 0; }
        }
        
        return TextColor.color(Math.max(0, Math.min(255, r)), 
                               Math.max(0, Math.min(255, g)), 
                               Math.max(0, Math.min(255, b)));
    }

    private GradientAnimation(List<Component> frames, long interval) {
        this.frames = new ArrayList<>(frames);
        this.frameInterval = Math.max(50, interval);
    }

    /**
     * 创建渐变动画
     * @param start 起始组件
     * @param end 结束组件
     * @param steps 渐变步数
     * @param interval 帧间隔
     * @return 动画实例
     */
    public static GradientAnimation create(Component start, Component end, int steps, long interval) {
        return new GradientAnimation(start, end, steps, interval);
    }
}