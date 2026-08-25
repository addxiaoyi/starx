package io.github.addxiaoyi.starx.api.extension;

/**
 * StarX 扩展自动补全提供器接口。
 * 扩展可以实现此接口，向 IDE 注册自动补全器。
 */
public interface StarxAutoCompleterProvider {

  /**
   * 注册补全器。
   *
   * @param registry 补全器注册表
   */
  void registerAutoCompleters(StarxAutoCompleterRegistry registry);

  /**
   * 卸载补全器。
   *
   * @param registry 补全器注册表
   */
  default void unregisterAutoCompleters(StarxAutoCompleterRegistry registry) {
    // do nothing
  }
}
