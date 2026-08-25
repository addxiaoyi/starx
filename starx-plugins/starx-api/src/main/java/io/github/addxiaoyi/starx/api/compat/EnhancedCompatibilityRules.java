package io.github.addxiaoyi.starx.api.compat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 增强的兼容性规则，支持自动补全和动态检查。
 * 扩展了标准的 CompatibilityRules，增加了对 IDE 自动补全的支持。
 */
public final class EnhancedCompatibilityRules {

  private static final Pattern VERSION = Pattern.compile("(?<!\\d)(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?");
  private static final Pattern VELOCITY_BUILD = Pattern.compile("(?:build\\s*|[-.]b)(\\d+)", Pattern.CASE_INSENSITIVE);

  /** 自动补全上下文：兼容性 */
  public static final String CONTEXT_COMPATIBILITY = "compatibility";
  /** 自动补全上下文：平台 */
  public static final String CONTEXT_PLATFORM = "platform";
  /** 自动补全上下文：集成 */
  public static final String CONTEXT_INTEGRATION = "integration";

  /** 支持的 Java 版本列表 */
  public static final List<String> SUPPORTED_JAVA_VERSIONS = List.of("21", "25");

  /** 支持的 Velocity 版本列表 */
  public static final List<String> SUPPORTED_VELOCITY_VERSIONS = List.of("3.5.0", "3.5.0-SNAPSHOT");

  /** 支持的 Minecraft 版本列表 */
  public static final List<String> SUPPORTED_MINECRAFT_VERSIONS = List.of(
      "1.21.0", "1.21.1", "1.21.2", "1.21.3", "1.21.4", "1.21.5", "1.21.6", "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11",
      "1.21",
      "26.1.0", "26.1.1", "26.1.2", "26.1.3", "26.1.4",
      "26.2.0", "26.2.1", "26.2.2"
  );

  /** 支持的插件集成列表，键为插件ID，值为支持的版本列表 */
  public static final java.util.Map<String, java.util.List<String>> SUPPORTED_INTEGRATIONS = java.util.Map.of(
      "luckperms", List.of("5.0", "5.1", "5.2", "5.3", "5.4", "5.5", "5.x"),
      "floodgate", List.of("2.0", "2.1", "2.2", "2.x"),
      "tab", List.of("5.0", "5.1", "5.2", "6.0", "6.1", "6.2", "5.x", "6.x"),
      "plan", List.of("5.0", "5.1", "5.2", "5.3", "5.4", "5.5", "5.x"),
      "geyser", List.of("2.0", "2.1", "2.2", "2.x"),
      "skinsrestorer", List.of("15.0", "15.1", "15.2", "15.x"),
      "placeholderapi", List.of("2.11.0", "2.11.1", "2.11.2", "2.12.0", "2.12.1", "2.13.0", ">=2.11,<3"),
      "raknetify", List.of("latest", "presence-only")
  );

  private EnhancedCompatibilityRules() {
  }

  /**
   * 获取 Java 运行时的自动补全建议。
   *
   * @param input 当前输入
   * @return 补全建议数组
   */
  public static String[] javaCompletions(String input) {
    if (input == null || input.isBlank()) {
      return SUPPORTED_JAVA_VERSIONS.toArray(new String[0]);
    }
    String normalized = input.trim().toLowerCase(Locale.ROOT);
    List<String> results = new ArrayList<>();
    for (String version : SUPPORTED_JAVA_VERSIONS) {
      if (version.toLowerCase(Locale.ROOT).contains(normalized)) {
        results.add(version);
      }
    }
    return results.toArray(new String[0]);
  }

