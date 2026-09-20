package io.github.addxiaoyi.starx.velocity;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.api.extension.StarxServiceProvider;
import org.junit.jupiter.api.Test;

class ExtensionServiceProviderContractTest {
  @Test
  void velocityEntrypointExposesStableProviderContract() {
    assertTrue(StarxServiceProvider.class.isAssignableFrom(StarxVelocityPlugin.class));
  }
}
