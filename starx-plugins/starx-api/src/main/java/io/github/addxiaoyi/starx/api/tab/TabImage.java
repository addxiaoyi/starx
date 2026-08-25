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
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * 标签页图片封装
 * 支持通过皮肤纹理嵌入图片
 */
public interface TabImage {

    /**
     * 获取图片宽度（字符宽度）
     * @return 宽度
     */
    int width();

    /**
     * 获取图片高度（行数）
     * @return 高度
     */
    int height();

    /**
     * 获取图片URL或纹理ID
     * @return 图片标识
     */
    String imageUrl();

    /**
     * 获取图片颜色方案
     * @return 颜色数组
     */
    default char[][] colors() {
        return new char[height()][width()];
    }

    /**
     * 渲染为文本组件
     * @return 文本组件
     */
    default Component render() {
        char[][] colors = colors();
        StringBuilder sb = new StringBuilder();
        for (char[] row : colors) {
            for (char c : row) {
                sb.append(c);
            }
            sb.append('\n');
        }
        return Component.text(sb.toString());
    }

    /**
     * 创建简单的图片
     * @param colors 颜色字符数组
     * @return 图片实例
     */
    static TabImage of(char[][] colors) {
        Objects.requireNonNull(colors, "colors");
        return new SimpleTabImage(colors, "");
    }

    /**
     * 创建带图片链接的图片
     * @param width 宽度
     * @param height 高度
     * @param imageUrl 图片URL
     * @return 图片实例
     */
    static TabImage fromUrl(int width, int height, String imageUrl) {
        Objects.requireNonNull(imageUrl, "imageUrl");
        return new UrlTabImage(width, height, imageUrl);
    }

    /**
     * 创建基于Base64编码的图片
     * @param base64Data Base64编码的图片数据
     * @param width 宽度
     * @param height 高度
     * @return 图片实例
     */
    static TabImage fromBase64(String base64Data, int width, int height) {
        Objects.requireNonNull(base64Data, "base64Data");
        return new Base64TabImage(base64Data, width, height);
    }

    /**
     * 处理器接口
     */
    interface ImageProcessor {
        /**
         * 处理图片数据
         * @param data 图片数据
         * @return 处理后的图片
         */
        TabImage process(byte[] data);
    }

    /**
     * 简单图片实现
     */
    final class SimpleTabImage implements TabImage {
        private final int width;
        private final int height;
        private final String imageUrl;
        private final char[][] colors;

        SimpleTabImage(char[][] colors, String imageUrl) {
            this.colors = colors;
            this.width = colors.length > 0 ? colors[0].length : 0;
            this.height = colors.length;
            this.imageUrl = imageUrl != null ? imageUrl : "";
        }

        @Override
        public int width() {
            return width;
        }

        @Override
        public int height() {
            return height;
        }

        @Override
        public String imageUrl() {
            return imageUrl;
        }

        @Override
        public char[][] colors() {
            return colors.clone();
        }
    }

    /**
     * URL图片实现
     */
    final class UrlTabImage implements TabImage {
        private final int width;
        private final int height;
        private final String imageUrl;

        UrlTabImage(int width, int height, String imageUrl) {
            this.width = width > 0 ? width : 1;
            this.height = height > 0 ? height : 1;
            this.imageUrl = Objects.requireNonNull(imageUrl, "imageUrl");
        }

        @Override
        public int width() {
            return width;
        }

        @Override
        public int height() {
            return height;
        }

        @Override
        public String imageUrl() {
            return imageUrl;
        }

        @Override
        public Component render() {
            // 返回一个包含图片URL的占位符组件
            // 实际渲染由平台实现
            return Component.text("[" + imageUrl.substring(Math.max(0, imageUrl.length() - 10)) + "]");
        }
    }

    /**
     * Base64图片实现
     */
    final class Base64TabImage implements TabImage {
        private final String base64Data;
        private final int width;
        private final int height;
        private final String imageUrl;

        Base64TabImage(String base64Data, int width, int height) {
            this.base64Data = Objects.requireNonNull(base64Data, "base64Data");
            this.width = width > 0 ? width : 1;
            this.height = height > 0 ? height : 1;
            this.imageUrl = "base64://" + UUID.randomUUID().toString();
        }

        @Override
        public int width() {
            return width;
        }

        @Override
        public int height() {
            return height;
        }

        @Override
        public String imageUrl() {
            return imageUrl;
        }

        /**
         * 获取解码后的图片数据
         * @return Base64解码后的字节数组
         */
        public byte[] decode() {
            return Base64.getDecoder().decode(base64Data);
        }

        /**
         * 获取Base64字符串
         * @return Base64字符串
         */
        public String getBase64() {
            return base64Data;
        }
    }
}