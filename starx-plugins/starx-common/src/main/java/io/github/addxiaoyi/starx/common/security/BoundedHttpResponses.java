package io.github.addxiaoyi.starx.common.security;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletionException;

/** Bounded response handlers for small authentication/control-plane responses. */
public final class BoundedHttpResponses {
  private BoundedHttpResponses() {
  }

  public static HttpResponse.BodyHandler<String> utf8(int maxBytes) {
    if (maxBytes < 1) {
      throw new IllegalArgumentException("maxBytes must be positive");
    }
    return ignored -> HttpResponse.BodySubscribers.mapping(
        HttpResponse.BodySubscribers.ofInputStream(),
        input -> decode(input, maxBytes));
  }

  private static String decode(InputStream input, int maxBytes) {
    Objects.requireNonNull(input, "input");
    try (input) {
      byte[] bytes = input.readNBytes(maxBytes + 1);
      if (bytes.length > maxBytes) {
        throw new IllegalStateException("HTTP response exceeds " + maxBytes + " bytes");
      }
      return new String(bytes, StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new CompletionException(error);
    }
  }
}