  /**
   * 获取 Velocity 运行时的自动补全建议。
   *
   * @param input 当前输入
   * @return 补全建议数组
   */
  public static String[] velocityCompletions(String input) {
    if (input == null || input.isBlank()) {
      return SUPPORTED_VELOCITY_VERSIONS.toArray(new String[0]);
    }
    String normalized = input.trim().toLowerCase(Locale.ROOT);
    List<String> results = new ArrayList<>();
    for (String version : SUPPORTED_VELOCITY_VERSIONS) {
      if (version.toLowerCase(Locale.ROOT).contains(normalized)) {
        results.add(version);
      }
    }
    // 添加构建号建议
    if (normalized.startsWith("3.5.0")) {
      results.add("3.5.0 build 606");
    }
    return results.toArray(new String[0]);
  }

  /**
   * 获取 Minecraft 版本的自动补全建议。
   *
   * @param input 当前输入
   * @return 补全建议数组
   */
  public static String[] minecraftCompletions(String input) {
    if (input == null || input.isBlank()) {
      return SUPPORTED_MINECRAFT_VERSIONS.toArray(new String[0]);
    }
    String normalized = input.trim().toLowerCase(Locale.ROOT);
    List<String> results = new ArrayList<>();
    for (String version : SUPPORTED_MINECRAFT_VERSIONS) {
      if (version.toLowerCase(Locale.ROOT).contains(normalized)) {
        results.add(version);
      }
    }
    return results.toArray(new String[0]);
  }

  /**
   * 获取插件集成的自动补全建议。
   *
   * @param input 当前输入
   * @return 补全建议数组
   */
  public static String[] integrationCompletions(String input) {
    if (input == null || input.isBlank()) {
      List<String> all = new ArrayList<>();
      SUPPORTED_INTEGRATIONS.keySet().forEach(plugin -> {
        all.add(plugin);
        all.addAll(SUPPORTED_INTEGRATIONS.get(plugin));
      });
      return all.toArray(new String[0]);
    }
    String normalized = input.trim().toLowerCase(Locale.ROOT);
    List<String> results = new ArrayList<>();
    
    // 匹配插件名称
    for (String plugin : SUPPORTED_INTEGRATIONS.keySet()) {
      if (plugin.toLowerCase(Locale.ROOT).contains(normalized)) {
        results.add(plugin);
        results.addAll(SUPPORTED_INTEGRATIONS.get(plugin));
      }
    }
    
    // 匹配版本号
    for (List<String> versions : SUPPORTED_INTEGRATIONS.values()) {
      for (String version : versions) {
        if (version.toLowerCase(Locale.ROOT).contains(normalized)) {
          results.add(version);
        }
      }
    }
    
    return results.toArray(new String[0]);
  }

  /**
   * 获取所有兼容性上下文的自动补全。
   *
   * @param context 上下文类型
   * @param input   当前输入
   * @return 补全建议数组
   */
  public static String[] completions(String context, String input) {
    String ctx = context.toLowerCase(Locale.ROOT);
    if (ctx.equals(CONTEXT_COMPATIBILITY) || ctx.equals("java")) {
      return javaCompletions(input);
    }
    if (ctx.equals("velocity")) {
      return velocityCompletions(input);
    }
    if (ctx.equals("minecraft") || ctx.equals("mc")) {
      return minecraftCompletions(input);
    }
    if (ctx.equals(CONTEXT_INTEGRATION) || ctx.equals("plugin")) {
      return integrationCompletions(input);
    }
    return new String[0];
  }

