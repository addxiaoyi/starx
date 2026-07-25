package io.github.addxiaoyi.starx.velocity.http;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class ApiConsoleReport {

  private ApiConsoleReport() {
  }

  static List<String> lines(
      ApiExposureResolver.Exposure exposure,
      Map<String, ? extends Map<String, ?>> routes,
      Set<String> publicEndpoints) {
    Objects.requireNonNull(exposure, "exposure");
    Objects.requireNonNull(routes, "routes");
    Objects.requireNonNull(publicEndpoints, "publicEndpoints");

    List<String> lines = new ArrayList<>();
    lines.add("StarX API exposure source=" + exposure.source()
        + " base=" + exposure.baseUrl()
        + " public=" + exposure.publiclyReachable());
    routes.entrySet().stream()
        .flatMap(route -> route.getValue().keySet().stream()
            .map(method -> new Endpoint(method, route.getKey())))
        .sorted(Comparator.comparing(Endpoint::path).thenComparing(Endpoint::method))
        .map(endpoint -> "StarX API " + endpoint.method() + " "
            + exposure.baseUrl() + endpoint.path()
            + " access=" + (publicEndpoints.contains(endpoint.path())
                ? "public"
                : HttpApiServer.API_KEY_HEADER))
        .forEach(lines::add);
    return List.copyOf(lines);
  }

  private record Endpoint(String method, String path) {
  }
}
