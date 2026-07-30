package io.github.addxiaoyi.starx.api.compat;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The explicitly certified StarX platform and integration compatibility matrix. */
public final class CompatibilityRules {
  private static final Pattern VERSION = Pattern.compile("(?<!\\d)(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?");
  private static final Pattern VELOCITY_BUILD = Pattern.compile("(?:build\\s*|[-.]b)(\\d+)", Pattern.CASE_INSENSITIVE);

  private CompatibilityRules() {
  }

  /**
   * Evaluates the Java runtime against the certified StarX bytecode baseline.
   *
   * @param version detected Java version
   * @return compatibility decision for Java
   */
  public static CompatibilityCheck javaRuntime(String version) {
    int major = javaMajor(version);
    if (major < 0) {
      return unknown("java", "Java", version, "21", "无法解析 Java 主版本");
    }
    if (major < 21) {
      return unsupported("java", "Java", version, "21", "StarX 使用 Java 21 字节码");
    }
    if (major == 21) {
      return supported("java", "Java", version, "21", "已认证运行时");
    }
    return degraded("java", "Java", version, "21", "较新的 Java 可运行但尚未纳入认证矩阵");
  }

  /**
   * Evaluates Velocity, including the internal API build used by Uworld.
   *
   * @param version detected Velocity version string
   * @return compatibility decision for Velocity
   */
  public static CompatibilityCheck velocityRuntime(String version) {
    int[] parsed = versionParts(version);
    if (parsed == null) {
      return unknown("velocity", "Velocity", version, "3.5.0 build 606", "无法解析 Velocity 版本");
    }
    if (parsed[0] != 3 || parsed[1] != 5 || parsed[2] != 0) {
      return unsupported("velocity", "Velocity", version, "3.5.0 build 606",
          "Uworld 使用经过 build 606 验证的 Velocity 内部 API");
    }
    Matcher matcher = VELOCITY_BUILD.matcher(normalize(version));
    if (!matcher.find()) {
      return degraded("velocity", "Velocity", version, "3.5.0 build 606",
          "版本属于 3.5.0，但无法确认内部 API 构建号");
    }
    int build;
    try {
      build = Integer.parseInt(matcher.group(1));
    } catch (NumberFormatException error) {
      return degraded("velocity", "Velocity", version, "3.5.0 build 606",
          "版本属于 3.5.0，但构建号超出可解析范围");
    }
    return build == 606
        ? supported("velocity", "Velocity", version, "3.5.0 build 606", "Uworld 内部 API 基线匹配")
        : unsupported("velocity", "Velocity", version, "3.5.0 build 606",
            "检测到未认证的 Velocity 内部 API 构建 " + build);
  }

  /**
   * Evaluates the Minecraft protocol and server API baseline.
   *
   * @param version detected Minecraft version
   * @return compatibility decision for Minecraft
   */
  public static CompatibilityCheck minecraftRuntime(String version) {
    int[] parsed = versionParts(version);
    if (parsed == null) {
      return unknown("minecraft", "Minecraft", version, "1.21.0-1.21.11", "无法解析 Minecraft 版本");
    }
    if (parsed[0] != 1 || parsed[1] != 21) {
      return unsupported("minecraft", "Minecraft", version, "1.21.0-1.21.11",
          "插件 API 和协议基线为 Minecraft 1.21.x");
    }
    if (parsed[2] <= 11) {
      return supported("minecraft", "Minecraft", version, "1.21.0-1.21.11", "已认证 1.21 系列");
    }
    return degraded("minecraft", "Minecraft", version, "1.21.0-1.21.11",
        "新的 1.21 补丁版本需要补充真实服务器验收");
  }

