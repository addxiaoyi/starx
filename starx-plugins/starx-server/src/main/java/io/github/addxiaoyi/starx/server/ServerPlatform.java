package io.github.addxiaoyi.starx.server;

import io.github.addxiaoyi.starx.api.bridge.PlatformKind;

public enum ServerPlatform {
  PAPER(PlatformKind.PAPER, "main-thread"),
  FOLIA(PlatformKind.FOLIA, "regionized");

  static final String FOLIA_SERVER_CLASS = "io.papermc.paper.threadedregions.RegionizedServer";
  static final String PAPER_SERVER_CLASS = "io.papermc.paper.configuration.GlobalConfiguration";

  private final PlatformKind bridgeKind;
  private final String executionModel;

  ServerPlatform(PlatformKind bridgeKind, String executionModel) {
    this.bridgeKind = bridgeKind;
    this.executionModel = executionModel;
  }

  public PlatformKind bridgeKind() {
    return this.bridgeKind;
  }

  public String executionModel() {
    return this.executionModel;
  }

  public static ServerPlatform detect() {
    return detect(name -> {
      try {
        Class.forName(name, false, ServerPlatform.class.getClassLoader());
        return true;
      } catch (ClassNotFoundException ignored) {
        return false;
      }
    });
  }

  static ServerPlatform detect(ClassProbe probe) {
    if (probe.isPresent(FOLIA_SERVER_CLASS)) {
      return FOLIA;
    }
    if (probe.isPresent(PAPER_SERVER_CLASS)) {
      return PAPER;
    }
    throw new IllegalStateException("StarXServer requires Paper or Folia");
  }

  @FunctionalInterface
  interface ClassProbe {
    boolean isPresent(String className);
  }
}
