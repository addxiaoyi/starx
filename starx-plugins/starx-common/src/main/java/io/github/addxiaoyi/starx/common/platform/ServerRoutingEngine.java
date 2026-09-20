package io.github.addxiaoyi.starx.common.platform;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ServerRoutingEngine {
  private static final double FRIEND_AFFINITY = 12.0;
  private static final double PREFERENCE_AFFINITY = 20.0;

  public Decision select(Request request, List<Node> nodes) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(nodes, "nodes");
    Map<String, String> rejected = new LinkedHashMap<>();
    Candidate best = null;

    for (Node node : nodes) {
      node.validate();
      String reason = rejectionReason(request, node);
      if (reason != null) {
        rejected.put(node.id(), reason);
        continue;
      }
      Candidate candidate = score(request, node);
      if (best == null || candidate.score() > best.score()
          || (candidate.score() == best.score() && node.id().compareTo(best.node().id()) < 0)) {
        best = candidate;
      }
    }
    if (best == null) throw new IllegalStateException("No healthy compatible server is available");
    int eta = (int) Math.ceil(best.node().queue() * 60.0 / best.node().admissionsPerMinute());
    return new Decision(best.node().id(), best.score(), eta, best.factors(), rejected);
  }

  private static String rejectionReason(Request request, Node node) {
    if (!node.online()) return "offline";
    if (node.maintenance()) return "maintenance";
    if (node.draining()) return "draining";
    if (node.players() >= node.capacity()) return "full";
    if (node.weightPercent() <= 0) return "zero_weight";
    if (request.serverType() != null && !request.serverType().isBlank()
        && !request.serverType().equalsIgnoreCase(node.serverType())) return "incompatible_type";
    return null;
  }

  private static Candidate score(Request request, Node node) {
    double utilization = node.players() / (double) node.capacity();
    Map<String, Double> factors = new LinkedHashMap<>();
    factors.put("capacity", -utilization * 60.0);
    factors.put("mspt", -Math.max(0.0, node.mspt() - 20.0) * 2.0);
    factors.put("latency", -node.latencyMs() / 10.0);
    factors.put("queue", -node.queue() * 1.5);
    factors.put("recoveryWeight", -(100 - node.weightPercent()) * 0.5);
    factors.put("friend", request.friendNodes().contains(node.id()) ? FRIEND_AFFINITY : 0.0);
    factors.put("preference", node.id().equalsIgnoreCase(request.preferredNode())
        ? PREFERENCE_AFFINITY : 0.0);
    double score = 100.0 + factors.values().stream().mapToDouble(Double::doubleValue).sum();
    return new Candidate(node, score, factors);
  }

  public record Request(String serverType, String preferredNode, Set<String> friendNodes) {
    public Request {
      friendNodes = friendNodes == null ? Set.of() : Set.copyOf(friendNodes);
    }
  }

  public record Node(
      String id,
      String serverType,
      boolean online,
      boolean maintenance,
      boolean draining,
      int capacity,
      int players,
      double mspt,
      int latencyMs,
      int queue,
      int admissionsPerMinute,
      int weightPercent
  ) {
    void validate() {
      if (id == null || id.isBlank()) throw new IllegalArgumentException("Node id is required");
      if (serverType == null || serverType.isBlank()) {
        throw new IllegalArgumentException("Server type is required");
      }
      if (capacity <= 0 || players < 0 || mspt < 0 || latencyMs < 0 || queue < 0
          || admissionsPerMinute <= 0 || weightPercent < 0 || weightPercent > 100) {
        throw new IllegalArgumentException("Node metrics are outside their supported range: " + id);
      }
    }
  }

  public record Decision(
      String nodeId,
      double score,
      int etaSeconds,
      Map<String, Double> factors,
      Map<String, String> rejected
  ) {
    public Decision {
      factors = Map.copyOf(factors);
      rejected = Map.copyOf(rejected);
    }
  }

  private record Candidate(Node node, double score, Map<String, Double> factors) { }
}
