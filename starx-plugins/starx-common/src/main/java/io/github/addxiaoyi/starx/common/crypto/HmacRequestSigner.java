package io.github.addxiaoyi.starx.common.crypto;

import java.util.Locale;

public final class HmacRequestSigner {
  private static final String VERSION = "STARX-HMAC-V2";

  private HmacRequestSigner() {}

  public static String sign(
      String secret, String method, String path, String timestamp, String body) {
    return HmacSigner.sign(secret, canonical(method, path, timestamp, body));
  }

  public static boolean verify(
      String secret,
      String method,
      String path,
      String timestamp,
      String body,
      String signature) {
    try {
      return HmacSigner.verify(secret, canonical(method, path, timestamp, body), signature);
    } catch (IllegalArgumentException error) {
      return false;
    }
  }

  private static String canonical(String method, String path, String timestamp, String body) {
    String normalizedMethod = method == null ? "" : method.toUpperCase(Locale.ROOT);
    if (!normalizedMethod.matches("[A-Z]+")) {
      throw new IllegalArgumentException("Invalid HTTP method for request signature");
    }
    if (path == null || !path.startsWith("/") || containsLineBreak(path)) {
      throw new IllegalArgumentException("Invalid path for request signature");
    }
    if (timestamp == null || !timestamp.matches("[0-9]{1,20}")) {
      throw new IllegalArgumentException("Invalid timestamp for request signature");
    }
    return VERSION + '\n' + normalizedMethod + '\n' + path + '\n' + timestamp + '\n'
        + (body == null ? "" : body);
  }

  private static boolean containsLineBreak(String value) {
    return value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
  }
}
