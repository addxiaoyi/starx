package io.github.addxiaoyi.starx.velocity.http.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.api.dto.UserDto;
import io.github.addxiaoyi.starx.common.auth.BindingVerificationService;
import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class BindingCodeResolverTest {

  @Test
  void resolvesAPlayerIdentityExactlyOnce() {
    UUID uuid = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");
    UserDto user = UserDto.builder().uuid(uuid).username("Steve").build();
    JdbcUserRepository users = new JdbcUserRepository(null) {
      @Override
      public Optional<UserDto> findByUuid(UUID requested) {
        return requested.equals(uuid) ? Optional.of(user) : Optional.empty();
      }
    };
    BindingVerificationService codes = new BindingVerificationService();
    BindingCodeResolver resolver = new BindingCodeResolver(codes, users);
    String code = codes.generateCode(uuid);

    BindingCodeResolver.Identity identity = resolver.resolve(code).orElseThrow();

    assertEquals(uuid, identity.playerUuid());
    assertEquals("Steve", identity.username());
    assertTrue(resolver.resolve(code).isEmpty());
  }

  @Test
  void keepsTheCodeUntilARegisteringPlayerExists() {
    UUID uuid = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");
    AtomicReference<UserDto> registered = new AtomicReference<>();
    JdbcUserRepository users = new JdbcUserRepository(null) {
      @Override
      public Optional<UserDto> findByUuid(UUID requested) {
        return requested.equals(uuid) ? Optional.ofNullable(registered.get()) : Optional.empty();
      }
    };
    BindingVerificationService codes = new BindingVerificationService();
    BindingCodeResolver resolver = new BindingCodeResolver(codes, users);
    String code = codes.generateCode(uuid);

    assertTrue(resolver.resolve(code).isEmpty());

    registered.set(UserDto.builder().uuid(uuid).username("Steve").build());
    assertEquals("Steve", resolver.resolve(code).orElseThrow().username());
    assertTrue(resolver.resolve(code).isEmpty());
  }
}
