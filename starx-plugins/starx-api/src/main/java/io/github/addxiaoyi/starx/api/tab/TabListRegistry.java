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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 标签页列表注册表
 * 管理多个标签页列表实例
 */
public interface TabListRegistry {

    /**
     * 注册标签页列表
     * @param id 标识符
     * @param tabList 标签页列表
     */
    void register(String id, TabList tabList);

    /**
     * 注册标签页列表提供者
     * @param id 标识符
     * @param supplier 供应器
     */
    void register(String id, Supplier<TabList> supplier);

    /**
     * 注销标签页列表
     * @param id 标识符
     */
    void unregister(String id);

    /**
     * 获取标签页列表
     * @param id 标识符
     * @return 标签页列表
     */
    Optional<TabList> get(String id);

    /**
     * 获取或创建标签页列表
     * @param id 标识符
     * @param supplier 供应器
     * @return 标签页列表
     */
    TabList getOrCreate(String id, Supplier<TabList> supplier);

    /**
     * 获取所有标签页列表
     * @return 所有标签页列表
     */
    Collection<TabList> getAll();

    /**
     * 获取所有标识符
     * @return 所有标识符
     */
    Set<String> getIds();

    /**
     * 检查是否存在
     * @param id 标识符
     * @return 是否存在
     */
    boolean contains(String id);

    /**
     * 清除所有
     */
    void clear();

    /**
     * 添加全局刷新监听器
     * @param listener 监听器
     */
    void addRefreshListener(Consumer<TabList> listener);

    /**
     * 移除全局刷新监听器
     * @param listener 监听器
     */
    void removeRefreshListener(Consumer<TabList> listener);

    /**
     * 刷新所有标签页列表
     */
    void refreshAll();

    /**
     * 销毁所有标签页列表
     */
    void destroyAll();

    /**
     * 创建默认注册表
     * @return 默认注册表
     */
    static TabListRegistry create() {
        return new DefaultTabListRegistry();
    }

    /**
     * 默认注册表实现
     */
    class DefaultTabListRegistry implements TabListRegistry {

        private final ConcurrentMap<String, TabList> registry = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, Supplier<TabList>> suppliers = new ConcurrentHashMap<>();
        private final List<Consumer<TabList>> refreshListeners = new ArrayList<>();

        @Override
        public void register(String id, TabList tabList) {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(tabList, "tabList");
            registry.put(id, tabList);
            suppliers.remove(id);
        }

        @Override
        public void register(String id, Supplier<TabList> supplier) {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(supplier, "supplier");
            suppliers.put(id, supplier);
            registry.remove(id);
        }

        @Override
        public void unregister(String id) {
            Objects.requireNonNull(id, "id");
            TabList tabList = registry.remove(id);
            if (tabList != null) {
                tabList.destroy();
            }
            suppliers.remove(id);
        }

        @Override
        public Optional<TabList> get(String id) {
            Objects.requireNonNull(id, "id");
            
            // 先尝试获取现有实例
            TabList tabList = registry.get(id);
            if (tabList != null) {
                return Optional.of(tabList);
            }
            
            // 尝试从供应器创建
            Supplier<TabList> supplier = suppliers.get(id);
            if (supplier != null) {
                tabList = supplier.get();
                if (tabList != null) {
                    registry.put(id, tabList);
                    return Optional.of(tabList);
                }
            }
            
            return Optional.empty();
        }

        @Override
        public TabList getOrCreate(String id, Supplier<TabList> supplier) {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(supplier, "supplier");
            
            return registry.computeIfAbsent(id, k -> supplier.get());
        }

        @Override
        public Collection<TabList> getAll() {
            return List.copyOf(registry.values());
        }

        @Override
        public Set<String> getIds() {
            return Set.copyOf(registry.keySet());
        }

        @Override
        public boolean contains(String id) {
            Objects.requireNonNull(id, "id");
            return registry.containsKey(id) || suppliers.containsKey(id);
        }

        @Override
        public void clear() {
            for (TabList tabList : registry.values()) {
                tabList.destroy();
            }
            registry.clear();
            suppliers.clear();
        }

        @Override
        public void addRefreshListener(Consumer<TabList> listener) {
            Objects.requireNonNull(listener, "listener");
            refreshListeners.add(listener);
        }

        @Override
        public void removeRefreshListener(Consumer<TabList> listener) {
            Objects.requireNonNull(listener, "listener");
            refreshListeners.remove(listener);
        }

        @Override
        public void refreshAll() {
            for (TabList tabList : registry.values()) {
                tabList.refresh();
            }
            for (Consumer<TabList> listener : refreshListeners) {
                try {
                    for (TabList tabList : registry.values()) {
                        listener.accept(tabList);
                    }
                } catch (Exception e) {
                    // 记录错误但不中断其他监听器
                }
            }
        }

        @Override
        public void destroyAll() {
            for (TabList tabList : registry.values()) {
                tabList.destroy();
            }
            registry.clear();
            suppliers.clear();
            refreshListeners.clear();
        }
    }
}
