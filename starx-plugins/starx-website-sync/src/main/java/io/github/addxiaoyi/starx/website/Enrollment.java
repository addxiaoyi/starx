package io.github.addxiaoyi.starx.website;

import java.util.List;
import java.util.Objects;

public record Enrollment(SecretValue nodeToken, String nodeId, List<String> scopes) {
  public Enrollment {
    nodeToken = Objects.requireNonNull(nodeToken, "nodeToken");
    if (!nodeToken.isPresent()) {
      throw new IllegalArgumentException("Enrollment response did not include a node token");
    }
    nodeId = Objects.requireNonNullElse(nodeId, "").trim();
    if (nodeId.isEmpty()) {
      throw new IllegalArgumentException("Enrollment response did not include a node id");
    }
    scopes = scopes == null ? List.of() : List.copyOf(scopes);
  }
}
