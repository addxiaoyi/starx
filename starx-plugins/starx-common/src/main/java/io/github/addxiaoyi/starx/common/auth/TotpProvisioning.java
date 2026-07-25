package io.github.addxiaoyi.starx.common.auth;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class TotpProvisioning {
  private TotpProvisioning() {
  }

  public static URI uri(String issuer, String account, String secret) {
    String provider = requireText(issuer, "issuer");
    String label = requireText(account, "account");
    String key = requireText(secret, "secret");
    return URI.create("otpauth://totp/" + encode(provider + ":" + label)
        + "?secret=" + encode(key)
        + "&issuer=" + encode(provider)
        + "&algorithm=SHA1&digits=6&period=30");
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static String requireText(String value, String label) {
    String text = Objects.requireNonNullElse(value, "").trim();
    if (text.isEmpty()) {
      throw new IllegalArgumentException("TOTP " + label + " must not be blank");
    }
    return text;
  }
}
