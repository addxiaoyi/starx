package io.github.addxiaoyi.starx.common.auth;

import java.util.Objects;
import java.util.UUID;

public record AuthLease(UUID token) {

  public AuthLease {
    Objects.requireNonNull(token, "token");
  }

  public static AuthLease create() {
    return new AuthLease(UUID.randomUUID());
  }
}
