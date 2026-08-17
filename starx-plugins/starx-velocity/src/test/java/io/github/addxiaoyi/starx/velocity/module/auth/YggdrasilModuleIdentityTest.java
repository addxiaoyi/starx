package io.github.addxiaoyi.starx.velocity.module.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class YggdrasilModuleIdentityTest {
  private static final UUID EXPECTED_UUID = UUID.fromString("12345678-1234-1234-1234-123456789012");

  @Test
  void acceptsProfileWhenUuidMatchesEvenIfNameDiffers() {
    String body = "{\"id\":\"12345678123412341234123456789012\",\"name\":\"OtherName\"}";

    assertTrue(matchesProfileUuid(body, EXPECTED_UUID));
  }

  @Test
  void rejectsProfileWhenNameMatchesButUuidDiffers() {
    String body = "{\"id\":\"87654321876543218765876543218765\",\"name\":\"ExpectedName\"}";

    assertFalse(matchesProfileUuid(body, EXPECTED_UUID));
  }

  private static boolean matchesProfileUuid(String body, UUID expectedUuid) {
    try {
      Method method = YggdrasilModule.class.getDeclaredMethod(
          "matchesProfileUuid", String.class, UUID.class);
      method.setAccessible(true);
      return (boolean) method.invoke(null, body, expectedUuid);
    } catch (ReflectiveOperationException ignored) {
      return false;
    }
  }
}
