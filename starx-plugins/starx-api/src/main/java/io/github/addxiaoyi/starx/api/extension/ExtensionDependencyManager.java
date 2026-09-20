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
import java.util.concurrent.CompletableFuture;

/**
 * 扩展依赖管理器
 * 管理扩展之间的依赖关系
 */
public interface ExtensionDependencyManager {

    /**
     * 注册扩展依赖
     * @param extensionId 扩展ID
     * @param dependencies 依赖列表
     */
    void registerDependencies(String extensionId, List<ExtensionDependency> dependencies);

    /**
     * 检查依赖是否满足
     * @param extensionId 扩展ID
     * @return 是否满足所有依赖
     */
    boolean checkDependencies(String extensionId);

    /**
     * 获取扩展的依赖
     * @param extensionId 扩展ID
     * @return 依赖列表
     */
    List<ExtensionDependency> getDependencies(String extensionId);

    /**
     * 获取依赖扩展的扩展列表
     * @param dependencyId 依赖ID
     * @return 扩展列表
     */
    List<String> getDependentExtensions(String dependencyId);

    /**
     * 检查是否有循环依赖
     * @return 是否有循环依赖
     */
    boolean hasCircularDependencies();

    /**
     * 获取循环依赖列表
     * @return 循环依赖列表
     */
    List<List<String>> getCircularDependencies();

    /**
     * 获取依赖图
     * @return 依赖图
     */
    Map<String, List<String>> getDependencyGraph();

    /**
     * 解析依赖字符串
     * @param dependencyString 依赖字符串
     * @return 解析后的依赖
     */
    ExtensionDependency parseDependency(String dependencyString);

    /**
     * 检查依赖版本兼容性
     * @param extensionId 扩展ID
     * @return 版本兼容性结果
     */
    DependencyVersionResult checkVersionCompatibility(String extensionId);

    /**
     * 获取可选依赖
     * @param extensionId 扩展ID
     * @return 可选依赖列表
     */
    List<ExtensionDependency> getOptionalDependencies(String extensionId);

    /**
     * 获取强制依赖
     * @param extensionId 扩展ID
     * @return 强制依赖列表
     */
    List<ExtensionDependency> getRequiredDependencies(String extensionId);

    /**
     * 扩展依赖
     *
     * @param id 依赖扩展ID
     * @param versionRange 版本范围
     * @param optional 是否可选
     * @param scope 作用域
     * @param metadata 额外元数据
     */
    record ExtensionDependency(
        String id,
        String versionRange,
        boolean optional,
        String scope,
        Map<String, String> metadata
    ) {
        public static ExtensionDependency required(String id, String versionRange) {
            return new ExtensionDependency(id, versionRange, false, "compile", Map.of());
        }

        public static ExtensionDependency optional(String id, String versionRange) {
            return new ExtensionDependency(id, versionRange, true, "compile", Map.of());
        }

        public boolean isRequired() {
            return !optional;
        }

        public boolean isOptional() {
            return optional;
        }
    }

    /**
     * 依赖版本结果
     *
     * @param extensionId 扩展ID
     * @param compatible 是否兼容
     * @param conflicts 版本冲突列表
     * @param missingDependencies 缺失依赖列表
     * @param versionMismatches 版本不匹配列表
     */
    record DependencyVersionResult(
        String extensionId,
        boolean compatible,
        List<VersionConflict> conflicts,
        List<MissingDependency> missingDependencies,
        List<VersionMismatch> versionMismatches
    ) {
        public boolean hasConflicts() {
            return !conflicts.isEmpty();
        }

        public boolean hasMissingDependencies() {
            return !missingDependencies.isEmpty();
        }

        public boolean hasVersionMismatches() {
            return !versionMismatches.isEmpty();
        }
    }

    /**
     * 版本冲突
     *
     * @param dependencyId 依赖ID
     * @param requiredVersion 所需版本
     * @param availableVersion 可用版本
     * @param message 冲突消息
     */
    record VersionConflict(
        String dependencyId,
        String requiredVersion,
        String availableVersion,
        String message
    ) {}

    /**
     * 缺失依赖
     *
     * @param dependencyId 依赖ID
     * @param requiredVersion 所需版本
     * @param optional 是否可选
     */
    record MissingDependency(
        String dependencyId,
        String requiredVersion,
        boolean optional
    ) {}

    /**
     * 版本不匹配
     *
     * @param dependencyId 依赖ID
     * @param requiredVersion 所需版本
     * @param availableVersion 可用版本
     */
    record VersionMismatch(
        String dependencyId,
        String requiredVersion,
        String availableVersion
    ) {}

    /**
     * 依赖解析器
     */
    interface DependencyResolver {
        /**
         * 解析依赖
         * @param dependencyString 依赖字符串
         * @return 解析后的依赖
         */
        ExtensionDependency resolve(String dependencyString);

        /**
         * 解析版本范围
         * @param versionRange 版本范围
         * @return 版本约束
         */
        VersionConstraint parseVersionConstraint(String versionRange);
    }

    /**
     * 版本约束
     */
    interface VersionConstraint {
        /**
         * 检查版本是否满足约束
         * @param version 版本
         * @return 是否满足
         */
        boolean isSatisfiedBy(String version);

        /**
         * 获取约束字符串
         */
        String getConstraintString();
    }
}
