package io.github.addxiaoyi.starx.common.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import org.junit.jupiter.api.Test;

final class GitHubReleasesClientTest {

  @Test
  void selectsTheUniversalJarInsteadOfTheFirstJarAsset() {
    String json = """
        {"tag_name":"v1.0.28","name":"StarX 1.0.28","assets":[
          {"name":"starx-server-1.0.28.jar","browser_download_url":"https://example.invalid/server.jar"},
          {"name":"starx-universal-1.0.28.jar","browser_download_url":"https://example.invalid/universal.jar","digest":"sha256:abc123"}
        ]}
        """;

    RepositoryClient.VersionInfo info = GitHubReleasesClient.parseResponse(json).orElseThrow();

    assertEquals(URI.create("https://example.invalid/universal.jar"), info.downloadUrl());
    assertEquals("abc123", info.sha256());
  }

  @Test
  void rejectsMalformedReleaseVersions() {
    assertTrue(GitHubReleasesClient.parseResponse("{\"tag_name\":\"nightly\",\"assets\":[]}").isEmpty());
  }
}