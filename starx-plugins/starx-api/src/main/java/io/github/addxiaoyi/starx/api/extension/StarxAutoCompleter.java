package io.github.addxiaoyi.starx.api.extension;

import java.util.List;

/**
 * StarX 自动补全器接口。
 * 扩展可以实现此接口，为特定上下文提供自动补全功能。
 */
public interface StarxAutoCompleter {

  /**
   * 获取自动补全器的唯一标识符。
   *
   * @return 唯一标识符
   */
  String id();

  /**
   * 获取自动补全器的显示名称。
   *
   * @return 显示名称
   */
  String displayName();

  /**
   * 获取自动补全器的描述。
   *
   * @return 描述
   */
  default String description() {
    return "";
  }

  /**
   * 获取自动补全器的优先级（数值越小，优先级越高）。
   *
   * @return 优先级
   */
  default int priority() {
    return 100;
  }

  /**
   * 获取自动补全器支持的上下文。
   *
   * @return 支持的上下文列表
   */
  List<String> contexts();

  /**
   * 为给定的输入提供补全建议。
   *
   * @param input 输入文本
   * @return 补全建议列表
   */
  List<String> complete(String input);

  /**
   * 为给定的输入和光标位置提供补全建议。
   *
   * @param input  输入文本
   * @param cursor 光标位置
   * @return 补全建议列表
   */
  default List<String> complete(String input, int cursor) {
    return complete(input);
  }

  /**
   * 检查是否可以为给定的输入提供补全。
   *
   * @param input 输入文本
   * @return 是否可以提供补全
   */
  default boolean canComplete(String input) {
    return true;
  }

  /**
   * 获取补全建议的文档说明。
   *
   * @param suggestion 补全建议
   * @return 文档说明（可为 null）
   */
  default String documentation(String suggestion) {
    return null;
  }

  /**
   * 检查自动补全器是否已启用。
   *
   * @return 是否启用
   */
  default boolean isEnabled() {
    return true;
  }

  /**
   * 启用自动补全器。
   */
  default void enable() {
    // do nothing
  }

  /**
   * 禁用自动补全器。
   */
  default void disable() {
    // do nothing
  }
}
