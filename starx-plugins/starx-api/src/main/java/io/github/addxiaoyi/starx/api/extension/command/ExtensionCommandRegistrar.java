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

package io.github.addxiaoyi.starx.api.extension.command;

import io.github.addxiaoyi.starx.api.extension.StarxExtension;
import java.util.Optional;

/**
 * 扩展命令注册器
 * 允许扩展注册自定义命令
 */
public interface ExtensionCommandRegistrar {

    /**
     * 注册扩展命令
     * @param extension 扩展实例
     * @param command 命令
     */
    void registerCommand(StarxExtension extension, ExtensionCommand command);

    /**
     * 注册多个扩展命令
     * @param extension 扩展实例
     * @param commands 命令列表
     */
    void registerCommands(StarxExtension extension, Iterable<ExtensionCommand> commands);

    /**
     * 注销扩展的所有命令
     * @param extension 扩展实例
     */
    void unregisterCommands(StarxExtension extension);

    /**
     * 注销特定扩展的命令
     * @param extension 扩展实例
     * @param commandName 命令名称
     */
    void unregisterCommand(StarxExtension extension, String commandName);

    /**
     * 获取命令帮助
     * @param commandName 命令名称
     * @return 命令帮助信息
     */
    Optional<CommandHelp> getCommandHelp(String commandName);

    /**
     * 扩展命令
     */
    interface ExtensionCommand {
        /**
         * 获取命令名称
         */
        String name();

        /**
         * 获取命令别名
         */
        java.util.List<String> aliases();

        /**
         * 获取命令描述
         */
        String description();

        /**
         * 获取命令用法
         */
        String usage();

        /**
         * 获取权限节点
         */
        String permission();

        /**
         * 执行命令
         * @param context 命令上下文
         */
        void execute(CommandContext context);

        /**
         * 获取补全建议
         * @param context 命令上下文
         * @return 补全建议列表
         */
        default java.util.List<String> tabComplete(CommandContext context) {
            return java.util.List.of();
        }
    }

    /**
     * 命令上下文
     */
    interface CommandContext {
        /**
         * 获取发送者
         */
        CommandSender sender();

        /**
         * 获取参数
         */
        String[] args();

        /**
         * 发送消息
         * @param message 消息
         */
        void sendMessage(String message);

        /**
         * 发送错误消息
         * @param message 消息
         */
        void sendErrorMessage(String message);

        /**
         * 发送成功消息
         * @param message 消息
         */
        void sendSuccessMessage(String message);

        /**
         * 检查权限
         * @param permission 权限
         * @return 是否有权限
         */
        boolean hasPermission(String permission);
    }

    /**
     * 命令发送者接口
     */
    interface CommandSender {
        /**
         * 发送者名称
         */
        String name();

        /**
         * 是否是控制台
         */
        boolean isConsole();

        /**
         * 发送消息
         * @param message 消息
         */
        void sendMessage(String message);
    }

    /**
     * 命令帮助信息
     *
     * @param name 命令名称
     * @param description 命令描述
     * @param usage 命令用法
     * @param aliases 命令别名列表
     * @param subcommands 子命令帮助列表
     */
    record CommandHelp(
        String name,
        String description,
        String usage,
        java.util.List<String> aliases,
        java.util.List<CommandSubcommand> subcommands
    ) {
        /**
         * 命令子命令帮助
         *
         * @param name 子命令名称
         * @param description 子命令描述
         * @param usage 子命令用法
         */
        record CommandSubcommand(
            String name,
            String description,
            String usage
        ) {}
    }
}