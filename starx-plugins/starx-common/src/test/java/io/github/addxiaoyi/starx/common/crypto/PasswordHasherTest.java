package io.github.addxiaoyi.starx.common.crypto;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class PasswordHasherTest {
  @Test
  void preservesExistingShortPasswordBehavior() {
    String hash = PasswordHasher.hash("correct horse battery staple");

    assertTrue(hash.startsWith("$2a$12$"));
    assertTrue(PasswordHasher.verify("correct horse battery staple", hash));
    assertFalse(PasswordHasher.verify("wrong password", hash));
  }

  @Test
  void supportsPasswordsAtAndBeyondBcryptByteLimit() {
    String ascii = "a".repeat(72) + "7";
    String unicode = "密碼".repeat(40) + "7";

    String asciiHash = PasswordHasher.hash(ascii);
    String unicodeHash = PasswordHasher.hash(unicode);

    assertTrue(PasswordHasher.verify(ascii, asciiHash));
    assertTrue(PasswordHasher.verify(unicode, unicodeHash));
    assertFalse(PasswordHasher.verify(ascii + "x", asciiHash));
    assertFalse(PasswordHasher.verify(unicode + "x", unicodeHash));
  }

  @Test
  void malformedOrMissingHashesFailClosed() {
    assertFalse(PasswordHasher.verify("password123", null));
    assertFalse(PasswordHasher.verify(null, "not-a-bcrypt-hash"));
    assertFalse(PasswordHasher.verify("password123", "not-a-bcrypt-hash"));
  }
}
