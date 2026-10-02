package io.github.addxiaoyi.starx.common.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class MavenCentralClientTest {

  @Test
  void parsesTheNewestReleaseDocument() {
    String json = """
        {"response":{"docs":[
          {"g":"io.github.addxiaoyi.starx","a":"starx-universal","v":"1.0.28"},
          {"g":"io.github.addxiaoyi.starx","a":"starx-universal","v":"1.0.27-SNAPSHOT"}
        ]}}
        """;

    RepositoryClient.VersionInfo info = MavenCentralClient.parseResponse(json).orElseThrow();

    assertEquals("1.0.28", info.version().raw());
    assertTrue(info.downloadUrl().toString().contains("starx-universal-1.0.28.jar"));
  }

  @Test
  void rejectsMalformedSolrPayloads() {
    assertTrue(MavenCentralClient.parseResponse("{\"response\":{\"docs\":[]}}").isEmpty());
  }
}