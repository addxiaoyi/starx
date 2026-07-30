package io.github.addxiaoyi.starx.velocity.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.addxiaoyi.starx.velocity.config.NetworkAutomationConfig;
import io.github.addxiaoyi.starx.velocity.config.StarxConfig;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Produces an auditable network automation report and optionally applies explicitly enabled
 * FRP/ACME actions. Defaults are detection-only.
 */
public final class NetworkAutomationService implements AutoCloseable {
  private static final Duration REFRESH_INTERVAL = Duration.ofMinutes(30);
  private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);
  private static final int MAX_COMMAND_OUTPUT = 65_536;

  private final NetworkAutomationConfig config;
  private final StarxConfig.HttpConfig http;
  private final Path dataDirectory;
  private final Logger logger;
  private final AddressDetector detector;
  private final Supplier<List<LocalAddressInfo>> localAddressSupplier;
  private final CommandRunner commandRunner;
  private final HealthProbe healthProbe;
  private final Clock clock;
  private final Map<String, Object> portAllocation;
  private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
  private final ScheduledExecutorService scheduler;
  private final AtomicBoolean started = new AtomicBoolean();
  private final AtomicBoolean refreshing = new AtomicBoolean();
  private volatile Map<String, Object> snapshot = Map.of("status", "not_started");

  public NetworkAutomationService(
      NetworkAutomationConfig config,
      StarxConfig.HttpConfig http,
      Path dataDirectory,
      Logger logger) {
    this(config, http, dataDirectory, logger, Map.of());
  }

  public NetworkAutomationService(
      NetworkAutomationConfig config,
      StarxConfig.HttpConfig http,
      Path dataDirectory,
      Logger logger,
      Map<String, Object> portAllocation) {
    this(
        config,
        http,
        dataDirectory,
        logger,
        new PublicAddressDetector(config.publicAddress())::detect,
        NetworkAutomationService::localAddresses,
        new ProcessCommandRunner(),
        StarxHealthProbe::probe,
        Clock.systemUTC(),
        portAllocation);
  }

  NetworkAutomationService(
      NetworkAutomationConfig config,
      StarxConfig.HttpConfig http,
      Path dataDirectory,
      Logger logger,
      AddressDetector detector,
      Supplier<List<LocalAddressInfo>> localAddressSupplier,
      CommandRunner commandRunner,
      Clock clock) {
    this(
        config, http, dataDirectory, logger, detector, localAddressSupplier,
        commandRunner, StarxHealthProbe::probe, clock, Map.of());
  }

  NetworkAutomationService(
      NetworkAutomationConfig config,
      StarxConfig.HttpConfig http,
      Path dataDirectory,
      Logger logger,
      AddressDetector detector,
      Supplier<List<LocalAddressInfo>> localAddressSupplier,
      CommandRunner commandRunner,
      PortProbe portProbe,
      Clock clock,
      Map<String, Object> portAllocation) {
    this(
        config,
        http,
        dataDirectory,
        logger,
        detector,
        localAddressSupplier,
        commandRunner,
        (HealthProbe) (host, port, timeout) -> StarxHealthProbe.synthetic(
            host, port, portProbe.reachable(host, port, timeout)),
        clock,
        portAllocation);
  }

  NetworkAutomationService(
      NetworkAutomationConfig config,
      StarxConfig.HttpConfig http,
      Path dataDirectory,
      Logger logger,
      AddressDetector detector,
      Supplier<List<LocalAddressInfo>> localAddressSupplier,
      CommandRunner commandRunner,
      HealthProbe healthProbe,
      Clock clock,
      Map<String, Object> portAllocation) {
    this.config = Objects.requireNonNull(config, "config");
    this.http = Objects.requireNonNull(http, "http");
    this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory")
        .toAbsolutePath().normalize();
    this.logger = Objects.requireNonNull(logger, "logger");
    this.detector = Objects.requireNonNull(detector, "detector");
    this.localAddressSupplier = Objects.requireNonNull(localAddressSupplier, "localAddressSupplier");
    this.commandRunner = Objects.requireNonNull(commandRunner, "commandRunner");
    this.healthProbe = Objects.requireNonNull(healthProbe, "healthProbe");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.portAllocation = portAllocation == null ? Map.of() : Map.copyOf(portAllocation);
    this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
      Thread thread = new Thread(runnable, "starx-network-automation");
      thread.setDaemon(true);
      return thread;
    });
  }

  public void start() {
    if (!this.config.enabled() || !this.started.compareAndSet(false, true)) {
      return;
    }
    this.scheduler.scheduleWithFixedDelay(
        this::refreshSafely,
        0,
        REFRESH_INTERVAL.toMinutes(),
        TimeUnit.MINUTES);
  }

  public Map<String, Object> snapshot() {
    return this.snapshot;
  }

  Map<String, Object> refreshNow() throws IOException {
    List<LocalAddressInfo> localAddresses = this.localAddressSupplier.get().stream()
        .filter(Objects::nonNull)
        .sorted(Comparator.comparing(LocalAddressInfo::address))
        .toList();
    PublicAddressConsensus.Result publicAddress = this.detector.detect();
    NetworkEnvironmentAssessment.Result topology =
        NetworkEnvironmentAssessment.assess(localAddresses, publicAddress);

    Map<String, Object> frp = handleFrp();
    Map<String, Object> certificate = handleCertificate();
    Map<String, Object> endpoint = selectEndpoint(topology, frp);
    List<String> recommendations = recommendations(topology, frp, certificate);

    LinkedHashMap<String, Object> report = new LinkedHashMap<>();
    report.put("status", "ready");
    report.put("generatedAt", this.clock.instant().toString());
    report.put("ports", this.portAllocation);
    report.put("localAddresses", localAddresses.stream().map(info -> Map.of(
        "address", info.address(),
        "scope", info.scope().name(),
        "label", info.locationLabel())).toList());
    report.put("publicAddress", Map.of(
        "status", publicAddress.status().name(),
        "address", publicAddress.address(),
        "agreement", publicAddress.agreement(),
        "validSources", publicAddress.validSources(),
        "observations", publicAddress.observations(),
        "rejectedSources", publicAddress.rejectedSources()));
    report.put("topology", Map.of(
        "type", topology.topology().name(),
        "reason", topology.reason(),
        "directAddressConfirmed", topology.directAddressConfirmed(),
        "externalAddress", topology.externallyObservedAddress(),
        "localPublicAddresses", topology.localPublicAddresses()));
    report.put("inboundPortConfirmed", false);
    report.put("frp", frp);
    report.put("certificate", certificate);
    report.put("selectedEndpoint", endpoint);
    report.put("recommendations", recommendations);
    Map<String, Object> immutable = Map.copyOf(report);
    writeReport(immutable);
    this.snapshot = immutable;
    return immutable;
  }

  private void refreshSafely() {
    if (!this.refreshing.compareAndSet(false, true)) {
      return;
    }
    try {
      refreshNow();
    } catch (Exception error) {
      this.snapshot = Map.of(
          "status", "failed",
          "generatedAt", this.clock.instant().toString(),
          "error", safeMessage(error));
      this.logger.log(Level.WARNING, "StarX network automation refresh failed", error);
    } finally {
      this.refreshing.set(false);
    }
  }

  private Map<String, Object> handleFrp() throws IOException {
    NetworkAutomationConfig.Frp frp = this.config.frp();
    LinkedHashMap<String, Object> result = new LinkedHashMap<>();
    result.put("mode", frp.mode().name());
    result.put("proxyName", frp.proxyName());
    result.put("remotePortPolicy", frp.mode() == NetworkAutomationConfig.Frp.Mode.MANAGED
        ? "FRPS_ATOMIC_ASSIGNMENT"
        : "NOT_MANAGED");
    result.put("autoApply", frp.autoApply());

    String configuredUrl = explicitFrpUrl(frp);
    FrpInstallationDetector.Result installation =
        FrpInstallationDetector.detect(frp, this.dataDirectory);
    Path mainConfig = installation.mainConfig();
    String frpcCommand = installation.command();
    result.put("discoverySource", installation.source().name());
    if (mainConfig != null) {
      result.put("mainConfigFile", mainConfig.toString());
    }
    if (frp.mode() == NetworkAutomationConfig.Frp.Mode.OFF) {
      result.put("status", configuredUrl.isBlank() ? "disabled" : "manual_url_configured");
      result.put("publicUrl", configuredUrl);
      return Map.copyOf(result);
    }

    if (frp.mode() == NetworkAutomationConfig.Frp.Mode.DETECT) {
      OptionalInt port = readAssignedPort(frp, frpcCommand, mainConfig);
      if (port.isPresent()) {
        result.put("status", "assigned_port_confirmed");
        result.put("assignedPort", port.getAsInt());
        result.put("publicUrl", buildFrpUrl(frp, port.getAsInt()));
      } else {
        result.put("status", configuredUrl.isBlank()
            ? "no_confirmed_assignment"
            : "manual_url_configured");
        result.put("publicUrl", configuredUrl);
      }
      return Map.copyOf(result);
    }

    Path managedConfig = resolveDataFile(frp.managedConfigFile());
    result.put("managedConfigFile", managedConfig.toString());
    if (!frp.autoApply()) {
      writeAtomically(managedConfig, FrpManagedProxy.render(frp));
      result.put("status", "config_written_awaiting_auto_apply");
      result.put("publicUrl", configuredUrl);
      return Map.copyOf(result);
    }
    if (!installation.configPresent() || mainConfig == null) {
      result.put("status", "main_config_missing");
      result.put("publicUrl", configuredUrl);
      return Map.copyOf(result);
    }
    String mainConfigFormat = mainConfigFormat(mainConfig);
    result.put("mainConfigFormat", mainConfigFormat);
    if (!"TOML".equals(mainConfigFormat)) {
      result.put("status", "managed_config_format_unsupported");
      result.put("diagnostic", "managed FRP include editing requires a TOML main config");
      result.put("publicUrl", configuredUrl);
      return Map.copyOf(result);
    }
    if (!mainConfigIncludes(mainConfig, frp.managedConfigFile(), managedConfig)) {
      result.put("status", "managed_include_missing");
      result.put("publicUrl", configuredUrl);
      return Map.copyOf(result);
    }

    Path lockFile = mainConfig.resolveSibling(
        mainConfig.getFileName() + ".starx.lock");
    Optional<NetworkOperationLock> acquired = NetworkOperationLock.tryAcquire(lockFile);
    if (acquired.isEmpty()) {
      result.put("status", "operation_locked");
      result.put("publicUrl", configuredUrl);
      return Map.copyOf(result);
    }

    try (NetworkOperationLock ignored = acquired.orElseThrow()) {
      FrpManagedTransactionStore transactionStore =
          FrpManagedTransactionStore.forConfig(this.dataDirectory, mainConfig, managedConfig);
      result.put("transactionStateFile", transactionStore.stateFile().toString());
      result.put("transactionBackupFile", transactionStore.backupFile().toString());
      FrpRecovery recovery = recoverPendingFrpTransaction(
          transactionStore, mainConfig, frpcCommand);
      result.put("transactionStateStatus", recovery.loadStatus());
      result.put("transactionRecovery", recovery.report());
      if (!recovery.ready()) {
        result.put("status", switch (recovery.status()) {
          case "state_invalid" -> "transaction_recovery_state_invalid";
          case "content_conflict" -> "transaction_recovery_conflict";
          default -> "transaction_recovery_failed";
        });
        result.put("diagnostic", recovery.diagnostic());
        result.put("publicUrl", configuredUrl);
        return Map.copyOf(result);
      }

      StarxHealthProbe.Result initialHealth = probeLocalTarget(frp);
      result.put("localHealth", healthInfo(initialHealth));
      if (!initialHealth.healthy()) {
        result.put("status", "local_target_unhealthy");
        result.put("localTargetReachable", false);
        result.put("diagnostic", initialHealth.diagnostic());
        result.put("publicUrl", configuredUrl);
        return Map.copyOf(result);
      }
      result.put("localTargetReachable", true);

      String previousConfig = Files.isRegularFile(managedConfig)
          ? Files.readString(managedConfig, StandardCharsets.UTF_8)
          : null;
      String desiredConfig = FrpManagedProxy.render(frp);
      FrpManagedTransactionStore.Snapshot transaction = transactionStore.begin(
          previousConfig, desiredConfig, this.clock.instant());
      result.put("transaction", transaction.report());
      writeAtomically(managedConfig, desiredConfig);

      CommandResult verify = run(List.of(
          frpcCommand, "verify", "-c", mainConfig.toString()), COMMAND_TIMEOUT);
      if (!verify.success()) {
        result.put("status", "verify_failed");
        result.put("diagnostic", verify.summary());
        result.put("rollback", rollbackManagedConfig(
            transactionStore, transaction, mainConfig, frpcCommand, false));
        result.put("publicUrl", configuredUrl);
        return Map.copyOf(result);
      }
      transaction = transactionStore.updatePhase(
          transaction,
          FrpManagedTransactionStore.Phase.RELOAD_REQUIRED,
          this.clock.instant());
      result.put("transaction", transaction.report());
      CommandResult reload = run(List.of(
          frpcCommand, "reload", "-c", mainConfig.toString()), COMMAND_TIMEOUT);
      if (!reload.success()) {
        result.put("status", "reload_failed");
        result.put("diagnostic", reload.summary());
        result.put("rollback", rollbackManagedConfig(
            transactionStore, transaction, mainConfig, frpcCommand, true));
        result.put("publicUrl", configuredUrl);
        return Map.copyOf(result);
      }
      CommandResult status = run(List.of(
          frpcCommand, "status", "-c", mainConfig.toString()), COMMAND_TIMEOUT);
      OptionalInt port = status.success()
          ? FrpManagedProxy.parseAssignedPort(status.output(), frp.proxyName())
          : OptionalInt.empty();
      if (port.isEmpty()) {
        result.put("status", status.success()
            ? "assigned_port_not_reported"
            : "status_failed");
        result.put("diagnostic", status.summary());
        result.put("rollback", rollbackManagedConfig(
            transactionStore, transaction, mainConfig, frpcCommand, true));
        result.put("publicUrl", configuredUrl);
        return Map.copyOf(result);
      }
      StarxHealthProbe.Result reloadedHealth = probeLocalTarget(frp);
      result.put("localHealthAfterReload", healthInfo(reloadedHealth));
      if (!reloadedHealth.healthy()) {
        result.put("status", "local_target_unhealthy_after_reload");
        result.put("localTargetReachable", false);
        result.put("diagnostic", reloadedHealth.diagnostic());
        result.put("rollback", rollbackManagedConfig(
            transactionStore, transaction, mainConfig, frpcCommand, true));
        result.put("publicUrl", configuredUrl);
        return Map.copyOf(result);
      }

      transactionStore.clear();
      result.put("transactionOutcome", "committed");
      result.put("status", "assigned_port_confirmed");
      result.put("assignedPort", port.getAsInt());
      result.put("publicUrl", buildFrpUrl(frp, port.getAsInt()));
      return Map.copyOf(result);
    }
  }

  private StarxHealthProbe.Result probeLocalTarget(NetworkAutomationConfig.Frp frp) {
    return this.healthProbe.probe(
        frp.localAddress(), frp.localPort(), Duration.ofSeconds(2));
  }

  private static Map<String, Object> healthInfo(StarxHealthProbe.Result health) {
    LinkedHashMap<String, Object> info = new LinkedHashMap<>();
    info.put("status", health.status().name());
    info.put("endpoint", health.endpoint());
    info.put("httpStatus", health.httpStatus());
    info.put("diagnostic", health.diagnostic());
    return Map.copyOf(info);
  }

  private static String mainConfigFormat(Path mainConfig) {
    String name = mainConfig.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
    return name.endsWith(".toml") ? "TOML"
        : name.endsWith(".yaml") || name.endsWith(".yml") ? "YAML"
        : name.endsWith(".ini") ? "INI"
        : "UNKNOWN";
  }

  private FrpRecovery recoverPendingFrpTransaction(
      FrpManagedTransactionStore transactionStore,
      Path mainConfig,
      String frpcCommand) {
    FrpManagedTransactionStore.LoadResult load = transactionStore.load();
    if (load.status() == FrpManagedTransactionStore.LoadStatus.MISSING) {
      return new FrpRecovery(true, "not_needed", "", load.status().name(), Map.of());
    }
    if (load.status() == FrpManagedTransactionStore.LoadStatus.INVALID) {
      return new FrpRecovery(
          false, "state_invalid", load.diagnostic(), load.status().name(), Map.of());
    }

    FrpManagedTransactionStore.Snapshot transaction = load.snapshot();
    try {
      if (!transactionStore.currentContentCompatible(transaction)) {
        return new FrpRecovery(
            false,
            "content_conflict",
            "managed FRP config no longer matches the pending transaction",
            load.status().name(),
            transaction.report());
      }
      transactionStore.restore(transaction);
      if (transaction.phase() == FrpManagedTransactionStore.Phase.RELOAD_REQUIRED) {
        CommandResult verify = run(List.of(
            frpcCommand, "verify", "-c", mainConfig.toString()), COMMAND_TIMEOUT);
        if (!verify.success()) {
          return new FrpRecovery(
              false,
              "restore_verify_failed",
              verify.summary(),
              load.status().name(),
              transaction.report());
        }
        CommandResult reload = run(List.of(
            frpcCommand, "reload", "-c", mainConfig.toString()), COMMAND_TIMEOUT);
        if (!reload.success()) {
          return new FrpRecovery(
              false,
              "restore_reload_failed",
              reload.summary(),
              load.status().name(),
              transaction.report());
        }
      }
      transactionStore.clear();
      return new FrpRecovery(
          true,
          transaction.phase() == FrpManagedTransactionStore.Phase.RELOAD_REQUIRED
              ? "restored_and_reloaded"
              : "restored",
          "",
          load.status().name(),
          transaction.report());
    } catch (IOException error) {
      return new FrpRecovery(
          false,
          "io_failed",
          safeMessage(error),
          load.status().name(),
          transaction.report());
    }
  }

  private String rollbackManagedConfig(
      FrpManagedTransactionStore transactionStore,
      FrpManagedTransactionStore.Snapshot transaction,
      Path mainConfig,
      String frpcCommand,
      boolean reloadRequired) throws IOException {
    transactionStore.restore(transaction);
    if (!reloadRequired) {
      transactionStore.clear();
      return "restored";
    }
    CommandResult verify = run(List.of(
        frpcCommand, "verify", "-c", mainConfig.toString()), COMMAND_TIMEOUT);
    if (!verify.success()) {
      return "restore_verify_failed: " + verify.summary();
    }
    CommandResult reload = run(List.of(
        frpcCommand, "reload", "-c", mainConfig.toString()), COMMAND_TIMEOUT);
    if (!reload.success()) {
      return "restore_reload_failed: " + reload.summary();
    }
    transactionStore.clear();
    return "restored_and_reloaded";
  }

  private OptionalInt readAssignedPort(
      NetworkAutomationConfig.Frp frp,
      String frpcCommand,
      Path mainConfig) {
    if (mainConfig == null || !Files.isRegularFile(mainConfig)) {
      return OptionalInt.empty();
    }
    CommandResult status = run(List.of(
        frpcCommand, "status", "-c", mainConfig.toString()), COMMAND_TIMEOUT);
    return status.success()
        ? FrpManagedProxy.parseAssignedPort(status.output(), frp.proxyName())
        : OptionalInt.empty();
  }

  private Map<String, Object> handleCertificate() {
    CertificateCommandPlanner.Plan plan =
        CertificateCommandPlanner.plan(this.dataDirectory, this.config.certificate());
    LinkedHashMap<String, Object> result = new LinkedHashMap<>();
    result.put("status", plan.status().name());
    result.put("reason", plan.reason());
    result.put("autoRunConfigured", this.config.certificate().autoRun());
    result.put("autoRunAllowed", plan.autoRunAllowed());
    if (plan.fullChain() != null) {
      result.put("fullChain", plan.fullChain().toString());
      result.put("privateKey", plan.privateKey().toString());
    }
    if (!plan.ready() || !plan.autoRunAllowed()) {
      return Map.copyOf(result);
    }

    Instant now = this.clock.instant();
    CertificateRenewalPolicy.Decision renewal = CertificateRenewalPolicy.evaluate(
        plan.fullChain(), now, this.config.certificate().renewBeforeDays());
    result.put("renewal", renewalInfo(renewal));
    if (!renewal.due()) {
      result.put("execution", "not_due");
      return Map.copyOf(result);
    }

    String lineage = plan.fullChain().getParent().getFileName().toString();
    CertificateAttemptStateStore attemptStore =
        CertificateAttemptStateStore.forLineage(this.dataDirectory, lineage);
    result.put("lineage", lineage);
    result.put("operationLock", attemptStore.lockFile().toString());
    result.put("attemptStateFile", attemptStore.stateFile().toString());

    Optional<NetworkOperationLock> acquired;
    try {
      acquired = NetworkOperationLock.tryAcquire(attemptStore.lockFile());
    } catch (IOException lockError) {
      result.put("execution", "lock_error");
      result.put("diagnostic", safeMessage(lockError));
      return Map.copyOf(result);
    }
    if (acquired.isEmpty()) {
      result.put("execution", "operation_locked");
      return Map.copyOf(result);
    }

    try (NetworkOperationLock ignored = acquired.orElseThrow()) {
      Instant lockedNow = this.clock.instant();
      CertificateRenewalPolicy.Decision lockedRenewal = CertificateRenewalPolicy.evaluate(
          plan.fullChain(), lockedNow, this.config.certificate().renewBeforeDays());
      if (!lockedRenewal.due()) {
        result.put("renewal", renewalInfo(lockedRenewal));
        result.put("execution", "not_due_after_lock");
        return Map.copyOf(result);
      }

      CertificateAttemptStateStore.LoadResult loaded = attemptStore.load();
      result.put("attemptStateStatus", loaded.status().name());
      if (loaded.status() == CertificateAttemptStateStore.LoadStatus.INVALID) {
        result.put("execution", "attempt_state_invalid");
        result.put("diagnostic", loaded.diagnostic());
        return Map.copyOf(result);
      }
      CertificateAttemptStateStore.Snapshot attemptState = loaded.snapshot();
      putAttemptState(result, attemptState);
      if (attemptState.backoffActive(lockedNow)) {
        result.put("execution", "backoff_active");
        result.put("failureClass", attemptState.failureClass().name());
        return Map.copyOf(result);
      }

      int challengePort = this.config.certificate().http01LocalPort();
      if (!http01PortAvailable(challengePort)) {
        attemptState = attemptStore.recordFailure(
            attemptState,
            lockedNow,
            CertificateAttemptStateStore.Phase.PREFLIGHT,
            CertificateAttemptStateStore.FailureClass.CHALLENGE_PORT_OCCUPIED);
        result.put("execution", "http01_port_occupied");
        result.put("failureClass", attemptState.failureClass().name());
        result.put("http01LocalPort", challengePort);
        putAttemptState(result, attemptState);
        return Map.copyOf(result);
      }

      if (!plan.stagingCommand().isEmpty()) {
        attemptState = attemptStore.recordStart(
            attemptState,
            this.clock.instant(),
            CertificateAttemptStateStore.Phase.STAGING);
        putAttemptState(result, attemptState);
        if (!http01PortAvailable(challengePort)) {
          attemptState = attemptStore.recordFailure(
              attemptState,
              this.clock.instant(),
              CertificateAttemptStateStore.Phase.STAGING,
              CertificateAttemptStateStore.FailureClass.CHALLENGE_PORT_OCCUPIED);
          result.put("execution", "staging_port_occupied");
          result.put("failureClass", attemptState.failureClass().name());
          result.put("http01LocalPort", challengePort);
          putAttemptState(result, attemptState);
          return Map.copyOf(result);
        }
        CommandResult staging = run(plan.stagingCommand(), Duration.ofMinutes(5));
        if (!staging.success()) {
          CertificateAttemptStateStore.FailureClass failure =
              CertificateAttemptStateStore.classify(staging);
          attemptState = attemptStore.recordFailure(
              attemptState,
              this.clock.instant(),
              CertificateAttemptStateStore.Phase.STAGING,
              failure);
          result.put("execution", "staging_failed");
          result.put("failureClass", failure.name());
          result.put("diagnostic", staging.summary());
          putAttemptState(result, attemptState);
          return Map.copyOf(result);
        }
      }

      if (!http01PortAvailable(challengePort)) {
        attemptState = attemptStore.recordFailure(
            attemptState,
            this.clock.instant(),
            CertificateAttemptStateStore.Phase.PRODUCTION,
            CertificateAttemptStateStore.FailureClass.CHALLENGE_PORT_OCCUPIED);
        result.put("execution", "production_port_occupied");
        result.put("failureClass", attemptState.failureClass().name());
        result.put("http01LocalPort", challengePort);
        putAttemptState(result, attemptState);
        return Map.copyOf(result);
      }

      attemptState = attemptStore.recordStart(
          attemptState,
          this.clock.instant(),
          CertificateAttemptStateStore.Phase.PRODUCTION);
      putAttemptState(result, attemptState);
      if (!http01PortAvailable(challengePort)) {
        attemptState = attemptStore.recordFailure(
            attemptState,
            this.clock.instant(),
            CertificateAttemptStateStore.Phase.PRODUCTION,
            CertificateAttemptStateStore.FailureClass.CHALLENGE_PORT_OCCUPIED);
        result.put("execution", "production_port_occupied");
        result.put("failureClass", attemptState.failureClass().name());
        result.put("http01LocalPort", challengePort);
        putAttemptState(result, attemptState);
        return Map.copyOf(result);
      }

      CommandResult production = run(plan.productionCommand(), Duration.ofMinutes(5));
      if (production.success()) {
        attemptState = attemptStore.recordSuccess(
            attemptState,
            this.clock.instant(),
            CertificateAttemptStateStore.Phase.PRODUCTION);
        result.put("execution", "production_succeeded");
      } else {
        CertificateAttemptStateStore.FailureClass failure =
            CertificateAttemptStateStore.classify(production);
        attemptState = attemptStore.recordFailure(
            attemptState,
            this.clock.instant(),
            CertificateAttemptStateStore.Phase.PRODUCTION,
            failure);
        result.put("execution", "production_failed");
        result.put("failureClass", failure.name());
      }
      result.put("diagnostic", production.summary());
      putAttemptState(result, attemptState);
      return Map.copyOf(result);
    } catch (IOException stateOrLockError) {
      result.put("execution", "attempt_state_error");
      result.put("diagnostic", safeMessage(stateOrLockError));
      return Map.copyOf(result);
    }
  }

  private static void putAttemptState(
      LinkedHashMap<String, Object> result,
      CertificateAttemptStateStore.Snapshot state) {
    result.put("attemptState", state.report());
  }

  private Map<String, Object> renewalInfo(
      CertificateRenewalPolicy.Decision renewal) {
    LinkedHashMap<String, Object> info = new LinkedHashMap<>();
    info.put("status", renewal.status().name());
    info.put("due", renewal.due());
    info.put("renewBeforeDays", this.config.certificate().renewBeforeDays());
    info.put("reason", renewal.reason());
    if (renewal.notAfter() != null) {
      info.put("notAfter", renewal.notAfter().toString());
    }
    return Map.copyOf(info);
  }

  private boolean http01PortAvailable(int port) {
    try {
      return TcpPortAllocator.isAvailable("*", port);
    } catch (IOException | RuntimeException unavailable) {
      return false;
    }
  }

  private Map<String, Object> selectEndpoint(
      NetworkEnvironmentAssessment.Result topology,
      Map<String, Object> frp) {
    String frpUrl = Objects.toString(frp.get("publicUrl"), "");
    if (!frpUrl.isBlank()) {
      return Map.of(
          "source", "FRP",
          "url", frpUrl,
          "frpPortAssignedByServer",
          "assigned_port_confirmed".equals(frp.get("status")),
          "inboundReachability", "UNVERIFIED");
    }
    if (!this.http.frpPublicUrl().isBlank()) {
      return Map.of(
          "source", "MANUAL_FRP",
          "url", this.http.frpPublicUrl(),
          "frpPortAssignedByServer", false,
          "inboundReachability", "UNVERIFIED");
    }
    if (topology.directAddressConfirmed() && listensExternally(this.http.bind())) {
      return Map.of(
          "source", "DIRECT_ADDRESS_CANDIDATE",
          "url", httpUrl(topology.externallyObservedAddress(), this.http.port()),
          "frpPortAssignedByServer", false,
          "inboundReachability", "UNVERIFIED");
    }
    return Map.of(
        "source", "LOCAL_ONLY",
        "url", localUrl(),
        "frpPortAssignedByServer", false,
        "inboundReachability", "LOCAL");
  }

  private List<String> recommendations(
      NetworkEnvironmentAssessment.Result topology,
      Map<String, Object> frp,
      Map<String, Object> certificate) {
    List<String> values = new ArrayList<>();
    if (!topology.directAddressConfirmed()) {
      values.add("No direct public address was confirmed; use a verified FRP assignment or configure routing.");
    } else {
      values.add("The public address matches a local interface, but inbound port reachability still needs an external callback probe.");
    }
    if ("no_confirmed_assignment".equals(frp.get("status"))
        || "config_written_awaiting_auto_apply".equals(frp.get("status"))) {
      values.add("FRP public URLs are published only after frpc status reports the server-assigned port.");
    }
    if (!"READY".equals(certificate.get("status"))
        && !"DISABLED".equals(certificate.get("status"))) {
      values.add("Certificate automation is blocked: " + certificate.get("reason"));
    }
    return List.copyOf(values);
  }

  private String explicitFrpUrl(NetworkAutomationConfig.Frp frp) {
    if (!frp.publicUrl().isBlank()) {
      try {
        return FrpManagedProxy.publicUrl(frp, 1);
      } catch (IllegalArgumentException error) {
        return "";
      }
    }
    return "";
  }

  private String buildFrpUrl(NetworkAutomationConfig.Frp frp, int port) {
    try {
      return FrpManagedProxy.publicUrl(frp, port);
    } catch (IllegalArgumentException error) {
      return "";
    }
  }

  private Path resolveDataFile(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("managed file path must not be blank");
    }
    Path relative = Path.of(value);
    if (relative.isAbsolute()) {
      throw new IllegalArgumentException("managed file path must be relative to the StarX data directory");
    }
    Path resolved = this.dataDirectory.resolve(relative).normalize();
    if (!resolved.startsWith(this.dataDirectory)) {
      throw new IllegalArgumentException("managed file path escapes the StarX data directory");
    }
    return resolved;
  }

  private boolean mainConfigIncludes(Path mainConfig, String configuredValue, Path managedConfig)
      throws IOException {
    return FrpManagedProxy.mainConfigIncludes(
        mainConfig,
        managedConfig,
        Files.readString(mainConfig, StandardCharsets.UTF_8));
  }

  private void writeReport(Map<String, Object> report) throws IOException {
    Path reportPath = resolveDataFile(this.config.reportFile());
    writeAtomically(reportPath, this.gson.toJson(report) + System.lineSeparator());
  }

  private static void writeAtomically(Path target, String content) throws IOException {
    Path parent = target.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Path temporary = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
    try {
      Files.writeString(temporary, content, StandardCharsets.UTF_8);
      try {
        Files.setPosixFilePermissions(temporary, Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE));
      } catch (UnsupportedOperationException ignored) {
        // Windows and some filesystems do not expose POSIX permissions.
      }
      try {
        Files.move(
            temporary,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException ignored) {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private CommandResult run(List<String> command, Duration timeout) {
    try {
      return this.commandRunner.run(List.copyOf(command), timeout);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      return new CommandResult(-1, "", true, "interrupted");
    } catch (Exception error) {
      return new CommandResult(-1, "", false, safeMessage(error));
    }
  }

  private static boolean tcpReachable(String host, int port, Duration timeout) {
    String target = Objects.requireNonNullElse(host, "").trim();
    if (target.isBlank() || "0.0.0.0".equals(target)
        || "::".equals(target) || "[::]".equals(target)) {
      target = "127.0.0.1";
    }
    if (target.startsWith("[") && target.endsWith("]")) {
      target = target.substring(1, target.length() - 1);
    }
    try (Socket socket = new Socket()) {
      socket.connect(
          new InetSocketAddress(target, port),
          Math.toIntExact(Math.max(1L, timeout.toMillis())));
      return true;
    } catch (IOException | RuntimeException unavailable) {
      return false;
    }
  }

  private static List<LocalAddressInfo> localAddresses() {
    List<LocalAddressInfo> result = new ArrayList<>();
    try {
      Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
      if (interfaces == null) {
        return List.of();
      }
      while (interfaces.hasMoreElements()) {
        NetworkInterface network = interfaces.nextElement();
        if (!network.isUp() || network.isLoopback()) {
          continue;
        }
        Enumeration<InetAddress> addresses = network.getInetAddresses();
        while (addresses.hasMoreElements()) {
          result.add(LocalAddressInfo.parse(addresses.nextElement().getHostAddress()));
        }
      }
    } catch (Exception ignored) {
      return List.copyOf(result);
    }
    return List.copyOf(result);
  }

  private static boolean listensExternally(String bind) {
    if ("0.0.0.0".equals(bind) || "::".equals(bind) || "[::]".equals(bind)) {
      return true;
    }
    return LocalAddressInfo.parse(bind).isGloballyRoutable();
  }

  private String localUrl() {
    String bind = this.http.bind();
    if ("0.0.0.0".equals(bind) || "::".equals(bind) || "[::]".equals(bind)) {
      bind = "127.0.0.1";
    }
    return "http://" + uriHost(bind) + ":" + this.http.port();
  }

  private static String httpUrl(String address, int port) {
    return "http://" + uriHost(address) + ":" + port;
  }

  private static String uriHost(String value) {
    String withoutScope = value.replaceFirst("%.*$", "");
    return withoutScope.contains(":") ? "[" + withoutScope + "]" : withoutScope;
  }

  private static String safeMessage(Throwable error) {
    String message = error.getMessage();
    return message == null || message.isBlank()
        ? error.getClass().getSimpleName()
        : redact(message);
  }

  private static String redact(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    return value
        .replaceAll("(?i)(authorization\\s*[:=]\\s*)bearer\\s+[^\\s,;]+", "$1Bearer <redacted>")
        .replaceAll(
            "(?i)((?:token|secret|password|authorization)\\s*[:=]\\s*)"
                + "(?:\\\"[^\\\"]*\\\"|\'[^\']*\'|[^\\s,;]+)",
            "$1<redacted>");
  }

  @Override
  public void close() {
    this.scheduler.shutdownNow();
  }

  @FunctionalInterface
  interface AddressDetector {
    PublicAddressConsensus.Result detect();
  }

  @FunctionalInterface
  interface PortProbe {
    boolean reachable(String host, int port, Duration timeout);
  }

  @FunctionalInterface
  interface HealthProbe {
    StarxHealthProbe.Result probe(String host, int port, Duration timeout);
  }

  private record FrpRecovery(
      boolean ready,
      String status,
      String diagnostic,
      String loadStatus,
      Map<String, Object> transaction) {
    FrpRecovery {
      status = Objects.requireNonNullElse(status, "unknown");
      diagnostic = Objects.requireNonNullElse(diagnostic, "");
      loadStatus = Objects.requireNonNullElse(loadStatus, "UNKNOWN");
      transaction = transaction == null ? Map.of() : Map.copyOf(transaction);
    }

    Map<String, Object> report() {
      LinkedHashMap<String, Object> result = new LinkedHashMap<>();
      result.put("status", this.status);
      result.put("ready", this.ready);
      if (!this.diagnostic.isBlank()) {
        result.put("diagnostic", this.diagnostic);
      }
      if (!this.transaction.isEmpty()) {
        result.put("transaction", this.transaction);
      }
      return Map.copyOf(result);
    }
  }

  @FunctionalInterface
  interface CommandRunner {
    CommandResult run(List<String> command, Duration timeout)
        throws IOException, InterruptedException;
  }

  record CommandResult(int exitCode, String output, boolean timedOut, String error) {
    CommandResult {
      output = output == null ? "" : output;
      error = error == null ? "" : error;
    }

    boolean success() {
      return !this.timedOut && this.exitCode == 0;
    }

    String summary() {
      if (this.timedOut) {
        return "timed_out";
      }
      if (!this.error.isBlank()) {
        return this.error;
      }
      String compact = redact(this.output).replaceAll("\\s+", " ").trim();
      if (compact.length() > 240) {
        compact = compact.substring(0, 240);
      }
      return "exit=" + this.exitCode + (compact.isBlank() ? "" : " output=" + compact);
    }
  }

  private static final class ProcessCommandRunner implements CommandRunner {
    @Override
    public CommandResult run(List<String> command, Duration timeout)
        throws IOException, InterruptedException {
      Process process = new ProcessBuilder(command)
          .redirectErrorStream(true)
          .start();
      StringBuilder output = new StringBuilder();
      Thread reader = Thread.ofVirtual().start(() -> {
        try (var input = process.inputReader(StandardCharsets.UTF_8)) {
          char[] buffer = new char[2048];
          int read;
          while ((read = input.read(buffer)) >= 0) {
            if (output.length() < MAX_COMMAND_OUTPUT) {
              int remaining = MAX_COMMAND_OUTPUT - output.length();
              output.append(buffer, 0, Math.min(read, remaining));
            }
          }
        } catch (IOException ignored) {
          // Exit status remains the source of truth.
        }
      });
      boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!finished) {
        process.destroy();
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
          process.destroyForcibly();
        }
      }
      reader.join(Duration.ofSeconds(2));
      return new CommandResult(
          finished ? process.exitValue() : -1,
          output.toString(),
          !finished,
          "");
    }
  }
}
