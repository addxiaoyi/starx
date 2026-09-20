package io.github.addxiaoyi.starx.velocity.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ApiConsoleReportTest {

  @Test
  void printsSortedRoutesWithExposureAndAccessMode() {
    ApiExposureResolver.Exposure exposure = new ApiExposureResolver.Exposure(
        ApiExposureResolver.Source.FRP,
        "https://api.star.example.com/starx",
        true);

    List<String> lines = ApiConsoleReport.lines(
        exposure,
        Map.of(
            "/v1/network/status", Map.of("GET", "handler"),
            "/v1/health", Map.of("GET", "handler")),
        Set.of("/v1/health"));

    assertEquals(List.of(
        "StarX API exposure source=FRP base=https://api.star.example.com/starx public=true",
        "StarX API GET https://api.star.example.com/starx/v1/health access=public",
        "StarX API GET https://api.star.example.com/starx/v1/network/status access=X-API-Key"), lines);
  }
}
