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
import java.util.concurrent.CompletableFuture;

/**
 * 扩展热重载管理器
 * 提供扩展的动态加载、卸载和重载功能
 */
public interface ExtensionHotReloadManager {

    /**
     * 启用扩展热重载
     */
    void enableHotReload();

    /**
     * 禁用扩展热重载
     */
    void disableHotReload();

    /**
     * 检查是否启用热重载
     *
     * @return 是否启用热重载
     */
    boolean isHotReloadEnabled();

    /**
     * 热重载指定扩展
     *
     * @param extensionId 扩展ID
     * @return 是否成功重载
     */
    CompletableFuture<Boolean> hotReloadExtension(String extensionId);

    /**
     * 热重载所有扩展
     * @return 重载结果映射
     */
    CompletableFuture<Map<String, Boolean>> hotReloadAllExtensions();

    /**
     * 卸载扩展
     * @param extensionId 扩展ID
     * @return 是否成功卸载
     */
    CompletableFuture<Boolean> unloadExtension(String extensionId);

    /**
     * 加载扩展
     * @param extensionId 扩展ID
     * @return 是否成功加载
     */
    CompletableFuture<Boolean> loadExtension(String extensionId);

    /**
     * 获取扩展状态
     * @param extensionId 扩展ID
     * @return 扩展状态
     */
    Optional<ExtensionHotReloadState> getExtensionState(String extensionId);

    /**
     * 获取所有扩展的热重载状态
     */
    Map<String, ExtensionHotReloadState> getAllExtensionStates();

    /**
     * 注册热重载监听器
     * @param listener 监听器
     */
    void addHotReloadListener(HotReloadListener listener);

    /**
     * 移除热重载监听器
     * @param listener 监听器
     */
    void removeHotReloadListener(HotReloadListener listener);

    /**
     * 扩展热重载状态
     */
    enum ExtensionHotReloadState {
        LOADED,        // 已加载
        UNLOADED,      // 已卸载
        RELOADING,     // 重载中
        FAILED,        // 重载失败
        DISABLED       // 已禁用
    }

    /**
     * 热重载监听器
     */
    interface HotReloadListener {
        /**
         * 扩展重载前调用
         * @param extensionId 扩展ID
         */
        default void onBeforeReload(String extensionId) {}

        /**
         * 扩展重载后调用
         * @param extensionId 扩展ID
         * @param success 是否成功
         * @param errorMessage 错误信息（如果失败）
         */
        default void onAfterReload(String extensionId, boolean success, String errorMessage) {}

        /**
         * 扩展卸载前调用
         * @param extensionId 扩展ID
         */
        default void onBeforeUnload(String extensionId) {}

        /**
         * 扩展卸载后调用
         * @param extensionId 扩展ID
         * @param success 是否成功
         */
        default void onAfterUnload(String extensionId, boolean success) {}

        /**
         * 扩展加载前调用
         * @param extensionId 扩展ID
         */
        default void onBeforeLoad(String extensionId) {}

        /**
         * 扩展加载后调用
         * @param extensionId 扩展ID
         * @param success 是否成功
         */
        default void onAfterLoad(String extensionId, boolean success) {}
    }

    /**
     * 热重载结果
     *
     * @param extensionId 扩展ID
     * @param success 是否成功
     * @param errorMessage 错误信息
     * @param durationMillis 持续时间(毫秒)
     * @param warnings 警告列表
     */
    record HotReloadResult(
        String extensionId,
        boolean success,
        String errorMessage,
        long durationMillis,
        List<String> warnings
    ) {
        public static HotReloadResult success(String extensionId, long durationMillis) {
            return new HotReloadResult(extensionId, true, null, durationMillis, List.of());
        }

        public static HotReloadResult failure(String extensionId, String errorMessage, long durationMillis) {
            return new HotReloadResult(extensionId, false, errorMessage, durationMillis, List.of());
        }

        public static HotReloadResult failure(String extensionId, String errorMessage, long durationMillis, List<String> warnings) {
            return new HotReloadResult(extensionId, false, errorMessage, durationMillis, warnings);
        }
    }
}
