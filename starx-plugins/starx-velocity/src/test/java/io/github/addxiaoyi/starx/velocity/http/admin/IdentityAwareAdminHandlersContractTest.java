package io.github.addxiaoyi.starx.velocity.http.admin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class IdentityAwareAdminHandlersContractTest {
  @Test
  void accountDeletionAcceptsAPlayerResolvedThroughTheIdentityTable() throws Exception {
    String source = Files.readString(source("AccountDeletionHandler.java"), StandardCharsets.UTF_8);

    assertTrue(source.contains("identityAwareUserLookup"));
    assertTrue(source.contains("identityAwareUserLookup.apply(playerUuid)"));
  }

  @Test
  void accountDeletionUsesAllKnownMinecraftUuidsForStateAndCancellation() throws Exception {
    String source = Files.readString(source("AccountDeletionHandler.java"), StandardCharsets.UTF_8);

    assertTrue(source.contains("knownMinecraftUuidsResolver"));
    assertTrue(source.contains("deletions.latest(knownUuids)"));
    assertTrue(source.contains("deletions.request(playerUuid, knownUuids"));
    assertTrue(source.contains("deletions.cancel(request.requestId.trim(), knownUuids"));
  }

  @Test
  void playerBansUseTheIdentityAwareUserLookup() throws Exception {
    String source = Files.readString(source("BanHandler.java"), StandardCharsets.UTF_8);

    assertTrue(source.contains("identityAwareUserLookup"));
    assertTrue(source.contains("identityAwareUserLookup.apply(req.playerUuid)"));
  }

  @Test
  void bindingCodeGenerationUsesTheIdentityAwareUserLookup() throws Exception {
    String source = Files.readString(source("BindingHandler.java"), StandardCharsets.UTF_8);

    assertTrue(source.contains("identityAwareUserLookup.apply(req.playerUuid)"));
  }

  @Test
  void externalLinkVerificationUsesTheCanonicalUuid() throws Exception {
    String source = Files.readString(source("LinkExternalUserHandler.java"), StandardCharsets.UTF_8);

    assertTrue(source.contains("canonicalUuidResolver"));
    assertTrue(source.contains("canonicalUuidResolver.apply(existing.uuid())"));
  }

  @Test
  void bindingUnlinkUsesTheCanonicalUuid() throws Exception {
    String source = Files.readString(source("BindingUnlinkHandler.java"), StandardCharsets.UTF_8);

    assertTrue(source.contains("canonicalUuidResolver"));
    assertTrue(source.contains("canonicalUuidResolver.apply(request.playerUuid)"));
  }

  @Test
  void bindingUnlinkChecksEveryKnownMinecraftUuid() throws Exception {
    String source = Files.readString(source("BindingUnlinkHandler.java"), StandardCharsets.UTF_8);

    assertTrue(source.contains("knownMinecraftUuidsResolver"));
    assertTrue(source.contains("knownMinecraftUuidsResolver.apply(request.playerUuid)"));
  }

  @Test
  void bindingIntegrationsUseTheCompleteIdentityAliasSet() throws Exception {
    assertKnownUuidResolver("integration/LuckPermsContextModule.java");
    assertKnownUuidResolver("module/admin/AdminCommandsModule.java");
    assertKnownUuidResolver("module/playerlist/PlayerListModule.java");
  }

  @Test
  void userOverviewUsesTheCanonicalUuidForPlayerOwnedRecords() throws Exception {
    String source = Files.readString(source("UserOverviewHandler.java"), StandardCharsets.UTF_8);

    assertTrue(source.contains("canonicalUuidResolver"));
    assertTrue(source.contains("canonicalUuidResolver.apply(user.uuid())"));
    assertTrue(source.contains("knownMinecraftUuidsResolver"));
    assertTrue(source.contains("sessions.summary(knownUuids)"));
    assertTrue(source.contains("sessions.playtimeByServer(knownUuids)"));
  }

  @Test
  void bindingConfirmationMigratesAndSavesAsOneAtomicOperation() throws Exception {
    String source = Files.readString(source("BindingHandler.java"), StandardCharsets.UTF_8);

    assertTrue(source.contains("migrateAndSave"));
    assertFalse(source.contains("migratePlayer"));
  }

  @Test
  void bindingQueryConvertsMalformedPlayerUuidIntoAClientError() throws Exception {
    String source = Files.readString(source("BindingHandler.java"), StandardCharsets.UTF_8);

    assertTrue(source.contains("parsePlayerUuid"));
    assertTrue(source.contains("Invalid UUID format"));
  }

  @Test
  void crossDeviceApprovalConfirmationValidatesPlayerUuidBeforeExecution() throws Exception {
    String source = Files.readString(source("CrossDeviceApprovalHandler.java"), StandardCharsets.UTF_8);

    assertTrue(source.contains("parsePlayerUuid"));
    assertTrue(source.contains("invalid_request"));
  }

  @Test
  void totpEndpointsConvertMalformedUuidIntoAClientError() throws Exception {
    String source = Files.readString(source("TotpEnableHandler.java"), StandardCharsets.UTF_8);

    assertTrue(source.contains("parseUuid"));
    assertTrue(source.contains("catch (IllegalArgumentException error)"));
  }

  private static Path source(String fileName) {
    Path current = Path.of("").toAbsolutePath();
    for (int depth = 0; depth < 8 && current != null; depth++) {
      Path candidate = current.resolve(
          "src/main/java/io/github/addxiaoyi/starx/velocity/http/admin/" + fileName);
      if (Files.isRegularFile(candidate)) return candidate;
      current = current.getParent();
    }
    throw new IllegalStateException(fileName + " source is unavailable");
  }

  private static void assertKnownUuidResolver(String relativePath) throws Exception {
    Path current = Path.of("").toAbsolutePath();
    for (int depth = 0; depth < 8 && current != null; depth++) {
      Path candidate = current.resolve(
          "src/main/java/io/github/addxiaoyi/starx/velocity/" + relativePath)
          .normalize();
      if (Files.isRegularFile(candidate)) {
        String source = Files.readString(candidate, StandardCharsets.UTF_8);
        assertTrue(source.contains("knownMinecraftUuidsResolver"), relativePath);
        assertTrue(source.contains("knownMinecraftUuidsResolver.apply"), relativePath);
        return;
      }
      current = current.getParent();
    }
    throw new IllegalStateException(relativePath + " source is unavailable");
  }
}
