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

package io.github.addxiaoyi.starx.api.extension;

import java.util.Map;
import java.util.function.Function;

/**
 * 扩展配置助手
 * 提供简化的配置API和自动配置功能
 */
public interface ExtensionConfigurationHelper {

    /**
     * 创建简单配置
     * @param defaults 默认值
     * @return 配置映射
     */
    Map<String, Object> createSimpleConfig(Map<String, Object> defaults);

    /**
     * 从配置中获取值
     * @param config 配置
     * @param key 键
     * @param defaultValue 默认值
     * @param <T> 类型
     * @return 配置值
     */
    <T> T getString(Map<String, Object> config, String key, T defaultValue);

    /**
     * 从配置中获取整数值
     * @param config 配置
     * @param key 键
     * @param defaultValue 默认值
     * @return 配置值
     */
    default int getInt(Map<String, Object> config, String key, int defaultValue) {
        Object value = config.get(key);
        if (value instanceof Integer) return (Integer) value;
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /**
     * 从配置中获取布尔值
     * @param config 配置
     * @param key 键
     * @param defaultValue 默认值
     * @return 配置值
     */
    default boolean getBoolean(Map<String, Object> config, String key, boolean defaultValue) {
        Object value = config.get(key);
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return defaultValue;
    }

    /**
     * 从配置中获取列表值
     * @param config 配置
     * @param key 键
     * @return 配置值列表
     */
    @SuppressWarnings("unchecked")
    default java.util.List<String> getList(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (value instanceof java.util.List) {
            return (java.util.List<String>) value;
        }
        return java.util.List.of();
    }

    /**
     * 从配置中获取嵌套映射
     * @param config 配置
     * @param key 键
     * @return 嵌套映射
     */
    @SuppressWarnings("unchecked")
    default Map<String, Object> getMap(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return Map.of();
    }

    /**
     * 设置配置值
     * @param config 配置
     * @param key 键
     * @param value 值
     */
    void set(Map<String, Object> config, String key, Object value);

    /**
     * 创建带验证的配置字段
     * @param key 键
     * @param defaultValue 默认值
     * @param validator 验证器
     * @return 配置值
     */
    default String getStringWithValidation(Map<String, Object> config, String key, 
                                           String defaultValue, Function<String, Boolean> validator) {
        String value = getString(config, key, defaultValue);
        if (validator.apply(value)) {
            return value;
        }
        return defaultValue;
    }

    /**
     * 创建范围限制的整数配置
     * @param config 配置
     * @param key 键
     * @param defaultValue 默认值
     * @param min 最小值
     * @param max 最大值
     * @return 配置值
     */
    default int getIntInRange(Map<String, Object> config, String key, 
                              int defaultValue, int min, int max) {
        int value = getInt(config, key, defaultValue);
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    /**
     * 创建枚举配置
     * @param config 配置
     * @param key 键
     * @param defaultValue 默认值
     * @param values 可选值
     * @param <T> 枚举类型
     * @return 配置值
     */
    default <T extends Enum<T>> T getEnum(Map<String, Object> config, String key, 
                                  T defaultValue, T[] values) {
        String value = getString(config, key, defaultValue.name());
        try {
            @SuppressWarnings("unchecked")
            T result = (T) Enum.valueOf((Class<T>) defaultValue.getClass(), value.toUpperCase());
            return result;
        } catch (IllegalArgumentException e) {
            return defaultValue;
        }
    }

    /**
     * 验证配置完整性
     * @param config 配置
     * @return 验证结果
     */
    ValidationResult validateConfig(Map<String, Object> config);

    /**
     * 验证结果
     *
     * @param isValid 是否有效
     * @param errors 错误列表
     * @param warnings 警告列表
     * @param suggestions 建议映射
     */
    record ValidationResult(
        boolean isValid,
        java.util.List<String> errors,
        java.util.List<String> warnings,
        java.util.Map<String, String> suggestions
    ) {
        public static ValidationResult valid() {
            return new ValidationResult(true, java.util.List.of(), java.util.List.of(), java.util.Map.of());
        }

        public static ValidationResult invalid(java.util.List<String> errors) {
            return new ValidationResult(false, errors, java.util.List.of(), java.util.Map.of());
        }
    }
}