package io.github.addxiaoyi.starx.server;

import io.github.addxiaoyi.starx.api.bridge.PlatformKind;

public enum ServerPlatform {
  PAPER(PlatformKind.PAPER, "main-thread"),
  FOLIA(PlatformKind.FOLIA, "regionized");

  static final String FOLIA_SERVER_CLASS = "io.papermc.paper.threadedregions.RegionizedServer";

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
    return probe.isPresent(FOLIA_SERVER_CLASS) ? FOLIA : PAPER;
  }

  @FunctionalInterface
  interface ClassProbe {
    boolean isPresent(String className);
  }
}
