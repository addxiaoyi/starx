package io.github.addxiaoyi.starx.website;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface WebsiteSyncClient {
  Enrollment enroll(
      SecretValue bootstrapToken,
      String nodeId,
      WebsitePlatform platform,
      List<String> capabilities
  ) throws WebsiteSyncApiException;

  HeartbeatAck heartbeat(
      SecretValue nodeToken,
      String nodeId,
      List<String> capabilities,
      NodeSnapshot snapshot
  ) throws WebsiteSyncApiException;

  ManifestAck submitManifest(
      SecretValue nodeToken,
      Collection<PlayerTexture> entries
  ) throws WebsiteSyncApiException;

  default ManifestAck submitManifestPage(
      SecretValue nodeToken,
      String syncId,
      int page,
      int pages,
      Collection<PlayerTexture> entries
  ) throws WebsiteSyncApiException {
    return submitManifest(nodeToken, entries);
  }

  default void applyCatalogSkin(
      SecretValue nodeToken,
      String catalogId,
      UUID playerUuid,
      String username
  ) throws WebsiteSyncApiException {
    throw new UnsupportedOperationException("Catalog skin application is not supported by this client");
  }

  void uploadTexture(
      SecretValue nodeToken,
      TextureBlob texture
  ) throws WebsiteSyncApiException;
}
