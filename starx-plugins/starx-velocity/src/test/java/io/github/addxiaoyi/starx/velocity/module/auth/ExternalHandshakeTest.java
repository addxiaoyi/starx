package io.github.addxiaoyi.starx.velocity.module.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ExternalHandshakeTest {

  @TempDir
  Path tempDir;

  @Test
  void generatedKeyIsPersistedAndOnlyMatchesTheExactHandshakeFields() throws Exception {
    ExternalHandshake first = ExternalHandshake.open(this.tempDir);
    String key = first.key();
    assertEquals(43, key.length());
    assertTrue(key.matches("[A-Za-z0-9_-]{43}"));
    assertEquals(key, Files.readString(this.tempDir.resolve("external-handshake.key")).trim());

    assertTrue(first.matches("mc.example\0starx-handshake\0" + key), key);
    assertFalse(first.matches("mc.example\0starx-handshake\0wrong"));
    assertFalse(first.matches("mc.example\0other\0" + key));
    assertFalse(first.matches("mc.example\0starx-handshake\0" + key + "\0extra"));

    ExternalHandshake second = ExternalHandshake.open(this.tempDir);
    assertEquals(key, second.key());
  }

  @Test
  void disabledHandshakeNeverAcceptsAnEmptyToken() {
    assertFalse(ExternalHandshake.disabled().matches("mc.example\0starx-handshake\0"));
  }

  @Test
  void rejectsMalformedStoredKey() throws Exception {
    Files.writeString(this.tempDir.resolve(ExternalHandshake.KEY_FILE_NAME), "short");

    IOException error = assertThrows(IOException.class, () -> ExternalHandshake.open(this.tempDir));

    assertTrue(error.getMessage().contains("32-byte"));
  }

  @Test
  void rejectsBlankOrNonCanonicalHandshakeFields() throws Exception {
    ExternalHandshake handshake = ExternalHandshake.open(this.tempDir);
    String key = handshake.key();

    assertFalse(handshake.matches(null));
    assertFalse(handshake.matches("\0starx-handshake\0" + key));
    assertFalse(handshake.matches("mc.example\0starx-handshake\0" + key + " "));
  }
}
