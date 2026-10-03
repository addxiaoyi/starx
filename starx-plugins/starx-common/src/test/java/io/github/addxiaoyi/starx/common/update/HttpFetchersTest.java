package io.github.addxiaoyi.starx.common.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.URI;
import org.junit.jupiter.api.Test;

final class HttpFetchersTest {

  @Test
  void rejectsDowngradeRedirects() {
    IOException error = assertThrows(IOException.class, () ->
        HttpFetchers.resolveRedirect(
            URI.create("https://updates.example/releases/latest"),
            "http://updates.example/releases/file.jar"));

    assertEquals("Invalid update redirect", error.getMessage());
  }

  @Test
  void rejectsCredentialedRedirects() {
    IOException error = assertThrows(IOException.class, () ->
        HttpFetchers.resolveRedirect(
            URI.create("https://updates.example/releases/latest"),
            "https://user:secret@updates.example/releases/file.jar"));

    assertEquals("Invalid update redirect", error.getMessage());
  }

  @Test
  void resolvesRelativeHttpsRedirectsWithoutFragment() throws Exception {
    URI resolved = HttpFetchers.resolveRedirect(
        URI.create("https://updates.example/releases/latest"), "../starx.jar");

    assertEquals(URI.create("https://updates.example/starx.jar"), resolved);
  }
}
