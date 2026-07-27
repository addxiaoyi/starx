package io.github.addxiaoyi.starx.website;

import java.io.IOException;

@FunctionalInterface
public interface WebsiteSyncCredentialStore {
  void persistEnrollment(SecretValue nodeToken) throws IOException;
}
