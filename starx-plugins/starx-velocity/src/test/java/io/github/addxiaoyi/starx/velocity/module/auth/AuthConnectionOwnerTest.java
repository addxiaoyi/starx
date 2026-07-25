package io.github.addxiaoyi.starx.velocity.module.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class AuthConnectionOwnerTest {

  @Test
  void duplicateUuidCannotReplaceTheOwner() {
    UUID playerId = UUID.randomUUID();
    Object first = new Object();
    Object duplicate = new Object();
    AuthConnectionOwner<Object> owners = new AuthConnectionOwner<>();

    assertTrue(owners.claim(playerId, first));
    assertFalse(owners.claim(playerId, duplicate));
    assertTrue(owners.isOwner(playerId, first));
    assertFalse(owners.isOwner(playerId, duplicate));
  }

  @Test
  void equalConnectionCannotReleaseTheOwnerWithoutIdentity() {
    UUID playerId = UUID.randomUUID();
    Connection owner = new Connection("same-player");
    Connection equalConnection = new Connection("same-player");
    AuthConnectionOwner<Connection> owners = new AuthConnectionOwner<>();

    assertTrue(owners.claim(playerId, owner));
    assertFalse(owners.release(playerId, equalConnection));
    assertTrue(owners.isOwner(playerId, owner));
  }

  @Test
  void staleReleaseCannotRemoveTheReplacementOwner() {
    UUID playerId = UUID.randomUUID();
    Object first = new Object();
    Object replacement = new Object();
    AuthConnectionOwner<Object> owners = new AuthConnectionOwner<>();

    assertTrue(owners.claim(playerId, first));
    assertTrue(owners.release(playerId, first));
    assertTrue(owners.claim(playerId, replacement));
    assertFalse(owners.release(playerId, first));
    assertTrue(owners.isOwner(playerId, replacement));
  }

  private record Connection(String playerId) {
  }
}
