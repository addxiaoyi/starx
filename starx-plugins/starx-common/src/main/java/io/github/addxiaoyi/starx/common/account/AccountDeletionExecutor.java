package io.github.addxiaoyi.starx.common.account;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class AccountDeletionExecutor {
  private static final int BATCH_SIZE = 50;
  private static final long CLAIM_LEASE_MILLIS = 5 * 60 * 1000L;
  private final JdbcAccountDeletionRepository deletions;
  private final Eraser eraser;

  public AccountDeletionExecutor(JdbcAccountDeletionRepository deletions, Eraser eraser) {
    this.deletions = Objects.requireNonNull(deletions, "deletions");
    this.eraser = Objects.requireNonNull(eraser, "eraser");
  }

  public ExecutionSummary runOnce(long now) {
    deletions.releaseStaleClaims(now, CLAIM_LEASE_MILLIS);
    int claimed = 0;
    int completed = 0;
    List<Failure> failed = new ArrayList<>();
    for (JdbcAccountDeletionRepository.DueRequest request : deletions.findDue(now, BATCH_SIZE)) {
      if (!deletions.claimDue(request.requestId(), now)) continue;
      claimed++;
      try {
        eraser.erase(request.playerUuid(), now);
        if (!deletions.complete(request.requestId(), now)) {
          throw new IllegalStateException("Deletion request changed before completion: " + request.requestId());
        }
        completed++;
      } catch (RuntimeException error) {
        if (!deletions.releaseClaim(request.requestId())) {
          throw new IllegalStateException("Deletion request could not be released: " + request.requestId(), error);
        }
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        failed.add(new Failure(request.requestId(), message));
      }
    }
    return new ExecutionSummary(claimed, completed, List.copyOf(failed));
  }

  @FunctionalInterface
  public interface Eraser {
    void erase(UUID playerUuid, long erasedAt);
  }

  public record ExecutionSummary(int claimed, int completed, List<Failure> failures) {
    public ExecutionSummary {
      failures = List.copyOf(failures);
    }

    public List<String> failedRequestIds() {
      return failures.stream().map(Failure::requestId).toList();
    }
  }

  public record Failure(String requestId, String message) {}
}
