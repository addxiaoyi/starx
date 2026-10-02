package io.github.addxiaoyi.starx.common.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class VersionTest {

  @Test
  void comparesNumericPrereleaseIdentifiersNumerically() {
    assertTrue(Version.parse("1.0.0-rc.10").compareTo(Version.parse("1.0.0-rc.2")) > 0);
    assertTrue(Version.parse("1.0.0-rc.2").compareTo(Version.parse("1.0.0-rc.10")) < 0);
  }

  @Test
  void numericPrereleaseIdentifiersSortBeforeTextIdentifiers() {
    assertTrue(Version.parse("1.0.0-1").compareTo(Version.parse("1.0.0-alpha")) < 0);
  }

  @Test
  void rejectsMalformedPrereleaseAndBuildMetadata() {
    assertEquals(null, Version.parse("1.0.0-"));
    assertEquals(null, Version.parse("1.0.0-alpha..1"));
    assertEquals(null, Version.parse("1.0.0-alpha.01"));
    assertEquals(null, Version.parse("1.0.0+"));
  }

  @Test
  void stableReleaseSortsAfterAnyPrerelease() {
    assertTrue(Version.parse("1.0.0").compareTo(Version.parse("1.0.0-rc.99")) > 0);
    assertEquals(0, Version.parse("v1.0.0+build.1").compareTo(Version.parse("1.0.0+build.2")));
  }
}
