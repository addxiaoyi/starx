package io.github.addxiaoyi.starx.velocity.network;

import io.github.addxiaoyi.starx.velocity.config.NetworkAutomationConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Locates an existing frpc executable and main configuration without scanning arbitrary ports. */
final class FrpInstallationDetector {

  private FrpInstallationDetector() {
  }

  static Result detect(
      NetworkAutomationConfig.Frp config,
      Path dataDirectory) {
    return detect(config, dataDirectory, runningProcesses());
  }

  static Result detect(
      NetworkAutomationConfig.Frp config,
      Path dataDirectory,
      List<ProcessCandidate> processes) {
    Objects.requireNonNull(config, "config");
    Path root = Objects.requireNonNull(dataDirectory, "dataDirectory")
        .toAbsolutePath().normalize();
    List<ProcessCandidate> candidates = processes == null ? List.of() : List.copyOf(processes);

    if (!config.mainConfigFile().isBlank()) {
      Path configured = resolveConfigured(root, config.mainConfigFile());
      return new Result(
          config.frpcCommand(),
          configured,
          Files.isRegularFile(configured),
          Source.EXPLICIT_CONFIG);
    }

    for (ProcessCandidate process : candidates) {
      if (!isFrpc(process.command())) {
        continue;
      }
      Optional<Path> processConfig = configArgument(process.arguments(), process.workingDirectory());
      if (processConfig.isPresent() && Files.isRegularFile(processConfig.orElseThrow())) {
        return new Result(
            process.command().toString(),
            processConfig.orElseThrow(),
            true,
            Source.RUNNING_PROCESS);
      }
    }

    for (Path known : knownConfigCandidates(root)) {
      if (Files.isRegularFile(known)) {
        String command = candidates.stream()
            .filter(candidate -> isFrpc(candidate.command()))
            .map(candidate -> candidate.command().toString())
            .findFirst()
            .orElse(config.frpcCommand());
        return new Result(command, known, true, Source.KNOWN_LOCATION);
      }
    }

    return new Result(config.frpcCommand(), null, false, Source.NOT_FOUND);
  }

  private static List<ProcessCandidate> runningProcesses() {
    List<ProcessCandidate> result = new ArrayList<>();
    ProcessHandle.allProcesses().forEach(process -> {
      ProcessHandle.Info info = process.info();
      Optional<String> command = info.command();
      if (command.isEmpty()) {
        return;
      }
      String[] arguments = info.arguments().orElseGet(() -> new String[0]);
      Path workingDirectory = Path.of(System.getProperty("user.dir", "."))
          .toAbsolutePath().normalize();
      result.add(new ProcessCandidate(
          Path.of(command.orElseThrow()).toAbsolutePath().normalize(),
          List.of(arguments),
          workingDirectory));
    });
    return List.copyOf(result);
  }

  private static Optional<Path> configArgument(List<String> arguments, Path workingDirectory) {
    for (int index = 0; index < arguments.size(); index++) {
      String argument = arguments.get(index);
      String value = null;
      if ("-c".equals(argument) || "--config".equals(argument)) {
        if (index + 1 < arguments.size()) {
          value = arguments.get(index + 1);
        }
      } else if (argument.startsWith("--config=")) {
        value = argument.substring("--config=".length());
      }
      if (value != null && !value.isBlank()) {
        Path candidate = Path.of(value);
        Path base = workingDirectory == null
            ? Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize()
            : workingDirectory.toAbsolutePath().normalize();
        return Optional.of(candidate.isAbsolute()
            ? candidate.normalize()
            : base.resolve(candidate).normalize());
      }
    }
    return Optional.empty();
  }

  private static Path resolveConfigured(Path root, String value) {
    Path path = Path.of(value);
    return path.isAbsolute() ? path.normalize() : root.resolve(path).normalize();
  }

  private static List<Path> knownConfigCandidates(Path dataDirectory) {
    LinkedHashSet<Path> candidates = new LinkedHashSet<>();
    addNames(candidates, dataDirectory);
    addNames(candidates, dataDirectory.resolve("frp"));

    Path workingDirectory = Path.of(System.getProperty("user.dir", "."))
        .toAbsolutePath().normalize();
    addNames(candidates, workingDirectory);
    addNames(candidates, workingDirectory.resolve("frp"));

    if (!isWindows()) {
      addNames(candidates, Path.of("/etc/frp"));
      addNames(candidates, Path.of("/usr/local/etc/frp"));
    } else {
      String systemDrive = System.getenv("SystemDrive");
      if (systemDrive != null && !systemDrive.isBlank()) {
        addNames(candidates, Path.of(systemDrive + "\\frp"));
      }
    }
    return List.copyOf(candidates);
  }

  private static void addNames(LinkedHashSet<Path> candidates, Path directory) {
    for (String name : List.of("frpc.toml", "frpc.yaml", "frpc.yml", "frpc.ini")) {
      candidates.add(directory.resolve(name).toAbsolutePath().normalize());
    }
  }

  private static boolean isFrpc(Path command) {
    if (command == null || command.getFileName() == null) {
      return false;
    }
    String file = command.getFileName().toString().toLowerCase(Locale.ROOT);
    return "frpc".equals(file) || "frpc.exe".equals(file);
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "")
        .toLowerCase(Locale.ROOT)
        .contains("win");
  }

  record Result(
      String command,
      Path mainConfig,
      boolean configPresent,
      Source source) {
    Result {
      command = Objects.requireNonNullElse(command, "frpc");
      source = Objects.requireNonNull(source, "source");
    }
  }

  record ProcessCandidate(
      Path command,
      List<String> arguments,
      Path workingDirectory) {
    ProcessCandidate {
      command = Objects.requireNonNull(command, "command");
      arguments = arguments == null ? List.of() : List.copyOf(arguments);
    }
  }

  enum Source {
    EXPLICIT_CONFIG,
    RUNNING_PROCESS,
    KNOWN_LOCATION,
    NOT_FOUND
  }
}
