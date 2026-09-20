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

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 扩展兼容性管理器
 * 提供向后兼容性支持和版本适配
 */
public interface ExtensionCompatibilityManager {

    /**
     * 检查扩展是否与当前平台兼容
     * @param descriptor 扩展描述符
     * @return 兼容性结果
     */
    CompatibilityResult checkCompatibility(StarxExtensionDescriptor descriptor);

    /**
     * 获取兼容性适配器
     * @param extensionId 扩展ID
     * @return 适配器（如果需要）
     */
    Optional<CompatibilityAdapter> getAdapter(String extensionId);

    /**
     * 注册兼容性适配器
     * @param extensionId 扩展ID
     * @param adapter 适配器
     */
    void registerAdapter(String extensionId, CompatibilityAdapter adapter);

    /**
     * 获取所有已知的兼容性问题
     */
    List<CompatibilityIssue> getKnownIssues();

    /**
     * 获取兼容性问题的解决方案
     * @param issue 兼容性问题
     * @return 解决方案（如果有）
     */
    Optional<CompatibilitySolution> getSolution(CompatibilityIssue issue);

    /**
     * 检查是否可以启用向后兼容模式
     * @param extensionId 扩展ID
     * @return 是否可以启用
     */
    boolean canEnableBackwardCompatibility(String extensionId);

    /**
     * 启用向后兼容模式
     * @param extensionId 扩展ID
     * @param enable 是否启用
     */
    void setBackwardCompatibilityEnabled(String extensionId, boolean enable);

    /**
     * 获取兼容性配置
     */
    CompatibilityConfig getConfig();

    /**
     * 兼容性结果
     *
     * @param isCompatible 是否兼容
     * @param issues 兼容性问题列表
     * @param warnings 兼容性警告列表
     * @param requiredAdapters 所需适配器映射
     * @param platformVersion 平台版本
     * @param extensionVersion 扩展版本
     */
    record CompatibilityResult(
        boolean isCompatible,
        List<CompatibilityIssue> issues,
        List<CompatibilityWarning> warnings,
        Map<String, String> requiredAdapters,
        String platformVersion,
        String extensionVersion
    ) {
        public boolean hasCriticalIssues() {
            return issues.stream().anyMatch(issue -> issue.severity() == CompatibilityIssue.Severity.CRITICAL);
        }

        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }

        public boolean needsAdapters() {
            return !requiredAdapters.isEmpty();
        }
    }

    /**
     * 兼容性问题
     *
     * @param issueId 问题ID
     * @param description 问题描述
     * @param severity 严重程度
     * @param affectedComponent 受影响的组件
     * @param extensionId 扩展ID
     * @param platformVersion 平台版本
     * @param extensionVersion 扩展版本
     */
    record CompatibilityIssue(
        String issueId,
        String description,
        Severity severity,
        String affectedComponent,
        String extensionId,
        String platformVersion,
        String extensionVersion
    ) {
        public enum Severity {
            CRITICAL,    // 严重问题，无法加载
            HIGH,        // 高风险问题，可能导致崩溃
            MEDIUM,      // 中等问题，部分功能不可用
            LOW,         // 低风险问题，不影响主要功能
            INFO         // 信息，不影响使用
        }
    }

    /**
     * 兼容性警告
     *
     * @param warningId 警告ID
     * @param message 警告消息
     * @param component 相关组件
     * @param suggestedAction 建议操作
     */
    record CompatibilityWarning(
        String warningId,
        String message,
        String component,
        String suggestedAction
    ) {}

    /**
     * 兼容性解决方案
     *
     * @param solutionId 解决方案ID
     * @param description 解决方案描述
     * @param issueId 对应问题ID
     * @param steps 解决步骤列表
     * @param isAutomatic 是否自动应用
     * @param adapterClass 适配器类名
     */
    record CompatibilitySolution(
        String solutionId,
        String description,
        String issueId,
        List<String> steps,
        boolean isAutomatic,
        String adapterClass
    ) {
        public boolean canApplyAutomatically() {
            return isAutomatic && adapterClass != null;
        }
    }

    /**
     * 兼容性适配器
     */
    interface CompatibilityAdapter {
        /**
         * 适配扩展方法调用
         * @param methodName 方法名
         * @param args 参数
         * @return 适配后的结果
         */
        Object adaptMethodCall(String methodName, Object[] args);

        /**
         * 适配扩展配置
         * @param config 原始配置
         * @return 适配后的配置
         */
        Object adaptConfig(Object config);

        /**
         * 适配扩展数据
         * @param data 原始数据
         * @return 适配后的数据
         */
        Object adaptData(Object data);

        /**
         * 获取适配器ID
         */
        String getAdapterId();

        /**
         * 获取目标扩展ID
         */
        String getTargetExtensionId();

        /**
         * 获取适配器版本
         */
        String getVersion();
    }

    /**
     * 兼容性配置
     *
     * @param backwardCompatibilityEnabled 是否启用向后兼容性
     * @param autoApplyAdapters 是否自动应用适配器
     * @param logCompatibilityWarnings 是否记录兼容性警告
     * @param logCompatibilityErrors 是否记录兼容性错误
     * @param enabledAdapters 已启用的适配器列表
     * @param disabledAdapters 已禁用的适配器列表
     * @param adapterVersions 适配器版本映射
     */
    record CompatibilityConfig(
        boolean backwardCompatibilityEnabled,
        boolean autoApplyAdapters,
        boolean logCompatibilityWarnings,
        boolean logCompatibilityErrors,
        List<String> enabledAdapters,
        List<String> disabledAdapters,
        Map<String, String> adapterVersions
    ) {
        public static CompatibilityConfig defaults() {
            return new CompatibilityConfig(
                true,   // 启用向后兼容性
                true,   // 自动应用适配器
                true,   // 记录兼容性警告
                true,   // 记录兼容性错误
                List.of(),
                List.of(),
                Map.of()
            );
        }
    }
}
