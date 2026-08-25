package io.github.addxiaoyi.starx.common.security;

import java.time.Duration;

/**
 * StarX HTTP 客户端共享常量。
 * 统一不同模块的超时配置，避免硬编码分散。
 */
public final class HttpConstants {
  private HttpConstants() {
  }

  /** 默认连接超时：5 秒 */
  public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);

  /** 默认请求超时：10 秒 */
  public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);

  /** 更新检查专用较长超时：60 秒 */
  public static final Duration UPDATE_REQUEST_TIMEOUT = Duration.ofSeconds(60);

  /** 更新下载专用更长超时：10 秒 */
  public static final Duration UPDATE_CONNECT_TIMEOUT = Duration.ofSeconds(10);
}
