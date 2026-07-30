package io.github.addxiaoyi.starx.velocity.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.velocity.config.NetworkAutomationConfig;
import io.github.addxiaoyi.starx.velocity.config.StarxConfig;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NetworkAutomationServiceTest {
  @TempDir
  Path temporary;

  @Test
  void managedDetectionWritesRemotePortZeroWithoutExecutingAnything() throws Exception {
    List<List<String>> commands = new ArrayList<>();
    NetworkAutomationConfig config = config(
        managedFrp(false, ""),
        NetworkAutomationConfig.Certificate.defaults());

    try (NetworkAutomationService service = service(config, commands, command ->
        new NetworkAutomationService.CommandResult(0, "", false, ""))) {
      Map<String, Object> report = service.refreshNow();

      assertTrue(commands.isEmpty());
      String managed = Files.readString(this.temporary.resolve("frp/starx-api.toml"));
      assertTrue(managed.contains("remotePort = 0"));
      assertEquals("config_written_awaiting_auto_apply",
          nested(report, "frp", "status"));
      assertEquals("LOCAL_ONLY", nested(report, "selectedEndpoint", "source"));
      assertTrue(Files.isRegularFile(this.temporary.resolve("network-automation.json")));
    }
  }

  @Test
  void autoApplyUsesVerifyReloadStatusAndPublishesOnlyStatusAssignedPort() throws Exception {
    Files.writeString(this.temporary.resolve("frpc.toml"),
        "includes = [\"frp/starx-api.toml\"]\n");
    List<List<String>> commands = new ArrayList<>();
    NetworkAutomationConfig config = config(
        managedFrp(true, "frpc.toml"),
        NetworkAutomationConfig.Certificate.defaults());

    try (NetworkAutomationService service = service(config, commands, command -> {
      String action = command.get(1);
      String output = "status".equals(action)
          ? "starx-api tcp running 0.0.0.0:45123\n"
          : "";
      return new NetworkAutomationService.CommandResult(0, output, false, "");
    })) {
      Map<String, Object> report = service.refreshNow();

      assertEquals(List.of("verify", "reload", "status"),
          commands.stream().map(command -> command.get(1)).toList());
      assertEquals("HEALTHY", nested(report, "frp", "localHealth", "status"));
      assertEquals("HEALTHY", nested(report, "frp", "localHealthAfterReload", "status"));
      assertEquals("assigned_port_confirmed", nested(report, "frp", "status"));
      assertEquals(45123, nested(report, "frp", "assignedPort"));
      assertEquals("http://frp.example.com:45123",
          nested(report, "selectedEndpoint", "url"));
      assertEquals(true,
          nested(report, "selectedEndpoint", "frpPortAssignedByServer"));
    }
  }

  @Test
  void recoversReloadRequiredTransactionBeforeStartingANewApply() throws Exception {
    Path main = this.temporary.resolve("frpc.toml");
    Files.writeString(main, "includes = [\"frp/starx-api.toml\"]\n");
    Path managed = this.temporary.resolve("frp/starx-api.toml");
    Files.createDirectories(managed.getParent());
    Files.writeString(managed, "# previous\n");
    FrpManagedTransactionStore store =
        FrpManagedTransactionStore.forConfig(this.temporary, main, managed);
    FrpManagedTransactionStore.Snapshot interrupted = store.begin(
        "# previous\n", "# interrupted candidate\n", Instant.parse("2026-07-28T00:00:00Z"));
    Files.writeString(managed, "# interrupted candidate\n");
    store.updatePhase(
        interrupted,
        FrpManagedTransactionStore.Phase.RELOAD_REQUIRED,
        Instant.parse("2026-07-28T00:00:01Z"));
    List<List<String>> commands = new ArrayList<>();
    List<String> contentsAtCommand = new ArrayList<>();
    NetworkAutomationConfig config = config(
        managedFrp(true, "frpc.toml"),
        NetworkAutomationConfig.Certificate.defaults());

    try (NetworkAutomationService service = service(config, commands, command -> {
      try {
        contentsAtCommand.add(Files.readString(managed));
      } catch (Exception error) {
        throw new IllegalStateException(error);
      }
      String output = "status".equals(command.get(1))
          ? "starx-api tcp running 0.0.0.0:45123\n"
          : "";
      return new NetworkAutomationService.CommandResult(0, output, false, "");
    })) {
      Map<String, Object> report = service.refreshNow();

      assertEquals(List.of("verify", "reload", "verify", "reload", "status"),
          commands.stream().map(command -> command.get(1)).toList());
      assertEquals("# previous\n", contentsAtCommand.get(0));
      assertEquals("# previous\n", contentsAtCommand.get(1));
      assertTrue(contentsAtCommand.get(2).contains("remotePort = 0"));
      assertEquals("restored_and_reloaded",
          nested(report, "frp", "transactionRecovery", "status"));
      assertEquals("committed", nested(report, "frp", "transactionOutcome"));
      assertEquals("assigned_port_confirmed", nested(report, "frp", "status"));
      assertFalse(Files.exists(store.stateFile()));
      assertFalse(Files.exists(store.backupFile()));
    }
  }

  @Test
  void blocksNewApplyAndKeepsJournalWhenCrashRecoveryReloadFails() throws Exception {
    Path main = this.temporary.resolve("frpc.toml");
    Files.writeString(main, "includes = [\"frp/starx-api.toml\"]\n");
    Path managed = this.temporary.resolve("frp/starx-api.toml");
    Files.createDirectories(managed.getParent());
    Files.writeString(managed, "# previous\n");
    FrpManagedTransactionStore store =
        FrpManagedTransactionStore.forConfig(this.temporary, main, managed);
    FrpManagedTransactionStore.Snapshot interrupted = store.begin(
        "# previous\n", "# interrupted candidate\n", Instant.parse("2026-07-28T00:00:00Z"));
    Files.writeString(managed, "# interrupted candidate\n");
    store.updatePhase(
        interrupted,
        FrpManagedTransactionStore.Phase.RELOAD_REQUIRED,
        Instant.parse("2026-07-28T00:00:01Z"));
    List<List<String>> commands = new ArrayList<>();
    NetworkAutomationConfig config = config(
        managedFrp(true, "frpc.toml"),
        NetworkAutomationConfig.Certificate.defaults());

    try (NetworkAutomationService service = service(config, commands, command ->
        "reload".equals(command.get(1))
            ? new NetworkAutomationService.CommandResult(1, "reload rejected", false, "")
            : new NetworkAutomationService.CommandResult(0, "", false, ""))) {
      Map<String, Object> report = service.refreshNow();

      assertEquals(List.of("verify", "reload"),
          commands.stream().map(command -> command.get(1)).toList());
      assertEquals("# previous\n", Files.readString(managed));
      assertEquals("transaction_recovery_failed", nested(report, "frp", "status"));
      assertEquals("restore_reload_failed",
          nested(report, "frp", "transactionRecovery", "status"));
      assertTrue(Files.isRegularFile(store.stateFile()));
      assertTrue(Files.isRegularFile(store.backupFile()));
    }
  }

  @Test
  void preservesAdministratorEditWhenPendingTransactionContentConflicts() throws Exception {
    Path main = this.temporary.resolve("frpc.toml");
    Files.writeString(main, "includes = [\"frp/starx-api.toml\"]\n");
    Path managed = this.temporary.resolve("frp/starx-api.toml");
    Files.createDirectories(managed.getParent());
    Files.writeString(managed, "# previous\n");
    FrpManagedTransactionStore store =
        FrpManagedTransactionStore.forConfig(this.temporary, main, managed);
    store.begin(
        "# previous\n", "# interrupted candidate\n", Instant.parse("2026-07-28T00:00:00Z"));
    Files.writeString(managed, "# administrator edit\n");
    List<List<String>> commands = new ArrayList<>();
    NetworkAutomationConfig config = config(
        managedFrp(true, "frpc.toml"),
        NetworkAutomationConfig.Certificate.defaults());

    try (NetworkAutomationService service = service(config, commands, command ->
        new NetworkAutomationService.CommandResult(0, "", false, ""))) {
      Map<String, Object> report = service.refreshNow();

      assertTrue(commands.isEmpty());
      assertEquals("# administrator edit\n", Files.readString(managed));
      assertEquals("transaction_recovery_conflict", nested(report, "frp", "status"));
      assertEquals("content_conflict",
          nested(report, "frp", "transactionRecovery", "status"));
      assertTrue(Files.isRegularFile(store.stateFile()));
    }
  }

  @Test
  void managedAutoApplyStopsBeforeMutationWhenLocalTargetIsUnreachable() throws Exception {
    Files.writeString(this.temporary.resolve("frpc.toml"),
        "includes = [\"frp/starx-api.toml\"]\n");
    List<List<String>> commands = new ArrayList<>();
    NetworkAutomationConfig config = config(
        managedFrp(true, "frpc.toml"),
        NetworkAutomationConfig.Certificate.defaults());

    try (NetworkAutomationService service = service(
        config,
        commands,
        command -> new NetworkAutomationService.CommandResult(0, "", false, ""),
        Map.of(),
        (NetworkAutomationService.PortProbe) (host, port, timeout) -> false)) {
      Map<String, Object> report = service.refreshNow();

      assertTrue(commands.isEmpty());
      assertFalse(Files.exists(this.temporary.resolve("frp/starx-api.toml")));
      assertEquals("local_target_unhealthy", nested(report, "frp", "status"));
      assertEquals("CONNECTION_FAILED", nested(report, "frp", "localHealth", "status"));
      assertEquals(false, nested(report, "frp", "localTargetReachable"));
    }
  }

  @Test
  void managedAutoApplyRejectsNonTomlMainConfigExplicitly() throws Exception {
    Files.writeString(this.temporary.resolve("frpc.yaml"), "proxies: []\n");
    List<List<String>> commands = new ArrayList<>();
    NetworkAutomationConfig config = config(
        managedFrp(true, "frpc.yaml"),
        NetworkAutomationConfig.Certificate.defaults());

    try (NetworkAutomationService service = service(config, commands, command ->
        new NetworkAutomationService.CommandResult(0, "", false, ""))) {
      Map<String, Object> report = service.refreshNow();

      assertTrue(commands.isEmpty());
      assertEquals("managed_config_format_unsupported", nested(report, "frp", "status"));
      assertEquals("YAML", nested(report, "frp", "mainConfigFormat"));
    }
  }

  @Test
  void managedAutoApplyRollsBackWhenSemanticHealthFailsAfterReload() throws Exception {
    Files.writeString(this.temporary.resolve("frpc.toml"),
        "includes = [\"frp/starx-api.toml\"]\n");
    Path managed = this.temporary.resolve("frp/starx-api.toml");
    Files.createDirectories(managed.getParent());
    Files.writeString(managed, "# previous\n");
    List<List<String>> commands = new ArrayList<>();
    AtomicInteger probes = new AtomicInteger();
    NetworkAutomationConfig config = config(
        managedFrp(true, "frpc.toml"),
        NetworkAutomationConfig.Certificate.defaults());

    NetworkAutomationService.HealthProbe healthProbe = (host, port, timeout) ->
        probes.incrementAndGet() == 1
            ? StarxHealthProbe.synthetic(host, port, true)
            : new StarxHealthProbe.Result(
                StarxHealthProbe.Status.INVALID_STATUS,
                StarxHealthProbe.endpoint(host, port),
                200,
                "health payload status is not ok");

    try (NetworkAutomationService service = service(
        config, commands, command -> {
          String output = "status".equals(command.get(1))
              ? "starx-api tcp running 0.0.0.0:45123\n"
              : "";
          return new NetworkAutomationService.CommandResult(0, output, false, "");
        }, Map.of(), healthProbe)) {
      Map<String, Object> report = service.refreshNow();

      assertEquals("# previous\n", Files.readString(managed));
      assertEquals("local_target_unhealthy_after_reload", nested(report, "frp", "status"));
      assertEquals("INVALID_STATUS",
          nested(report, "frp", "localHealthAfterReload", "status"));
      assertEquals("restored_and_reloaded", nested(report, "frp", "rollback"));
      assertEquals(List.of("verify", "reload", "status", "verify", "reload"),
          commands.stream().map(command -> command.get(1)).toList());
    }
  }

  @Test
  void managedAutoApplyRestoresPreviousConfigWhenStatusCannotConfirmPort() throws Exception {
    Files.writeString(this.temporary.resolve("frpc.toml"),
        "includes = [\"frp/starx-api.toml\"]\n");
    Path managed = this.temporary.resolve("frp/starx-api.toml");
    Files.createDirectories(managed.getParent());
    Files.writeString(managed, "# previous\n");
    List<List<String>> commands = new ArrayList<>();
    NetworkAutomationConfig config = config(
        managedFrp(true, "frpc.toml"),
        NetworkAutomationConfig.Certificate.defaults());

    try (NetworkAutomationService service = service(config, commands, command -> {
      String action = command.get(1);
      String output = "status".equals(action) ? "no assigned port\n" : "";
      return new NetworkAutomationService.CommandResult(0, output, false, "");
    })) {
      Map<String, Object> report = service.refreshNow();

      assertEquals("# previous\n", Files.readString(managed));
      assertEquals("assigned_port_not_reported", nested(report, "frp", "status"));
      assertEquals("restored_and_reloaded", nested(report, "frp", "rollback"));
      assertEquals(List.of("verify", "reload", "status", "verify", "reload"),
          commands.stream().map(command -> command.get(1)).toList());
    }
  }

  @Test
  void includesRuntimePortSelectionInTheReport() throws Exception {
    List<List<String>> commands = new ArrayList<>();
    Map<String, Object> ports = Map.of(
        "http", Map.of(
            "configuredPort", 8788,
            "selectedPort", 8790,
            "changed", true));
    NetworkAutomationConfig config = config(
        offFrp(),
        NetworkAutomationConfig.Certificate.defaults());

    try (NetworkAutomationService service = service(
        config, commands, command ->
            new NetworkAutomationService.CommandResult(0, "", false, ""), ports)) {
      Map<String, Object> report = service.refreshNow();

      assertEquals(ports, report.get("ports"));
    }
  }

  @Test
  void unconfirmedHttp01RouteNeverInvokesCertbot() throws Exception {
    List<List<String>> commands = new ArrayList<>();
    NetworkAutomationConfig.Certificate certificate =
        new NetworkAutomationConfig.Certificate(
            true,
            "panel.example.com",
            "admin@example.com",
            NetworkAutomationConfig.Certificate.Client.AUTO,
            NetworkAutomationConfig.Certificate.Challenge.HTTP_01,
            true,
            true,
            8789,
            false,
            30);
    NetworkAutomationConfig config = config(offFrp(), certificate);

    try (NetworkAutomationService service = service(config, commands, command ->
        new NetworkAutomationService.CommandResult(0, "", false, ""))) {
      Map<String, Object> report = service.refreshNow();

      assertTrue(commands.isEmpty());
      assertEquals("HTTP_ROUTE_UNCONFIRMED",
          nested(report, "certificate", "status"));
      assertEquals(false,
          nested(report, "certificate", "autoRunAllowed"));
    }
  }

  @Test
  void occupiedHttp01PortPreventsAnyCertbotExecution() throws Exception {
    try (ServerSocket occupied = new ServerSocket(
        0, 1, InetAddress.getByName("127.0.0.1"))) {
      List<List<String>> commands = new ArrayList<>();
      NetworkAutomationConfig config = config(
          offFrp(),
          certificate(occupied.getLocalPort(), false));

      try (NetworkAutomationService service = service(config, commands, command ->
          new NetworkAutomationService.CommandResult(0, "", false, ""))) {
        Map<String, Object> report = service.refreshNow();

        assertTrue(commands.isEmpty());
        assertEquals("http01_port_occupied",
            nested(report, "certificate", "execution"));
        assertEquals(occupied.getLocalPort(),
            nested(report, "certificate", "http01LocalPort"));
        assertEquals("CHALLENGE_PORT_OCCUPIED",
            nested(report, "certificate", "failureClass"));
        assertTrue(Files.isRegularFile(Path.of((String)
            nested(report, "certificate", "attemptStateFile"))));
      }
    }
  }

  @Test
  void heldCertificateOperationLockPreventsConcurrentCertbotExecution() throws Exception {
    List<List<String>> commands = new ArrayList<>();
    int challengePort;
    try (ServerSocket available = new ServerSocket(
        0, 1, InetAddress.getByName("127.0.0.1"))) {
      challengePort = available.getLocalPort();
    }
    NetworkAutomationConfig config = config(offFrp(), certificate(challengePort, false));
    Path lockFile = CertificateAttemptStateStore
        .forLineage(this.temporary, "panel.example.com")
        .lockFile();

    try (NetworkOperationLock ignored =
        NetworkOperationLock.tryAcquire(lockFile).orElseThrow();
        NetworkAutomationService service = service(config, commands, command ->
            new NetworkAutomationService.CommandResult(0, "", false, ""))) {
      Map<String, Object> report = service.refreshNow();

      assertTrue(commands.isEmpty());
      assertEquals("operation_locked",
          nested(report, "certificate", "execution"));
    }
  }

  @Test
  void persistentBackoffSurvivesServiceRestart() throws Exception {
    int challengePort;
    try (ServerSocket available = new ServerSocket(
        0, 1, InetAddress.getByName("127.0.0.1"))) {
      challengePort = available.getLocalPort();
    }
    NetworkAutomationConfig config = config(offFrp(), certificate(challengePort, false));
    Instant firstAttempt = Instant.parse("2026-07-29T00:00:00Z");
    AtomicInteger firstCalls = new AtomicInteger();

    try (NetworkAutomationService service = service(
        config,
        new ArrayList<>(),
        command -> {
          firstCalls.incrementAndGet();
          return new NetworkAutomationService.CommandResult(
              1, "urn:ietf:params:acme:error:rateLimited Too many requests", false, "");
        },
        Clock.fixed(firstAttempt, ZoneOffset.UTC))) {
      Map<String, Object> report = service.refreshNow();

      assertEquals("production_failed", nested(report, "certificate", "execution"));
      assertEquals("ACME_RATE_LIMIT", nested(report, "certificate", "failureClass"));
      assertEquals(1, firstCalls.get());
    }

    AtomicInteger secondCalls = new AtomicInteger();
    try (NetworkAutomationService service = service(
        config,
        new ArrayList<>(),
        command -> {
          secondCalls.incrementAndGet();
          return new NetworkAutomationService.CommandResult(0, "", false, "");
        },
        Clock.fixed(firstAttempt.plus(java.time.Duration.ofHours(1)), ZoneOffset.UTC))) {
      Map<String, Object> report = service.refreshNow();

      assertEquals("backoff_active", nested(report, "certificate", "execution"));
      assertEquals("ACME_RATE_LIMIT", nested(report, "certificate", "failureClass"));
      assertEquals(0, secondCalls.get());
    }
  }

  @Test
  void productionPortIsRecheckedImmediatelyBeforeLaunch() throws Exception {
    int challengePort;
    try (ServerSocket available = new ServerSocket(
        0, 1, InetAddress.getByName("127.0.0.1"))) {
      challengePort = available.getLocalPort();
    }
    NetworkAutomationConfig config = config(offFrp(), certificate(challengePort, true));
    AtomicInteger calls = new AtomicInteger();
    AtomicReference<ServerSocket> occupied = new AtomicReference<>();

    try (NetworkAutomationService service = service(config, new ArrayList<>(), command -> {
      if (calls.incrementAndGet() == 1) {
        try {
          occupied.set(new ServerSocket(
              challengePort, 1, InetAddress.getByName("127.0.0.1")));
        } catch (Exception error) {
          throw new IllegalStateException(error);
        }
      }
      return new NetworkAutomationService.CommandResult(0, "", false, "");
    })) {
      Map<String, Object> report = service.refreshNow();

      assertEquals(1, calls.get());
      assertEquals("production_port_occupied",
          nested(report, "certificate", "execution"));
      assertEquals("CHALLENGE_PORT_OCCUPIED",
          nested(report, "certificate", "failureClass"));
    } finally {
      if (occupied.get() != null) {
        occupied.get().close();
      }
    }
  }

  @Test
  void malformedPersistentAttemptStatePreventsCertbotExecution() throws Exception {
    int challengePort;
    try (ServerSocket available = new ServerSocket(
        0, 1, InetAddress.getByName("127.0.0.1"))) {
      challengePort = available.getLocalPort();
    }
    CertificateAttemptStateStore store = CertificateAttemptStateStore
        .forLineage(this.temporary, "panel.example.com");
    Files.createDirectories(store.stateFile().getParent());
    Files.writeString(store.stateFile(), "{");
    List<List<String>> commands = new ArrayList<>();

    try (NetworkAutomationService service = service(
        config(offFrp(), certificate(challengePort, false)),
        commands,
        command -> new NetworkAutomationService.CommandResult(0, "", false, ""))) {
      Map<String, Object> report = service.refreshNow();

      assertTrue(commands.isEmpty());
      assertEquals("attempt_state_invalid",
          nested(report, "certificate", "execution"));
      assertEquals("INVALID", nested(report, "certificate", "attemptStateStatus"));
    }
  }

  @Test
  void successfulProductionClearsPersistentBackoff() throws Exception {
    int challengePort;
    try (ServerSocket available = new ServerSocket(
        0, 1, InetAddress.getByName("127.0.0.1"))) {
      challengePort = available.getLocalPort();
    }
    List<List<String>> commands = new ArrayList<>();
    NetworkAutomationConfig config = config(offFrp(), certificate(challengePort, false));

    try (NetworkAutomationService service = service(config, commands, command ->
        new NetworkAutomationService.CommandResult(0, "issued", false, ""))) {
      Map<String, Object> report = service.refreshNow();

      assertEquals("production_succeeded", nested(report, "certificate", "execution"));
      assertEquals("SUCCEEDED", nested(report, "certificate", "attemptState", "outcome"));
      assertEquals(0, nested(report, "certificate", "attemptState", "consecutiveFailures"));
      assertEquals(1, commands.size());
    }

    CertificateAttemptStateStore.LoadResult loaded = CertificateAttemptStateStore
        .forLineage(this.temporary, "panel.example.com")
        .load();
    assertEquals(CertificateAttemptStateStore.Outcome.SUCCEEDED,
        loaded.snapshot().outcome());
    assertEquals(0, loaded.snapshot().consecutiveFailures());
    assertEquals(null, loaded.snapshot().nextAllowedAt());
  }

  @Test
  void validCertificateOutsideRenewalWindowDoesNotInvokeCertbot() throws Exception {
    Path fullChain = this.temporary.resolve(
        "certificates/production/config/live/panel.example.com/fullchain.pem");
    Files.createDirectories(fullChain.getParent());
    Files.writeString(fullChain, CertificateRenewalPolicyTest.VALID_CERTIFICATE);
    List<List<String>> commands = new ArrayList<>();
    NetworkAutomationConfig.Certificate certificate =
        new NetworkAutomationConfig.Certificate(
            true,
            "panel.example.com",
            "admin@example.com",
            NetworkAutomationConfig.Certificate.Client.AUTO,
            NetworkAutomationConfig.Certificate.Challenge.HTTP_01,
            true,
            true,
            8789,
            true,
            30);
    NetworkAutomationConfig config = config(offFrp(), certificate);

    try (NetworkAutomationService service = service(config, commands, command ->
        new NetworkAutomationService.CommandResult(0, "", false, ""))) {
      Map<String, Object> report = service.refreshNow();

      assertTrue(commands.isEmpty());
      assertEquals("not_due", nested(report, "certificate", "execution"));
      assertEquals("VALID", nested(report, "certificate", "renewal", "status"));
      assertEquals(false, nested(report, "certificate", "renewal", "due"));
    }
  }

  private NetworkAutomationService service(
      NetworkAutomationConfig config,
      List<List<String>> commands,
      FakeCommand command) {
    return service(config, commands, command, Map.of());
  }

  private NetworkAutomationService service(
      NetworkAutomationConfig config,
      List<List<String>> commands,
      FakeCommand command,
      Map<String, Object> ports) {
    return service(
        config,
        commands,
        command,
        ports,
        (NetworkAutomationService.PortProbe) (host, port, timeout) -> true);
  }

  private NetworkAutomationService service(
      NetworkAutomationConfig config,
      List<List<String>> commands,
      FakeCommand command,
      Clock clock) {
    return service(
        config, commands, command, Map.of(), (host, port, timeout) -> true, clock);
  }

  private NetworkAutomationService service(
      NetworkAutomationConfig config,
      List<List<String>> commands,
      FakeCommand command,
      Map<String, Object> ports,
      NetworkAutomationService.PortProbe portProbe) {
    return service(
        config,
        commands,
        command,
        ports,
        portProbe,
        Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), ZoneOffset.UTC));
  }

  private NetworkAutomationService service(
      NetworkAutomationConfig config,
      List<List<String>> commands,
      FakeCommand command,
      Map<String, Object> ports,
      NetworkAutomationService.HealthProbe healthProbe) {
    return new NetworkAutomationService(
        config,
        new StarxConfig.HttpConfig("127.0.0.1", 8788),
        this.temporary,
        Logger.getAnonymousLogger(),
        () -> confirmed("8.8.8.8"),
        () -> List.of(LocalAddressInfo.parse("192.168.1.10")),
        (args, timeout) -> {
          commands.add(args);
          return command.run(args);
        },
        healthProbe,
        Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), ZoneOffset.UTC),
        ports);
  }

  private NetworkAutomationService service(
      NetworkAutomationConfig config,
      List<List<String>> commands,
      FakeCommand command,
      Map<String, Object> ports,
      NetworkAutomationService.PortProbe portProbe,
      Clock clock) {
    return new NetworkAutomationService(
        config,
        new StarxConfig.HttpConfig("127.0.0.1", 8788),
        this.temporary,
        Logger.getAnonymousLogger(),
        () -> confirmed("8.8.8.8"),
        () -> List.of(LocalAddressInfo.parse("192.168.1.10")),
        (args, timeout) -> {
          commands.add(args);
          return command.run(args);
        },
        portProbe,
        clock,
        ports);
  }

  private static NetworkAutomationConfig config(
      NetworkAutomationConfig.Frp frp,
      NetworkAutomationConfig.Certificate certificate) {
    return new NetworkAutomationConfig(
        true,
        "network-automation.json",
        new NetworkAutomationConfig.PublicAddress(
            false, 2, 1000, List.of()),
        frp,
        certificate);
  }

  private static NetworkAutomationConfig.Frp managedFrp(
      boolean autoApply,
      String mainConfig) {
    return new NetworkAutomationConfig.Frp(
        NetworkAutomationConfig.Frp.Mode.MANAGED,
        "frp.example.com",
        "http",
        "",
        "starx-api",
        "127.0.0.1",
        8788,
        0,
        "frpc",
        mainConfig,
        "frp/starx-api.toml",
        autoApply);
  }

  private static NetworkAutomationConfig.Certificate certificate(
      int http01Port,
      boolean stagingFirst) {
    return new NetworkAutomationConfig.Certificate(
        true,
        "panel.example.com",
        "admin@example.com",
        NetworkAutomationConfig.Certificate.Client.AUTO,
        NetworkAutomationConfig.Certificate.Challenge.HTTP_01,
        stagingFirst,
        true,
        http01Port,
        true,
        30);
  }

  private static NetworkAutomationConfig.Frp offFrp() {
    return new NetworkAutomationConfig.Frp(
        NetworkAutomationConfig.Frp.Mode.OFF,
        "",
        "http",
        "",
        "starx-api",
        "127.0.0.1",
        8788,
        0,
        "frpc",
        "",
        "frp/starx-api.toml",
        false);
  }

  private static PublicAddressConsensus.Result confirmed(String address) {
    return new PublicAddressConsensus.Result(
        PublicAddressConsensus.Status.CONFIRMED,
        address,
        2,
        2,
        Map.of("one.example", address, "two.example", address),
        List.of());
  }

  private static Object nested(Map<String, Object> root, String... path) {
    Object current = root;
    for (String key : path) {
      current = ((Map<?, ?>) current).get(key);
    }
    return current;
  }

  @FunctionalInterface
  private interface FakeCommand {
    NetworkAutomationService.CommandResult run(List<String> command);
  }
}
