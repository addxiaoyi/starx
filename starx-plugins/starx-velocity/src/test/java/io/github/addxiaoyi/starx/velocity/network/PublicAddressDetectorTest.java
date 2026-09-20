package io.github.addxiaoyi.starx.velocity.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.velocity.config.NetworkAutomationConfig;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

final class PublicAddressDetectorTest {

  @Test
  void confirmsIndependentHttpsObserversAndIgnoresUnsafeEndpoints() {
    NetworkAutomationConfig.PublicAddress config =
        new NetworkAutomationConfig.PublicAddress(true, 2, 1000, List.of(
            "https://one.example/ip",
            "http://unsafe.example/ip",
            "https://two.example/ip"));
    PublicAddressDetector detector = new PublicAddressDetector(config, (uri, timeout) ->
        new PublicAddressConsensus.Observation(uri.toString(), 200, "8.8.8.8"));

    PublicAddressConsensus.Result result = detector.detect();

    assertTrue(result.confirmed());
    assertEquals("8.8.8.8", result.address());
    assertEquals(2, result.agreement());
    assertEquals(2, result.validSources());
    assertTrue(result.rejectedSources().contains("unsafe.example"));
  }

  @Test
  void doesNotTreatFailuresOrPrivateResponsesAsPublicEvidence() {
    NetworkAutomationConfig.PublicAddress config =
        new NetworkAutomationConfig.PublicAddress(true, 2, 1000, List.of(
            "https://one.example/ip",
            "https://two.example/ip"));
    PublicAddressDetector detector = new PublicAddressDetector(config, (uri, timeout) ->
        uri.getHost().startsWith("one")
            ? new PublicAddressConsensus.Observation(uri.toString(), 500, "8.8.8.8")
            : new PublicAddressConsensus.Observation(uri.toString(), 200, "100.64.1.2"));

    PublicAddressConsensus.Result result = detector.detect();

    assertFalse(result.confirmed());
    assertEquals(PublicAddressConsensus.Status.NO_VALID_OBSERVATION, result.status());
  }

  @Test
  void acceptsOnlyCredentialFreeHttpsObserverUris() {
    assertTrue(PublicAddressDetector.isSafeEndpoint(URI.create("https://one.example/ip?q=1")));
    assertFalse(PublicAddressDetector.isSafeEndpoint(URI.create("http://one.example/ip")));
    assertFalse(PublicAddressDetector.isSafeEndpoint(URI.create("https://u:p@one.example/ip")));
    assertFalse(PublicAddressDetector.isSafeEndpoint(URI.create("https://one.example/ip#fragment")));
  }
}
