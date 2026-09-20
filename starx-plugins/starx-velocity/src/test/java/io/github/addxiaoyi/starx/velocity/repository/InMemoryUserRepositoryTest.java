package io.github.addxiaoyi.starx.velocity.repository;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.api.dto.UserDto;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class InMemoryUserRepositoryTest {
  @Test
  void usernameLookupIsCaseInsensitive() {
    InMemoryUserRepository users = new InMemoryUserRepository();
    users.save(UserDto.builder().uuid(UUID.randomUUID()).username("Alex").build());

    assertTrue(users.existsByUsername("alex"));
    assertTrue(users.findByUsername("ALEX").isPresent());
  }
}
