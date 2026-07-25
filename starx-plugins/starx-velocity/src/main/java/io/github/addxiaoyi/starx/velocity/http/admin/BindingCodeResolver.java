package io.github.addxiaoyi.starx.velocity.http.admin;

import io.github.addxiaoyi.starx.api.dto.UserDto;
import io.github.addxiaoyi.starx.common.auth.BindingVerificationService;
import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

final class BindingCodeResolver {

  record Identity(UUID playerUuid, String username) {
  }

  private final BindingVerificationService codes;
  private final JdbcUserRepository users;

  BindingCodeResolver(BindingVerificationService codes, JdbcUserRepository users) {
    this.codes = Objects.requireNonNull(codes, "codes");
    this.users = Objects.requireNonNull(users, "users");
  }

  Optional<Identity> resolve(String code) {
    AtomicReference<UserDto> resolved = new AtomicReference<>();
    UUID uuid = this.codes.verifyCodeIf(code, candidate -> {
      Optional<UserDto> user = this.users.findByUuid(candidate);
      user.ifPresent(resolved::set);
      return user.isPresent();
    });
    if (uuid == null) {
      return Optional.empty();
    }
    UserDto user = resolved.get();
    return Optional.of(new Identity(user.uuid(), user.username()));
  }
}
