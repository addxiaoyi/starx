package io.github.addxiaoyi.starx.velocity.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class NetworkEnvironmentAssessmentTest {

  @Test
  void confirmsDirectPublicOnlyWhenObserversMatchLocalInterface() {
    NetworkEnvironmentAssessment.Result result = NetworkEnvironmentAssessment.assess(
        List.of(LocalAddressInfo.parse("8.8.8.8"), LocalAddressInfo.parse("10.0.0.2")),
        confirmed("8.8.8.8"));

    assertEquals(
        NetworkEnvironmentAssessment.Topology.DIRECT_PUBLIC_ADDRESS,
        result.topology());
    assertTrue(result.directAddressConfirmed());
    assertFalse(result.requiresTunnel());
  }

  @Test
  void detectsPublicLookingAddressThatDoesNotMatchExternalObservation() {
    NetworkEnvironmentAssessment.Result result = NetworkEnvironmentAssessment.assess(
        List.of(LocalAddressInfo.parse("1.1.1.1")),
        confirmed("8.8.8.8"));

    assertEquals(
        NetworkEnvironmentAssessment.Topology.TRANSLATED_OR_FAKE_PUBLIC,
        result.topology());
    assertTrue(result.requiresTunnel());
  }

  @Test
  void identifiesCarrierGradeNatBeforeGenericPrivateNat() {
    NetworkEnvironmentAssessment.Result result = NetworkEnvironmentAssessment.assess(
        List.of(LocalAddressInfo.parse("100.64.10.20"), LocalAddressInfo.parse("192.168.1.2")),
        confirmed("8.8.8.8"));

    assertEquals(NetworkEnvironmentAssessment.Topology.CGNAT, result.topology());
    assertEquals("8.8.8.8", result.externallyObservedAddress());
  }

  @Test
  void doesNotTrustAnUnconfirmedLocalPublicAddress() {
    PublicAddressConsensus.Result unavailable = new PublicAddressConsensus.Result(
        PublicAddressConsensus.Status.INSUFFICIENT,
        "8.8.8.8",
        1,
        1,
        Map.of("one.example", "8.8.8.8"),
        List.of());

    NetworkEnvironmentAssessment.Result result = NetworkEnvironmentAssessment.assess(
        List.of(LocalAddressInfo.parse("8.8.8.8")),
        unavailable);

    assertEquals(
        NetworkEnvironmentAssessment.Topology.UNVERIFIED_PUBLIC,
        result.topology());
    assertTrue(result.requiresTunnel());
  }

  private static PublicAddressConsensus.Result confirmed(String address) {
    return new PublicAddressConsensus.Result(
        PublicAddressConsensus.Status.CONFIRMED,
        address,
        2,
        2,
        Map.of("one.example", address, "two.example", address),
        List.of());
  }
}