  /**
   * 获取兼容性检查的说明文档。
   *
   * @param context 上下文类型
   * @param value   补全值
   * @return 说明文档
   */
  public static String documentation(String context, String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    
    return switch (context.toLowerCase(Locale.ROOT)) {
      case "java" -> switch (normalized) {
        case "21" -> "Java 21: StarX 的主要字节码基线，完全支持";
        case "25" -> "Java 25: Paper 26.x 运行时基线，完全支持";
        default -> "Java 版本: StarX 需要 Java 21 或 25";
      };
      
      case "velocity" -> switch (normalized) {
        case "3.5.0", "3.5.0-snapshot" -> "Velocity 3.5.0: Uworld 使用的内部 API 基线";
        case "3.5.0 build 606" -> "Velocity 3.5.0 build 606: 经过验证的 Uworld 内部 API 构建";
        default -> "Velocity 版本: StarX 需要 Velocity 3.5.0 build 606";
      };
      
      case "minecraft", "mc" -> {
        if (normalized.startsWith("1.21")) {
          yield "Minecraft 1.21.x: 完全支持的后端基线";
        } else if (normalized.startsWith("26.")) {
          yield "Minecraft 26.x (Paper/Folia): 完全支持的服务器版本";
        } else {
          yield "Minecraft 版本: StarX 支持 1.21.x 和 26.x 系列";
        }
      }
      
      case CONTEXT_INTEGRATION, "plugin" -> {
        String plugin = normalized.split("[\\s]+")[0];
        if (SUPPORTED_INTEGRATIONS.containsKey(plugin)) {
          yield "插件集成: " + plugin + " - 支持的版本: " + String.join(", ", SUPPORTED_INTEGRATIONS.get(plugin));
        } else {
          yield "插件集成: " + value + " - 可选的第三方插件";
        }
      }
      
      default -> null;
    };
  }

  /**
   * 获取所有可用的兼容性上下文。
   *
   * @return 上下文列表
   */
  public static List<String> availableContexts() {
    return List.of(
        CONTEXT_COMPATIBILITY,
        CONTEXT_PLATFORM,
        CONTEXT_INTEGRATION,
        "java",
        "velocity", 
        "minecraft",
        "mc",
        "plugin"
    );
  }

  /**
   * 验证 Java 版本是否支持。
   *
   * @param version Java 版本字符串
   * @return 是否支持
   */
  public static boolean isJavaSupported(String version) {
    int major = javaMajor(version);
    return major == 21 || major == 25;
  }

  /**
   * 验证 Velocity 版本是否支持。
   *
   * @param version Velocity 版本字符串
   * @return 是否支持
   */
  public static boolean isVelocitySupported(String version) {
    int[] parsed = versionParts(version);
    if (parsed == null) {
      return false;
    }
    if (parsed[0] != 3 || parsed[1] != 5 || parsed[2] != 0) {
      return false;
    }
    Matcher matcher = VELOCITY_BUILD.matcher(normalize(version));
    if (!matcher.find()) {
      return false;
    }
    try {
      int build = Integer.parseInt(matcher.group(1));
      return build == 606;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  /**
   * 验证 Minecraft 版本是否支持。
   *
   * @param version Minecraft 版本字符串
   * @return 是否支持
   */
  public static boolean isMinecraftSupported(String version) {
    int[] parsed = versionParts(version);
    if (parsed == null) {
      return false;
    }
    // 1.21.x 系列
    if (parsed[0] == 1 && parsed[1] == 21) {
      return parsed[2] <= 11;
    }
    // 26.x 系列
    if (parsed[0] == 26) {
      return parsed[1] == 1 || parsed[1] == 2;
    }
    return false;
  }

  /**
   * 验证插件集成是否支持。
   *
   * @param plugin   插件名称
   * @param version  插件版本
   * @return 是否支持
   */
  public static boolean isIntegrationSupported(String plugin, String version) {
    if (plugin == null || version == null) {
      return false;
    }
    String key = plugin.toLowerCase(Locale.ROOT);
    int[] parsed = versionParts(version);
    if (parsed == null) {
      return false;
    }
    
    return switch (key) {
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
  }

  // 以下是从 CompatibilityRules 复制的辅助方法

  private static int javaMajor(String version) {
    int[] parts = versionParts(version);
    if (parts == null) {
      return -1;
    }
    return parts[0] == 1 && parts[1] > 0 ? parts[1] : parts[0];
  }

  private static int[] versionParts(String version) {
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

  private static int parseOptional(String value) {
    return value == null ? 0 : Integer.parseInt(value);
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim();
  }
}
