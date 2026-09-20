package io.github.addxiaoyi.starx.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

final class OfflineBuildContractTest {

  private static final String LIMBO_VENDOR_SHA256 =
      "18AC6287D413234C4FC317267A6D5DBF978ADAE8BF3F098A1248966BF2C32CE9";
  private static final String VELOCITY_FASTUTIL_SHA256 =
      "9094AE67D01D0AD246F886F11AD557FC2E79C72CBF3FEED83E1512A8AE90A74A";

  @Test
  void limboBuildUsesOnlyPinnedVendoredElytriumRuntime() throws Exception {
    Path root = repositoryRoot();
    Path limboBuild = root.resolve("starx-plugins/starx-standalone-limbo/build.gradle.kts");
    String rootBuildText = Files.readString(root.resolve("build.gradle.kts"));
    String limboBuildText = Files.readString(limboBuild);

    assertFalse(rootBuildText.contains("maven.elytrium.net"),
        "构建不得依赖 Elytrium 在线 Maven 仓库");
    assertFalse(limboBuildText.contains("implementation(\"net.elytrium"),
        "Limbo 依赖必须来自已固定哈希的本地 vendor 构件");
    assertTrue(limboBuildText.contains(LIMBO_VENDOR_SHA256),
        "Limbo vendor 构件必须在 Gradle 中固定 SHA-256");

    Path vendor = root.resolve(
        "starx-plugins/starx-standalone-limbo/vendor/limboapi-1.1.27-SNAPSHOT.jar");
    assertTrue(Files.isRegularFile(vendor), "缺少 Limbo vendor 构件: " + vendor);
    assertEquals(LIMBO_VENDOR_SHA256, sha256(vendor), "Limbo vendor 构件哈希不匹配");
  }

  @Test
  void velocityRelocatesVendoredLimboLibrariesIntoStarxNamespace() throws Exception {
    Path root = repositoryRoot();
    String velocityBuildText = Files.readString(
        root.resolve("starx-plugins/starx-velocity/build.gradle.kts"));

    assertTrue(velocityBuildText.contains(
            "relocate(\"net.elytrium.limboapi.thirdparty.commons\", "
                + "\"io.github.addxiaoyi.starx.limbo.thirdparty.commons\")"),
        "Velocity JAR 必须把 vendored commons 重定位到 StarX 私有命名空间");
    assertTrue(velocityBuildText.contains(
            "relocate(\"net.elytrium.limboapi.thirdparty.fastprepare\", "
                + "\"io.github.addxiaoyi.starx.limbo.thirdparty.fastprepare\")"),
        "Velocity JAR 必须把 vendored fastprepare 重定位到 StarX 私有命名空间");
  }

  @Test
  void standaloneLimboCompilesAgainstPinnedVelocityCoreLibraries() throws Exception {
    Path root = repositoryRoot();
    String rootBuildText = Files.readString(root.resolve("build.gradle.kts"));
    String limboBuildText = Files.readString(
        root.resolve("starx-plugins/starx-standalone-limbo/build.gradle.kts"));
    List<String> coreCoordinates = List.of(
        "io.netty:",
        "net.kyori:",
        "com.google.code.gson:",
        "it.unimi.dsi:",
        "com.github.spotbugs:",
        "org.checkerframework:",
        "org.jetbrains:",
        "org.slf4j:");

    coreCoordinates.forEach(coordinate -> assertFalse(limboBuildText.contains(coordinate),
        () -> "Velocity 核心库不得通过 Maven 解析: " + coordinate));

    Path fastutil = root.resolve("vendor/velocity/fastutil-8.5.18.jar");
    assertTrue(rootBuildText.contains(VELOCITY_FASTUTIL_SHA256),
        "Velocity fastutil 编译构件必须在 Gradle 中固定 SHA-256");
    assertTrue(Files.isRegularFile(fastutil), "缺少 Velocity fastutil 编译构件: " + fastutil);
    assertEquals(VELOCITY_FASTUTIL_SHA256, sha256(fastutil),
        "Velocity fastutil 编译构件哈希不匹配");
  }

  @Test
  void velocityCompilesAgainstPinnedCoreLibrariesWithoutMavenCoordinates() throws Exception {
    Path root = repositoryRoot();
    String velocityBuildText = Files.readString(
        root.resolve("starx-plugins/starx-velocity/build.gradle.kts"));
    List<String> coreCoordinates = List.of(
        "io.netty:",
        "it.unimi.dsi:",
        "com.google.inject:",
        "org.slf4j:",
        "org.yaml:",
        "net.kyori:adventure-text-minimessage",
        "net.kyori:adventure-text-serializer-plain",
        "net.kyori:adventure-nbt",
        "com.google.code.gson:");

    coreCoordinates.forEach(coordinate -> assertFalse(velocityBuildText.contains(coordinate),
        () -> "Velocity 核心库不得在插件模块中解析 Maven 坐标: " + coordinate));
  }

  @Test
  void vendoredFastutilIncludesLicenseAndDistributionNotice() throws Exception {
    Path root = repositoryRoot();
    Path license = root.resolve("LICENSES/Apache-2.0.txt");
    String notice = Files.readString(root.resolve("NOTICE"));

    assertTrue(Files.isRegularFile(license), "缺少 fastutil 的 Apache-2.0 许可证文本");
    String licenseText = Files.readString(license).replace("\r\n", "\n");
    assertTrue(licenseText.contains("Apache License\nVersion 2.0, January 2004"),
        "Apache-2.0 许可证文本不完整");
    assertTrue(notice.contains("fastutil 8.5.18"),
        "NOTICE 必须标明 vendored fastutil 的名称和版本");
    assertTrue(notice.contains("Apache License, Version 2.0"),
        "NOTICE 必须标明 fastutil 的许可证");
    assertTrue(notice.contains("compile and test input only"),
        "NOTICE 必须声明 fastutil 不进入 StarX 运行时 JAR");
  }

  private static String sha256(Path file) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (var input = Files.newInputStream(file)) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = input.read(buffer)) >= 0) {
        digest.update(buffer, 0, read);
      }
    }
    return HexFormat.of().withUpperCase().formatHex(digest.digest());
  }

  private static Path repositoryRoot() {
    Path workingDir = Path.of("").toAbsolutePath().normalize();
    if (Files.isDirectory(workingDir.resolve("starx-plugins/starx-common"))) {
      return workingDir;
    }
    Path parent = workingDir;
    while (parent != null) {
      if (Files.isDirectory(parent.resolve("starx-plugins/starx-common"))) {
        return parent;
      }
      parent = parent.getParent();
    }
    throw new IllegalStateException("无法定位 StarX 仓库，当前目录: " + workingDir);
  }
}
