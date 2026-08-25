package io.github.addxiaoyi.starx.api.compat;

import java.util.Objects;

/**
 * 增强的兼容性检查配置。
 * 支持动态检查和更详细的诊断信息。
 *
 * @param id                唯一检查标识符
 * @param component         组件名称
 * @param detectedVersion   检测到的版本
 * @param supportedRange    支持的版本范围
 * @param status            兼容性状态
 * @param message           诊断消息
 * @param checkDurationMs   检查耗时（毫秒）
 * @param metadata          额外的元数据
 */
public record EnhancedCompatibilityCheck(
    String id,
    String component,
    String detectedVersion,
    String supportedRange,
    CompatibilityStatus status,
    String message,
    long checkDurationMs,
    java.util.Map<String, String> metadata
) {
  /**
   * 创建一个标准兼容性检查（无额外元数据）。
   */
  public static EnhancedCompatibilityCheck of(
      String id,
      String component,
      String detectedVersion,
      String supportedRange,
      CompatibilityStatus status,
      String message
  ) {
    return new EnhancedCompatibilityCheck(
        id, component, detectedVersion, supportedRange, status, message, 0L, null
    );
  }

  /**
   * 创建一个带执行时间的兼容性检查。
   */
  public static EnhancedCompatibilityCheck withDuration(
      String id,
      String component,
      String detectedVersion,
      String supportedRange,
      CompatibilityStatus status,
      String message,
      long durationMs
  ) {
    return new EnhancedCompatibilityCheck(
        id, component, detectedVersion, supportedRange, status, message, durationMs, null
    );
  }

  /**
   * 转换为标准兼容性检查。
   */
  public CompatibilityCheck toCheck() {
    return new CompatibilityCheck(id, component, detectedVersion, supportedRange, status, message);
  }

/**
   * Reports the severity level of this compatibility check.
   *
   * @return severity (0=SUPPORTED, 1=UNKNOWN, 2=DEGRADED, 3=UNSUPPORTED)
   */
  public int severityLevel() {
    return switch (status) {
      case SUPPORTED -> 0;
      case UNKNOWN -> 1;
      case DEGRADED -> 2;
      case UNSUPPORTED -> 3;
    };
  }

  /**
   * 获取简短的诊断代码。
   */
  public String diagnosticCode() {
    return component.toUpperCase().replace("-", "_") + "_" + status.name();
  }

  /**
   * 获取机器可读的诊断信息。
   */
  public String toDiagnosticString() {
    StringBuilder sb = new StringBuilder();
    sb.append("[").append(diagnosticCode()).append("] ");
    sb.append(component).append(" v").append(detectedVersion);
    sb.append(" - ").append(message);
    if (checkDurationMs > 0) {
      sb.append(" (检查耗时: ").append(checkDurationMs).append("ms)");
    }
    return sb.toString();
  }
}
