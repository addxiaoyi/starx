package io.github.addxiaoyi.starx.velocity.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class PublicAddressConsensusTest {

  @Test
  void confirmsOnlyWhenIndependentHostsAgree() {
    PublicAddressConsensus.Result result = PublicAddressConsensus.resolve(List.of(
        new PublicAddressConsensus.Observation("https://one.example/ip", 200, "8.8.8.8\n"),
        new PublicAddressConsensus.Observation("https://two.example/json", 200, "{\"ip\":\"8.8.8.8\"}"),
        new PublicAddressConsensus.Observation("https://three.example/ip", 503, "8.8.8.8")), 2);

    assertTrue(result.confirmed());
    assertEquals("8.8.8.8", result.address());
    assertEquals(2, result.agreement());
    assertEquals(2, result.validSources());
  }

  @Test
  void sameHostCannotVoteTwice() {
    PublicAddressConsensus.Result result = PublicAddressConsensus.resolve(List.of(
        new PublicAddressConsensus.Observation("https://same.example/a", 200, "1.1.1.1"),
        new PublicAddressConsensus.Observation("https://same.example/b", 200, "1.1.1.1"),
        new PublicAddressConsensus.Observation("https://other.example/a", 200, "8.8.8.8")), 2);

    assertFalse(result.confirmed());
    assertEquals(PublicAddressConsensus.Status.DISAGREEMENT, result.status());
    assertEquals(2, result.validSources());
  }

  @Test
  void rejectsPrivateCgnatAndDocumentationResponses() {
    PublicAddressConsensus.Result result = PublicAddressConsensus.resolve(List.of(
        new PublicAddressConsensus.Observation("https://one.example", 200, "192.168.1.2"),
        new PublicAddressConsensus.Observation("https://two.example", 200, "ip=100.64.2.3"),
        new PublicAddressConsensus.Observation("https://three.example", 200, "{\"ip\":\"203.0.113.4\"}")), 2);

    assertFalse(result.confirmed());
    assertEquals(PublicAddressConsensus.Status.NO_VALID_OBSERVATION, result.status());
    assertEquals(0, result.validSources());
  }

  @Test
  void parsesIpv6FromJsonAndPlainResponses() {
    PublicAddressConsensus.Result result = PublicAddressConsensus.resolve(List.of(
        new PublicAddressConsensus.Observation("https://one.example", 200, "{\"ip\":\"2606:4700:4700::1111\"}"),
        new PublicAddressConsensus.Observation("https://two.example", 200, "2606:4700:4700::1111\n")), 2);

    assertTrue(result.confirmed());
    assertEquals("2606:4700:4700:0:0:0:0:1111", result.address());
  }
}
