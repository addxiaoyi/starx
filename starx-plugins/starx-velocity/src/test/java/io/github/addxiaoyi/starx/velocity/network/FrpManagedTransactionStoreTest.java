package io.github.addxiaoyi.starx.velocity.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FrpManagedTransactionStoreTest {
  private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");

  @TempDir
  Path temporary;

  @Test
  void persistsBackupAdvancesPhaseAndRestoresExistingConfig() throws Exception {
    Path main = this.temporary.resolve("frpc.toml");
    Path managed = this.temporary.resolve("frp/starx-api.toml");
    Files.createDirectories(managed.getParent());
    Files.writeString(main, "includes = [\"frp/starx-api.toml\"]\n");
    Files.writeString(managed, "# previous\n");
    FrpManagedTransactionStore store =
        FrpManagedTransactionStore.forConfig(this.temporary, main, managed);

    FrpManagedTransactionStore.Snapshot prepared = store.begin(
        "# previous\n", "# desired\n", NOW);
    Files.writeString(managed, "# desired\n");
    FrpManagedTransactionStore.Snapshot reloadRequired = store.updatePhase(
        prepared,
        FrpManagedTransactionStore.Phase.RELOAD_REQUIRED,
        NOW.plusSeconds(1));

    FrpManagedTransactionStore.LoadResult loaded = store.load();
    assertEquals(FrpManagedTransactionStore.LoadStatus.LOADED, loaded.status());
    assertEquals(FrpManagedTransactionStore.Phase.RELOAD_REQUIRED, loaded.snapshot().phase());
    assertEquals(reloadRequired.desiredSha256(), loaded.snapshot().desiredSha256());
    assertTrue(store.currentContentCompatible(loaded.snapshot()));
    assertTrue(Files.isRegularFile(store.stateFile()));
    assertTrue(Files.isRegularFile(store.backupFile()));

    store.restore(loaded.snapshot());
    assertEquals("# previous\n", Files.readString(managed));
    store.clear();
    assertFalse(Files.exists(store.stateFile()));
    assertFalse(Files.exists(store.backupFile()));
  }

  @Test
  void restoresAnOriginallyMissingManagedConfigByDeletingTheCandidate() throws Exception {
    Path main = this.temporary.resolve("frpc.toml");
    Path managed = this.temporary.resolve("frp/starx-api.toml");
    Files.writeString(main, "includes = [\"frp/starx-api.toml\"]\n");
    FrpManagedTransactionStore store =
        FrpManagedTransactionStore.forConfig(this.temporary, main, managed);

    FrpManagedTransactionStore.Snapshot transaction =
        store.begin(null, "# desired\n", NOW);
    Files.createDirectories(managed.getParent());
    Files.writeString(managed, "# desired\n");

    assertTrue(store.currentContentCompatible(transaction));
    store.restore(transaction);
    assertFalse(Files.exists(managed));
    assertFalse(Files.exists(store.backupFile()));
  }

  @Test
  void rejectsATamperedBackupWithoutExposingASnapshot() throws Exception {
    Path main = this.temporary.resolve("frpc.toml");
    Path managed = this.temporary.resolve("frp/starx-api.toml");
    Files.createDirectories(managed.getParent());
    Files.writeString(main, "includes = [\"frp/starx-api.toml\"]\n");
    Files.writeString(managed, "# previous\n");
    FrpManagedTransactionStore store =
        FrpManagedTransactionStore.forConfig(this.temporary, main, managed);
    store.begin("# previous\n", "# desired\n", NOW);

    Files.writeString(store.backupFile(), "# tampered\n");
    FrpManagedTransactionStore.LoadResult loaded = store.load();

    assertEquals(FrpManagedTransactionStore.LoadStatus.INVALID, loaded.status());
    assertEquals(null, loaded.snapshot());
    assertTrue(loaded.diagnostic().contains("checksum mismatch"));
  }

  @Test
  void detectsAnExternalManagedConfigEditAsARecoveryConflict() throws Exception {
    Path main = this.temporary.resolve("frpc.toml");
    Path managed = this.temporary.resolve("frp/starx-api.toml");
    Files.createDirectories(managed.getParent());
    Files.writeString(main, "includes = [\"frp/starx-api.toml\"]\n");
    Files.writeString(managed, "# previous\n");
    FrpManagedTransactionStore store =
        FrpManagedTransactionStore.forConfig(this.temporary, main, managed);
    FrpManagedTransactionStore.Snapshot transaction =
        store.begin("# previous\n", "# desired\n", NOW);

    Files.writeString(managed, "# administrator edit\n");

    assertFalse(store.currentContentCompatible(transaction));
    assertEquals("# administrator edit\n", Files.readString(managed));
  }

  @Test
  void corruptStateIsInvalidAndDifferentConfigsUseDifferentJournals() throws Exception {
    Path main = this.temporary.resolve("frpc.toml");
    Path managed = this.temporary.resolve("frp/starx-api.toml");
    FrpManagedTransactionStore first =
        FrpManagedTransactionStore.forConfig(this.temporary, main, managed);
    FrpManagedTransactionStore second = FrpManagedTransactionStore.forConfig(
        this.temporary,
        this.temporary.resolve("other-frpc.toml"),
        managed);
    Files.createDirectories(first.stateFile().getParent());
    Files.writeString(first.stateFile(), "not-json");

    assertNotEquals(first.stateFile(), second.stateFile());
    assertEquals(FrpManagedTransactionStore.LoadStatus.INVALID, first.load().status());
    assertEquals(FrpManagedTransactionStore.LoadStatus.MISSING, second.load().status());
  }
}
