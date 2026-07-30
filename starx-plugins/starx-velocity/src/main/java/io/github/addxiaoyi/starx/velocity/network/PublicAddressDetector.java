package io.github.addxiaoyi.starx.velocity.network;

import io.github.addxiaoyi.starx.velocity.config.NetworkAutomationConfig;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Fetches public-address observations and delegates trust decisions to consensus logic. */
public final class PublicAddressDetector {
  private static final int MAX_RESPONSE_BYTES = 4096;

  private final NetworkAutomationConfig.PublicAddress config;
  private final Probe probe;

  public PublicAddressDetector(NetworkAutomationConfig.PublicAddress config) {
    this(config, new JkdProbe(config.timeoutMs()));
  }

  PublicAddressDetector(NetworkAutomationConfig.PublicAddress config, Probe probe) {
    this.config = Objects.requireNonNull(config, "config");
    this.probe = Objects.requireNonNull(probe, "probe");
  }

  public PublicAddressConsensus.Result detect() {
    if (!this.config.enabled()) {
      return new PublicAddressConsensus.Result(
        PublicAddressConsensus.Status.NO_VALID_OBSERVATION,
        "",
        0,
        0,
        java.util.Map.of(),
        List.of("disabled"));
    }
    Duration timeout = Duration.ofMillis(this.config.timeoutMs());
    List<PublicAddressConsensus.Observation> observations = new ArrayList<>();
    for (String endpoint : this.config.endpoints()) {
      URI uri;
      try {
        uri = URI.create(endpoint);
      } catch (IllegalArgumentException error) {
        observations.add(new PublicAddressConsensus.Observation(endpoint, 0, ""));
        continue;
      }
      if (!isSafeEndpoint(uri)) {
        observations.add(new PublicAddressConsensus.Observation(endpoint, 0, ""));
        continue;
      }
      try {
        observations.add(this.probe.fetch(uri, timeout));
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        observations.add(new PublicAddressConsensus.Observation(endpoint, 0, ""));
        break;
      } catch (Exception error) {
        observations.add(new PublicAddressConsensus.Observation(endpoint, 0, ""));
      }
    }
    return PublicAddressConsensus.resolve(observations, this.config.minimumAgreement());
  }

  static boolean isSafeEndpoint(URI uri) {
    return uri != null
        && "https".equalsIgnoreCase(uri.getScheme())
        && uri.getHost() != null
        && uri.getUserInfo() == null
        && uri.getFragment() == null;
  }

  @FunctionalInterface
  interface Probe {
    PublicAddressConsensus.Observation fetch(URI uri, Duration timeout)
        throws IOException, InterruptedException;
  }

  private static final class JkdProbe implements Probe {
    private final HttpClient client;

    private JkdProbe(int timeoutMs) {
      this.client = HttpClient.newBuilder()
          .connectTimeout(Duration.ofMillis(timeoutMs))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
    }

    @Override
    public PublicAddressConsensus.Observation fetch(URI uri, Duration timeout)
        throws IOException, InterruptedException {
      HttpRequest request = HttpRequest.newBuilder(uri)
          .timeout(timeout)
          .header("Accept", "application/json, text/plain;q=0.9")
          .header("User-Agent", "StarX-Network-Automation/1")
          .GET()
          .build();
      HttpResponse<InputStream> response = this.client.send(
          request, HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream body = response.body()) {
        byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
        String value = bytes.length > MAX_RESPONSE_BYTES
            ? ""
            : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        return new PublicAddressConsensus.Observation(
            uri.toString(), response.statusCode(), value);
      }
    }
  }
}
