package io.github.addxiaoyi.starx.velocity.network;

import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves a public address only when independent observer hosts agree. */
public final class PublicAddressConsensus {
  private static final Pattern JSON_IP = Pattern.compile(
      "\\\"ip\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"", Pattern.CASE_INSENSITIVE);
  private static final Pattern IP_LIKE = Pattern.compile(
      "(?<![0-9a-fA-F:])([0-9a-fA-F:]*[.:][0-9a-fA-F:.]+)(?![0-9a-fA-F:])");

  private PublicAddressConsensus() {
  }

  public static Result resolve(List<Observation> observations, int minimumAgreement) {
    Objects.requireNonNull(observations, "observations");
    if (minimumAgreement < 2) {
      throw new IllegalArgumentException("minimumAgreement must be at least 2");
    }

    Map<String, String> validBySource = new LinkedHashMap<>();
    List<String> rejected = new ArrayList<>();
    for (Observation observation : observations) {
      if (observation == null) {
        continue;
      }
      String source = sourceHost(observation.source());
      if (source.isBlank() || validBySource.containsKey(source)) {
        continue;
      }
      Optional<String> parsed = observation.statusCode() >= 200
          && observation.statusCode() < 300
          ? extractGlobalAddress(observation.body())
          : Optional.empty();
      if (parsed.isPresent()) {
        validBySource.put(source, parsed.orElseThrow());
      } else {
        rejected.add(source);
      }
    }

    Map<String, Long> counts = new LinkedHashMap<>();
    validBySource.values().forEach(address -> counts.merge(address, 1L, Long::sum));
    Map.Entry<String, Long> winner = counts.entrySet().stream()
        .max(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue)
            .thenComparing(Map.Entry::getKey))
        .orElse(null);
    if (winner == null) {
      return new Result(Status.NO_VALID_OBSERVATION, "", 0, validBySource.size(),
          Map.copyOf(validBySource), List.copyOf(rejected));
    }
    int agreement = winner.getValue().intValue();
    Status status = agreement >= minimumAgreement
        ? Status.CONFIRMED
        : (counts.size() > 1 ? Status.DISAGREEMENT : Status.INSUFFICIENT);
    return new Result(status, winner.getKey(), agreement, validBySource.size(),
        Map.copyOf(validBySource), List.copyOf(rejected));
  }

  static Optional<String> extractGlobalAddress(String body) {
    if (body == null || body.isBlank()) {
      return Optional.empty();
    }
    List<String> candidates = new ArrayList<>();
    Matcher json = JSON_IP.matcher(body);
    if (json.find()) {
      candidates.add(json.group(1));
    }
    String trimmed = body.trim();
    if (trimmed.regionMatches(true, 0, "ip=", 0, 3)) {
      candidates.add(trimmed.substring(3).trim());
    }
    candidates.add(trimmed);
    Matcher matcher = IP_LIKE.matcher(body);
    while (matcher.find()) {
      candidates.add(matcher.group(1));
    }
    for (String candidate : candidates) {
      String normalized = candidate.trim().replaceAll("^[\\\"']+|[\\\"',}]+$", "");
      LocalAddressInfo info = LocalAddressInfo.parse(normalized);
      if (!info.isGloballyRoutable()) {
        continue;
      }
      try {
        return Optional.of(InetAddress.getByName(normalized).getHostAddress());
      } catch (Exception ignored) {
        // Keep looking for another syntactically valid candidate.
      }
    }
    return Optional.empty();
  }

  private static String sourceHost(String source) {
    try {
      String host = URI.create(source).getHost();
      return host == null ? "" : host.toLowerCase(java.util.Locale.ROOT);
    } catch (IllegalArgumentException error) {
      return "";
    }
  }

  public record Observation(String source, int statusCode, String body) {
    public Observation {
      source = Objects.requireNonNull(source, "source");
      body = body == null ? "" : body;
    }
  }

  public record Result(
      Status status,
      String address,
      int agreement,
      int validSources,
      Map<String, String> observations,
      List<String> rejectedSources) {
    public Result {
      status = Objects.requireNonNull(status, "status");
      address = address == null ? "" : address;
      observations = Map.copyOf(observations);
      rejectedSources = List.copyOf(rejectedSources);
    }

    public boolean confirmed() {
      return this.status == Status.CONFIRMED;
    }
  }

  public enum Status {
    CONFIRMED,
    INSUFFICIENT,
    DISAGREEMENT,
    NO_VALID_OBSERVATION
  }
}
