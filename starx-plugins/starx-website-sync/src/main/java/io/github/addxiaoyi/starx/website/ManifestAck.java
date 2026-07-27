package io.github.addxiaoyi.starx.website;

import java.util.Comparator;
import java.util.List;

public record ManifestAck(int accepted, List<MissingTexture> missingHashes) {
  public ManifestAck {
    if (accepted < 0 || accepted > 1_000) {
      throw new IllegalArgumentException("Manifest accepted count is outside protocol bounds");
    }
    missingHashes = (missingHashes == null ? List.<MissingTexture>of() : missingHashes)
        .stream()
        .sorted(Comparator.comparing(MissingTexture::hash)
            .thenComparing(entry -> entry.kind().wireName()))
        .toList();
  }
}
