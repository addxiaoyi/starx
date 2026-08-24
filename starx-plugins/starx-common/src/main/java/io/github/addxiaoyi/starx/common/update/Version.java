package io.github.addxiaoyi.starx.common.update;

import java.util.Objects;

/**
 * 语义化版本比较器。
 * 支持 "1.2.3"、"1.2.3-SNAPSHOT"、"1.2.3+build.5" 等常见格式。
 */
public final class Version implements Comparable<Version> {
  private final int major;
  private final int minor;
  private final int patch;
  private final String prerelease;
  private final String raw;

  private Version(int major, int minor, int patch, String prerelease, String raw) {
    this.major = major;
    this.minor = minor;
    this.patch = patch;
    this.prerelease = prerelease == null ? "" : prerelease;
    this.raw = raw;
  }

  /**
   * 解析版本字符串；无法解析时返回 null。
   */
  public static Version parse(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    // 去掉开头的 "v" 前缀（GitHub Releases 常见格式）
    String text = value.trim();
    if (text.startsWith("v") || text.startsWith("V")) {
      text = text.substring(1);
    }
    // 分离构建元数据
    int plus = text.indexOf('+');
    if (plus >= 0) {
      text = text.substring(0, plus);
    }
    // 分离预发布标识
    String prerelease = null;
    int dash = text.indexOf('-');
    if (dash >= 0) {
      prerelease = text.substring(dash + 1);
      text = text.substring(0, dash);
    }
    String[] parts = text.split("\\.");
    if (parts.length < 1 || parts.length > 3) {
      return null;
    }
    try {
      int major = Integer.parseInt(parts[0]);
      int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
      int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
      if (major < 0 || minor < 0 || patch < 0) {
        return null;
      }
      return new Version(major, minor, patch, prerelease, value.trim());
    } catch (NumberFormatException error) {
      return null;
    }
  }

  public boolean isPrerelease() {
    return !this.prerelease.isEmpty();
  }

  @Override
  public int compareTo(Version other) {
    int result = Integer.compare(this.major, other.major);
    if (result != 0) {
      return result;
    }
    result = Integer.compare(this.minor, other.minor);
    if (result != 0) {
      return result;
    }
    result = Integer.compare(this.patch, other.patch);
    if (result != 0) {
      return result;
    }
    // 正式版 > 预发布版
    if (this.isPrerelease() && !other.isPrerelease()) {
      return -1;
    }
    if (!this.isPrerelease() && other.isPrerelease()) {
      return 1;
    }
    return this.prerelease.compareTo(other.prerelease);
  }

  public boolean isNewerThan(Version other) {
    return other == null || this.compareTo(other) > 0;
  }

  public String raw() {
    return this.raw;
  }

  @Override
  public boolean equals(Object obj) {
    return obj instanceof Version other && this.compareTo(other) == 0;
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.major, this.minor, this.patch, this.prerelease);
  }

  @Override
  public String toString() {
    return this.raw;
  }
}
