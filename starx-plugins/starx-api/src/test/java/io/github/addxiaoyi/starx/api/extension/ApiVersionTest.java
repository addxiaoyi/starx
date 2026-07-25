package io.github.addxiaoyi.starx.api.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ApiVersionTest {
  @Test
  void parsesAndComparesSemanticVersions() {
    assertEquals(new ApiVersion(1, 2, 3), ApiVersion.parse("1.2.3"));
    assertTrue(new ApiVersion(1, 2, 0).supports(new ApiVersion(1, 1, 9)));
    assertFalse(new ApiVersion(1, 1, 9).supports(new ApiVersion(1, 2, 0)));
    assertFalse(new ApiVersion(2, 0, 0).supports(new ApiVersion(1, 0, 0)));
    assertThrows(IllegalArgumentException.class, () -> ApiVersion.parse("1.2"));
  }
}