  /**
   * Evaluates one optional integration using the certified major-version matrix.
   *
   * @param id stable integration identifier
   * @param displayName human-readable integration name
   * @param version detected version, or blank when the integration is absent
   * @return compatibility decision for the integration
   */
  public static CompatibilityCheck integration(String id, String displayName, String version) {
    String key = normalize(id).toLowerCase(Locale.ROOT);
    int[] parsed = versionParts(version);
    if (version == null || version.isBlank()) {
      return new CompatibilityCheck(key, displayName, "not-installed", certifiedRange(key),
          CompatibilityStatus.SUPPORTED, "可选集成未安装");
    }
    if (parsed == null) {
      return unknown(key, displayName, version, certifiedRange(key), "插件存在但版本格式无法解析");
    }
    boolean supported = switch (key) {
      case "luckperms" -> parsed[0] == 5;
      case "floodgate" -> parsed[0] == 2;
      case "tab" -> parsed[0] == 5 || parsed[0] == 6;
      case "plan" -> parsed[0] == 5;
      case "geyser" -> parsed[0] == 2;
      case "skinsrestorer" -> parsed[0] == 15;
      case "placeholderapi" -> parsed[0] == 2 && parsed[1] >= 11;
      case "raknetify" -> true;
      default -> false;
    };
    if (supported) {
      CompatibilityStatus status = "raknetify".equals(key)
          ? CompatibilityStatus.UNKNOWN : CompatibilityStatus.SUPPORTED;
      String message = status == CompatibilityStatus.SUPPORTED
          ? "版本位于 StarX 兼容主版本范围"
          : "仅验证存在性和安全降级，尚无稳定语义版本范围";
      return new CompatibilityCheck(key, displayName, version, certifiedRange(key), status, message);
    }
    return degraded(key, displayName, version, certifiedRange(key),
        "集成将保持软依赖和安全降级，但该主版本未认证");
  }

  /**
   * Extracts up to three numeric version components from an arbitrary version string.
   *
   * @param version version string to inspect
   * @return three numeric components, or {@code null} when no version can be parsed
   */
  public static int[] versionParts(String version) {
    Matcher matcher = VERSION.matcher(normalize(version));
    if (!matcher.find()) {
      return null;
    }
    try {
      return new int[] {
          Integer.parseInt(matcher.group(1)),
          parseOptional(matcher.group(2)),
          parseOptional(matcher.group(3))
      };
    } catch (NumberFormatException error) {
      return null;
    }
  }

  private static int javaMajor(String version) {
    int[] parts = versionParts(version);
    if (parts == null) {
      return -1;
    }
    return parts[0] == 1 && parts[1] > 0 ? parts[1] : parts[0];
  }

  private static int parseOptional(String value) {
    return value == null ? 0 : Integer.parseInt(value);
  }

  private static String certifiedRange(String id) {
    return switch (id) {
      case "luckperms" -> "5.x";
      case "floodgate" -> "2.x";
      case "tab" -> "5.x-6.x";
      case "plan" -> "5.x";
      case "geyser" -> "2.x";
      case "skinsrestorer" -> "15.x";
      case "placeholderapi" -> ">=2.11,<3";
      case "raknetify" -> "presence-only";
      default -> "unlisted";
    };
  }

  private static CompatibilityCheck supported(
      String id, String component, String detected, String range, String message
  ) {
    return new CompatibilityCheck(id, component, detected, range, CompatibilityStatus.SUPPORTED, message);
  }

  private static CompatibilityCheck degraded(
      String id, String component, String detected, String range, String message
  ) {
    return new CompatibilityCheck(id, component, detected, range, CompatibilityStatus.DEGRADED, message);
  }

  private static CompatibilityCheck unsupported(
      String id, String component, String detected, String range, String message
  ) {
    return new CompatibilityCheck(id, component, detected, range, CompatibilityStatus.UNSUPPORTED, message);
  }

  private static CompatibilityCheck unknown(
      String id, String component, String detected, String range, String message
  ) {
    return new CompatibilityCheck(id, component, detected, range, CompatibilityStatus.UNKNOWN, message);
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim();
  }
}
