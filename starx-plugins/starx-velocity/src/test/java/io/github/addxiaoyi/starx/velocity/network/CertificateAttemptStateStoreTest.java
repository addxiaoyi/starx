package io.github.addxiaoyi.starx.velocity.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CertificateAttemptStateStoreTest {
  @TempDir
  Path temporary;

  @Test
  void missingStateLoadsAnEmptySnapshot() {
    CertificateAttemptStateStore store =
        CertificateAttemptStateStore.forLineage(this.temporary, "Panel.Example.com");

    CertificateAttemptStateStore.LoadResult loaded = store.load();

    assertEquals(CertificateAttemptStateStore.LoadStatus.MISSING, loaded.status());
    assertEquals("panel.example.com", loaded.snapshot().lineage());
    assertEquals(CertificateAttemptStateStore.Outcome.NEVER, loaded.snapshot().outcome());
    assertFalse(loaded.snapshot().backoffActive(Instant.EPOCH));
  }

  @Test
  void differentLineagesUseDifferentLockAndStateFiles() {
    CertificateAttemptStateStore first =
        CertificateAttemptStateStore.forLineage(this.temporary, "one.example.com");
    CertificateAttemptStateStore second =
        CertificateAttemptStateStore.forLineage(this.temporary, "two.example.com");

    assertNotEquals(first.lockFile(), second.lockFile());
    assertNotEquals(first.stateFile(), second.stateFile());
    assertEquals(first.lockFile().getParent(), first.stateFile().getParent());
  }

  @Test
  void failuresPersistAndBackoffDoubles() throws Exception {
    CertificateAttemptStateStore store =
        CertificateAttemptStateStore.forLineage(this.temporary, "panel.example.com");
    Instant firstAttempt = Instant.parse("2026-07-29T00:00:00Z");

    CertificateAttemptStateStore.Snapshot firstFailure = store.recordFailure(
        CertificateAttemptStateStore.Snapshot.empty(store.lineage()),
        firstAttempt,
        CertificateAttemptStateStore.Phase.PRODUCTION,
        CertificateAttemptStateStore.FailureClass.COMMAND_FAILED);

    assertEquals(firstAttempt.plus(Duration.ofMinutes(30)), firstFailure.nextAllowedAt());
    CertificateAttemptStateStore.LoadResult loaded = store.load();
    assertEquals(CertificateAttemptStateStore.LoadStatus.LOADED, loaded.status());
    assertEquals(1, loaded.snapshot().consecutiveFailures());

    Instant secondAttempt = firstFailure.nextAllowedAt();
    CertificateAttemptStateStore.Snapshot secondFailure = store.recordFailure(
        loaded.snapshot(),
        secondAttempt,
        CertificateAttemptStateStore.Phase.PRODUCTION,
        CertificateAttemptStateStore.FailureClass.COMMAND_FAILED);

    assertEquals(2, secondFailure.consecutiveFailures());
    assertEquals(secondAttempt, secondFailure.lastAttemptAt());
    assertEquals(secondAttempt.plus(Duration.ofHours(1)), secondFailure.nextAllowedAt());
  }

  @Test
  void recordStartCreatesCrashGuard() throws Exception {
    CertificateAttemptStateStore store =
        CertificateAttemptStateStore.forLineage(this.temporary, "panel.example.com");
    Instant now = Instant.parse("2026-07-29T00:00:00Z");

    CertificateAttemptStateStore.Snapshot started = store.recordStart(
        CertificateAttemptStateStore.Snapshot.empty(store.lineage()),
        now,
        CertificateAttemptStateStore.Phase.STAGING);

    assertEquals(CertificateAttemptStateStore.Outcome.IN_PROGRESS, started.outcome());
    assertEquals(now.plus(Duration.ofMinutes(15)), started.nextAllowedAt());
    assertTrue(started.backoffActive(now.plus(Duration.ofMinutes(14))));
    assertFalse(started.backoffActive(now.plus(Duration.ofMinutes(15))));
  }

  @Test
  void successClearsFailureCounterAndBackoff() throws Exception {
    CertificateAttemptStateStore store =
        CertificateAttemptStateStore.forLineage(this.temporary, "panel.example.com");
    Instant now = Instant.parse("2026-07-29T00:00:00Z");
    CertificateAttemptStateStore.Snapshot failed = store.recordFailure(
        CertificateAttemptStateStore.Snapshot.empty(store.lineage()),
        now,
        CertificateAttemptStateStore.Phase.PRODUCTION,
        CertificateAttemptStateStore.FailureClass.TIMEOUT);

    CertificateAttemptStateStore.Snapshot succeeded = store.recordSuccess(
        failed,
        now.plus(Duration.ofHours(1)),
        CertificateAttemptStateStore.Phase.PRODUCTION);

    assertEquals(CertificateAttemptStateStore.Outcome.SUCCEEDED, succeeded.outcome());
    assertEquals(CertificateAttemptStateStore.FailureClass.NONE, succeeded.failureClass());
    assertEquals(0, succeeded.consecutiveFailures());
    assertEquals(null, succeeded.nextAllowedAt());
  }

  @Test
  void malformedStateFailsClosed() throws Exception {
    CertificateAttemptStateStore store =
        CertificateAttemptStateStore.forLineage(this.temporary, "panel.example.com");
    Files.createDirectories(store.stateFile().getParent());
    Files.writeString(store.stateFile(), "{");

    CertificateAttemptStateStore.LoadResult loaded = store.load();

    assertEquals(CertificateAttemptStateStore.LoadStatus.INVALID, loaded.status());
    assertFalse(loaded.diagnostic().isBlank());
  }

  @Test
  void commandFailuresAreClassifiedForBackoff() {
    assertEquals(
        CertificateAttemptStateStore.FailureClass.ACME_RATE_LIMIT,
        CertificateAttemptStateStore.classify(
            new NetworkAutomationService.CommandResult(
                1, "urn:ietf:params:acme:error:rateLimited Too many requests", false, "")));
    assertEquals(
        CertificateAttemptStateStore.FailureClass.DNS_FAILURE,
        CertificateAttemptStateStore.classify(
            new NetworkAutomationService.CommandResult(1, "DNS problem: NXDOMAIN", false, "")));
    assertEquals(
        CertificateAttemptStateStore.FailureClass.TIMEOUT,
        CertificateAttemptStateStore.classify(
            new NetworkAutomationService.CommandResult(-1, "", true, "")));
  }

  @Test
  void exponentialBackoffIsCappedAtTwentyFourHours() {
    assertEquals(
        Duration.ofHours(24),
        CertificateAttemptStateStore.backoffDelay(
            CertificateAttemptStateStore.FailureClass.ACME_RATE_LIMIT,
            3));
    assertEquals(
        Duration.ofHours(24),
        CertificateAttemptStateStore.backoffDelay(
            CertificateAttemptStateStore.FailureClass.COMMAND_FAILED,
            30));
  }
}
