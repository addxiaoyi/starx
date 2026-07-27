package io.github.addxiaoyi.starx.website;

import java.util.Collection;
import java.util.List;

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

  void uploadTexture(
      SecretValue nodeToken,
      TextureBlob texture
  ) throws WebsiteSyncApiException;
}
