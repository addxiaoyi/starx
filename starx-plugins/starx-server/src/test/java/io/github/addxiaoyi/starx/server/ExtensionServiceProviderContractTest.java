package io.github.addxiaoyi.starx.server;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.api.extension.StarxServiceProvider;
import org.junit.jupiter.api.Test;

class ExtensionServiceProviderContractTest {
  @Test
  void backendEntrypointExposesStableProviderContract() {
    assertTrue(StarxServiceProvider.class.isAssignableFrom(StarxServerPlugin.class));
  }
}
