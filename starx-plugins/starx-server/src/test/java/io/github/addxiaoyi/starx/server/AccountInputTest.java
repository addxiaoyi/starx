package io.github.addxiaoyi.starx.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class AccountInputTest {

  @Test
  void normalizesValidEmail() {
    assertEquals("player@example.com", AccountInput.email(" Player@Example.COM "));
  }

  @Test
  void rejectsMalformedEmail() {
    assertThrows(IllegalArgumentException.class, () -> AccountInput.email("player@"));
    assertThrows(IllegalArgumentException.class, () -> AccountInput.email("a b@example.com"));
  }

  @Test
  void acceptsNonBlankTotpPasswordWithoutTrimmingIt() {
    assertEquals(" pass word ", AccountInput.password(" pass word "));
    assertThrows(IllegalArgumentException.class, () -> AccountInput.password("   "));
  }

  @Test
  void acceptsOnlySixDigitTotpCodes() {
    assertEquals("123456", AccountInput.totpCode(" 123456 "));
    assertThrows(IllegalArgumentException.class, () -> AccountInput.totpCode("12345"));
  }
}
