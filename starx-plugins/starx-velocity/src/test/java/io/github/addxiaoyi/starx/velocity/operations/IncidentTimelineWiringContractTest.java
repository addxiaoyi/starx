package io.github.addxiaoyi.starx.velocity.operations;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class IncidentTimelineWiringContractTest {
  @Test
  void runtimeObservesAllEventsAndRegistersProtectedApi() throws IOException {
    String plugin = Files.readString(source("StarxVelocityPlugin.java"));
    String api = Files.readString(source("http/HttpApiServer.java"));

    assertTrue(plugin.contains("eventBus.subscribeAll(incidentTimeline::append)"));
    assertTrue(plugin.contains("incidentTimeline"));
    assertTrue(api.contains("new IncidentTimelineHandler(this.incidentTimeline)"));
    assertTrue(api.contains(".register(this, requireAuth)"));
  }

  private static Path source(String file) {
    Path current = Path.of("").toAbsolutePath();
    for (int i = 0; i < 8 && current != null; i++, current = current.getParent()) {
      Path candidate = current.resolve(
          "starx-plugins/starx-velocity/src/main/java/io/github/addxiaoyi/starx/velocity/" + file);
      if (Files.isRegularFile(candidate)) return candidate;
    }
    throw new IllegalStateException("Velocity source is unavailable: " + file);
  }
}
