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
import java.util.Optional;

/**
 * 扩展依赖检查器
 * 提供自动依赖解析和版本兼容性检查
 */
public interface ExtensionDependencyChecker {

    /**
     * 检查扩展是否可以安全加载
     * @param descriptor 扩展描述符
     * @return 检查结果
     */
    CheckResult checkCanLoad(StarxExtensionDescriptor descriptor);

    /**
     * 获取缺失的依赖列表
     * @param descriptor 扩展描述符
     * @return 缺失的依赖列表
     */
    List<DependencyInfo> getMissingDependencies(StarxExtensionDescriptor descriptor);

    /**
     * 获取版本不兼容的依赖列表
     * @param descriptor 扩展描述符
     * @return 不兼容的依赖列表
     */
    List<DependencyInfo> getIncompatibleDependencies(StarxExtensionDescriptor descriptor);

    /**
     * 自动解析并加载依赖
     * @param descriptor 扩展描述符
     * @return 是否成功解析所有依赖
     */
    boolean resolveDependencies(StarxExtensionDescriptor descriptor);

    /**
     * 验证扩展的加载顺序
     * @param descriptors 所有扩展描述符
     * @return 排序后的加载顺序
     */
    List<StarxExtensionDescriptor> resolveLoadOrder(List<StarxExtensionDescriptor> descriptors);

    /**
     * 检查结果
     */
    record CheckResult(
        boolean canLoad,
        List<DependencyInfo> missingDependencies,
        List<DependencyInfo> incompatibleDependencies,
        List<String> warnings,
        String errorMessage
    ) {
        public static CheckResult success() {
            return new CheckResult(true, List.of(), List.of(), List.of(), null);
        }

        public static CheckResult failure(String errorMessage) {
            return new CheckResult(false, List.of(), List.of(), List.of(), errorMessage);
        }

        public static CheckResult failure(String errorMessage, List<DependencyInfo> missing, List<DependencyInfo> incompatible) {
            return new CheckResult(false, missing, incompatible, List.of(), errorMessage);
        }
    }

    /**
     * 依赖信息
     */
    record DependencyInfo(
        String extensionId,
        String extensionName,
        String requiredVersion,
        String actualVersion,
        boolean isOptional
    ) {
        public boolean isVersionCompatible(String actualVersion) {
            if (actualVersion == null) return false;
            
            String required = this.requiredVersion;
            if (required == null || required.equals("*")) return true;
            
            // 支持语义化版本检查
            if (required.startsWith(">=")) {
                return compareVersions(actualVersion, required.substring(2)) >= 0;
            } else if (required.startsWith(">")) {
                return compareVersions(actualVersion, required.substring(1)) > 0;
            } else if (required.startsWith("<=")) {
                return compareVersions(actualVersion, required.substring(2)) <= 0;
            } else if (required.startsWith("<")) {
                return compareVersions(actualVersion, required.substring(1)) < 0;
            } else if (required.startsWith("~")) {
                // 兼容版本范围
                return isCompatibleMinor(actualVersion, required.substring(1));
            } else if (required.startsWith("^")) {
                // 兼容版本范围
                return isCompatibleMajor(actualVersion, required.substring(1));
            }
            
            // 精确版本匹配
            return actualVersion.equals(required);
        }

        private int compareVersions(String v1, String v2) {
            String[] parts1 = v1.split("\\.");
            String[] parts2 = v2.split("\\.");
            
            int length = Math.max(parts1.length, parts2.length);
            for (int i = 0; i < length; i++) {
                int p1 = i < parts1.length ? parseVersionPart(parts1[i]) : 0;
                int p2 = i < parts2.length ? parseVersionPart(parts2[i]) : 0;
                
                if (p1 != p2) {
                    return Integer.compare(p1, p2);
                }
            }
            return 0;
        }

        private int parseVersionPart(String part) {
            StringBuilder sb = new StringBuilder();
            for (char c : part.toCharArray()) {
                if (Character.isDigit(c)) {
                    sb.append(c);
                } else {
                    break;
                }
            }
            return sb.length() > 0 ? Integer.parseInt(sb.toString()) : 0;
        }

        private boolean isCompatibleMinor(String actual, String required) {
            // ~1.2.3 兼容 >= 1.2.3 且 < 1.3.0
            String[] actualParts = actual.split("\\.");
            String[] requiredParts = required.split("\\.");
            
            if (actualParts.length < 2 || requiredParts.length < 2) return false;
            
            int major = parseVersionPart(actualParts[0]);
            int minor = parseVersionPart(actualParts[1]);
            int reqMajor = parseVersionPart(requiredParts[0]);
            int reqMinor = parseVersionPart(requiredParts[1]);
            
            if (major != reqMajor || minor < reqMinor) return false;
            return minor == reqMinor || (minor == reqMinor + 1 && actualParts.length >= 3);
        }

        private boolean isCompatibleMajor(String actual, String required) {
            // ^1.2.3 兼容 >= 1.2.3 且 < 2.0.0
            String[] actualParts = actual.split("\\.");
            String[] requiredParts = required.split("\\.");
            
            if (actualParts.length < 2 || requiredParts.length < 2) return false;
            
            int major = parseVersionPart(actualParts[0]);
            int reqMajor = parseVersionPart(requiredParts[0]);
            int reqMinor = parseVersionPart(requiredParts[1]);
            
            if (major != reqMajor) return false;
            return actualParts.length >= 2 && parseVersionPart(actualParts[1]) >= reqMinor;
        }
    }
}
