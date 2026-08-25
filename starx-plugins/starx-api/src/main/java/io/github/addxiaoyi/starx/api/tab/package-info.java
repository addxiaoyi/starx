/**
 * 标签页列表API
 * 
 * 提供玩家列表（Tab List）的完整功能，包括：
 * - 动画效果（流动、滚动、脉冲等）
 * - 图片嵌入和头像显示
 * - 自定义玩家条目
 * - 背景图片和颜色
 * 
 * <h2>基本用法</h2>
 * <pre>{@code
 * // 创建标签页列表
 * TabList tabList = TabList.builder()
 *     .header(Component.text("欢迎光临"))
 *     .footer(Component.text("服务器名称"))
 *     .build();
 * 
 * // 设置玩家条目
 * tabList.setPlayerEntry("player-uuid", 
 *     TabPlayerEntry.builder(Component.text("[§a管理§r] 玩家名"))
 *         .prefix(Component.text("§7[§aOP§7] "))
 *         .sortOrder(0)
 *         .build());
 * }</pre>
 * 
 * <h2>动画效果</h2>
 * <pre>{@code
 * // 创建流动动画
 * TabAnimation animation = TabAnimation.flowing(
 *     List.of(
 *         Component.text(" §e §c §6 §b §d §a §9 §3 §f §5 §6 §2 §7 §4 §8 "),
 *         Component.text("§e§c§6§b§d§a§9§3§f§5§6§2§7§4§8 ")
 *     ), 
 *     1000
 * );
 * 
 * tabList.setHeaderAnimation(animation);
 * }</pre>
 */
package io.github.addxiaoyi.starx.api.tab;