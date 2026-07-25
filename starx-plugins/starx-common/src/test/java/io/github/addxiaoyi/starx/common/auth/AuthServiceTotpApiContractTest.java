package io.github.addxiaoyi.starx.common.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class AuthServiceTotpApiContractTest {
  @Test
  void directTotpPersistenceIsNotPublicApi() {
    boolean exposesDirectEnable = Arrays.stream(AuthService.class.getMethods())
        .anyMatch(method -> method.getName().equals("enableTotp"));

    assertFalse(exposesDirectEnable);
    assertFalse(Arrays.stream(AuthService.class.getMethods())
        .anyMatch(method -> method.getName().equals("bindTotp")));
  }
}
