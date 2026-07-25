package io.github.addxiaoyi.starx.common.auth;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public final class EmailAddress {
  private static final int MAX_LENGTH = 254;
  private static final Pattern ADDRESS = Pattern.compile(
      "[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]{1,64}@"
          + "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
          + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+");

  private EmailAddress() {}

  public static String normalize(String value) {
    String email = Objects.requireNonNull(value, "email").trim();
    if (email.length() > MAX_LENGTH || !ADDRESS.matcher(email).matches()) {
      throw new IllegalArgumentException("Invalid email address");
    }
    return email.toLowerCase(Locale.ROOT);
  }
}
