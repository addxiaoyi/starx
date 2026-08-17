package io.github.addxiaoyi.starx.velocity.http.admin;

import io.github.addxiaoyi.starx.api.dto.UserDto;
import io.github.addxiaoyi.starx.common.auth.BindingVerificationService;
import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

final class BindingCodeResolver {

  record Identity(UUID playerUuid, String username) {
  }

  private final BindingVerificationService codes;
  private final Function<UUID, Optional<UserDto>> userLookup;

  BindingCodeResolver(BindingVerificationService codes, JdbcUserRepository users) {
    this(codes, exactLookup(users));
  }

  BindingCodeResolver(
      BindingVerificationService codes,
      JdbcUserRepository users,
      Function<UUID, Optional<UserDto>> identityAwareLookup) {
    this(codes, composeLookup(users, identityAwareLookup));
  }

  private BindingCodeResolver(
      BindingVerificationService codes,
      Function<UUID, Optional<UserDto>> userLookup) {
    this.codes = Objects.requireNonNull(codes, "codes");
    this.userLookup = Objects.requireNonNull(userLookup, "userLookup");
  }

  Optional<Identity> resolve(String code) {
    AtomicReference<Identity> resolved = new AtomicReference<>();
    UUID uuid = this.codes.verifyCodeIf(code, candidate -> {
      Optional<UserDto> user = this.userLookup.apply(candidate);
      user.ifPresent(value -> resolved.set(new Identity(candidate, value.username())));
      return user.isPresent();
    });
    if (uuid == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(resolved.get());
  }

  private static Function<UUID, Optional<UserDto>> exactLookup(JdbcUserRepository users) {
    JdbcUserRepository repository = Objects.requireNonNull(users, "users");
    return repository::findByUuid;
  }

  private static Function<UUID, Optional<UserDto>> composeLookup(
      JdbcUserRepository users,
      Function<UUID, Optional<UserDto>> identityAwareLookup) {
    JdbcUserRepository repository = Objects.requireNonNull(users, "users");
    Function<UUID, Optional<UserDto>> fallback = Objects.requireNonNull(
        identityAwareLookup, "identityAwareLookup");
    return candidate -> {
      Optional<UserDto> exact = repository.findByUuid(candidate);
      return exact.isPresent() ? exact : fallback.apply(candidate);
    };
  }
}
