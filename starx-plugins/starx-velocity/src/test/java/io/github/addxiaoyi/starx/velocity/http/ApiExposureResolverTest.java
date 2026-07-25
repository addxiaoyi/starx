package io.github.addxiaoyi.starx.velocity.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.addxiaoyi.starx.velocity.config.StarxConfig;
import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ApiExposureResolverTest {

  @Test
  void advertisesLocalPublicAddressWhenListeningOnEveryInterface() throws Exception {
    StarxConfig.HttpConfig config = new StarxConfig.HttpConfig("0.0.0.0", 8788, "https://frp.example.com/starx");

    ApiExposureResolver.Exposure exposure = ApiExposureResolver.resolve(
        config,
        List.of(InetAddress.getByName("192.168.1.20"), InetAddress.getByName("8.8.8.8")));

    assertEquals(ApiExposureResolver.Source.LOCAL_PUBLIC, exposure.source());
    assertEquals("http://8.8.8.8:8788", exposure.baseUrl());
  }

  @Test
  void fallsBackToConfiguredFrpUrlWhenNoLocalPublicAddressIsReachable() throws Exception {
    StarxConfig.HttpConfig config = new StarxConfig.HttpConfig(
        "127.0.0.1", 8788, "https://api.star.example.com/starx/");

    ApiExposureResolver.Exposure exposure = ApiExposureResolver.resolve(
        config,
        List.of(InetAddress.getByName("100.64.1.2"), InetAddress.getByName("192.168.1.20")));

    assertEquals(ApiExposureResolver.Source.FRP, exposure.source());
    assertEquals("https://api.star.example.com/starx", exposure.baseUrl());
  }

  @Test
  void reportsLocalOnlyWhenNeitherPublicAddressNorFrpIsAvailable() throws Exception {
    StarxConfig.HttpConfig config = new StarxConfig.HttpConfig("127.0.0.1", 8788, "");

    ApiExposureResolver.Exposure exposure = ApiExposureResolver.resolve(
        config,
        List.of(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("10.0.0.8")));

    assertEquals(ApiExposureResolver.Source.LOCAL_ONLY, exposure.source());
    assertEquals("http://127.0.0.1:8788", exposure.baseUrl());
  }

  @Test
  void doesNotAdvertiseIpv6ThroughAnIpv4OnlyWildcard() throws Exception {
    StarxConfig.HttpConfig config = new StarxConfig.HttpConfig(
        "0.0.0.0", 8788, "https://frp.example.com/starx");

    ApiExposureResolver.Exposure exposure = ApiExposureResolver.resolve(
        config,
        List.of(InetAddress.getByName("2408:893a:1242:a4bc::1")));

    assertEquals(ApiExposureResolver.Source.FRP, exposure.source());
  }

  @Test
  void formatsPublicIpv6WhenListeningOnIpv6Wildcard() throws Exception {
    StarxConfig.HttpConfig config = new StarxConfig.HttpConfig("::", 8788, "");

    ApiExposureResolver.Exposure exposure = ApiExposureResolver.resolve(
        config,
        List.of(InetAddress.getByName("2408:893a:1242:a4bc::1")));

    assertEquals(ApiExposureResolver.Source.LOCAL_PUBLIC, exposure.source());
    assertEquals("http://[2408:893a:1242:a4bc:0:0:0:1]:8788", exposure.baseUrl());
  }
}
