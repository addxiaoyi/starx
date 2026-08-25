package io.github.addxiaoyi.starx.api.extension;

import io.github.addxiaoyi.starx.api.compat.EnhancedCompatibilityRules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * StarX 默认的自动补全器实现。
 * 提供对兼容性相关上下文的自动补全支持。
 */
public class DefaultStarxAutoCompleter implements StarxAutoCompleter {

  private final String id;
  private final String displayName;
  private final String description;
  private final int priority;
  private boolean enabled = true;

  /**
   * 创建一个默认的 StarX 自动补全器。
   *
   * @param id          唯一标识符
   * @param displayName 显示名称
   * @param description 描述
   * @param priority    优先级
   */
  public DefaultStarxAutoCompleter(String id, String displayName, String description, int priority) {
    this.id = id;
    this.displayName = displayName;
    this.description = description;
    this.priority = priority;
  }

  /**
   * 创建一个 Java 版本自动补全器。
   */
  public static DefaultStarxAutoCompleter javaVersionCompleter() {
    return new DefaultStarxAutoCompleter(
        "starx.java.version",
        "Java 版本",
        "为 StarX 提供 Java 版本自动补全",
        10
    );
  }

  /**
   * 创建一个 Velocity 版本自动补全器。
   */
  public static DefaultStarxAutoCompleter velocityVersionCompleter() {
    return new DefaultStarxAutoCompleter(
        "starx.velocity.version",
        "Velocity 版本",
        "为 StarX 提供 Velocity 版本自动补全",
        20
    );
  }

  /**
   * 创建一个 Minecraft 版本自动补全器。
   */
  public static DefaultStarxAutoCompleter minecraftVersionCompleter() {
    return new DefaultStarxAutoCompleter(
        "starx.minecraft.version",
        "Minecraft 版本",
        "为 StarX 提供 Minecraft 版本自动补全",
        30
    );
  }

  /**
   * 创建一个插件集成自动补全器。
   */
  public static DefaultStarxAutoCompleter integrationCompleter() {
    return new DefaultStarxAutoCompleter(
        "starx.integration",
        "插件集成",
        "为 StarX 提供插件集成自动补全",
        40
    );
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public String displayName() {
    return displayName;
  }

  @Override
  public String description() {
    return description;
  }

  @Override
  public int priority() {
    return priority;
  }

  @Override
  public List<String> contexts() {
    return switch (id) {
      case "starx.java.version" -> List.of("java", "runtime", EnhancedCompatibilityRules.CONTEXT_PLATFORM);
      case "starx.velocity.version" -> List.of("velocity", "proxy", EnhancedCompatibilityRules.CONTEXT_PLATFORM);
      case "starx.minecraft.version" -> List.of("minecraft", "mc", "server", EnhancedCompatibilityRules.CONTEXT_PLATFORM);
      case "starx.integration" -> List.of("plugin", "integration", EnhancedCompatibilityRules.CONTEXT_INTEGRATION);
      default -> List.of();
    };
  }

  @Override
  public List<String> complete(String input) {
    if (!enabled || input == null) {
      return List.of();
    }

    String normalized = input.trim().toLowerCase(Locale.ROOT);
    
    return switch (id) {
      case "starx.java.version" -> Arrays.asList(EnhancedCompatibilityRules.javaCompletions(normalized));
      case "starx.velocity.version" -> Arrays.asList(EnhancedCompatibilityRules.velocityCompletions(normalized));
      case "starx.minecraft.version" -> Arrays.asList(EnhancedCompatibilityRules.minecraftCompletions(normalized));
      case "starx.integration" -> Arrays.asList(EnhancedCompatibilityRules.integrationCompletions(normalized));
      default -> List.of();
    };
  }

  @Override
  public String documentation(String suggestion) {
    if (suggestion == null || suggestion.isBlank()) {
      return null;
    }

    return switch (id) {
      case "starx.java.version" -> EnhancedCompatibilityRules.documentation("java", suggestion);
      case "starx.velocity.version" -> EnhancedCompatibilityRules.documentation("velocity", suggestion);
      case "starx.minecraft.version" -> EnhancedCompatibilityRules.documentation("minecraft", suggestion);
      case "starx.integration" -> EnhancedCompatibilityRules.documentation("plugin", suggestion);
      default -> null;
    };
  }

  @Override
  public boolean isEnabled() {
    return enabled;
  }

  @Override
  public void enable() {
    this.enabled = true;
  }

  @Override
  public void disable() {
    this.enabled = false;
  }

  /**
   * 创建所有默认的自动补全器。
   *
   * @return 默认自动补全器列表
   */
  public static List<DefaultStarxAutoCompleter> createAll() {
    return List.of(
        javaVersionCompleter(),
        velocityVersionCompleter(),
        minecraftVersionCompleter(),
        integrationCompleter()
    );
  }
}
