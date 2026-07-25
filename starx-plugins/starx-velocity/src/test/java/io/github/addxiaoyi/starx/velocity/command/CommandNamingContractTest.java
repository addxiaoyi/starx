package io.github.addxiaoyi.starx.velocity.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class CommandNamingContractTest {
  private static final Pattern COMMAND = Pattern.compile("metaBuilder\\(\\\"([^\\\"]+)\\\"\\)");

  @Test
  void commandsUseShortStarxNamesWithoutGenericAliasesOrDuplicateRoots() throws Exception {
    Path root = Path.of("src/main/java/io/github/addxiaoyi/starx/velocity");
    List<String> names = new ArrayList<>();
    try (var files = Files.walk(root)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
        String source = Files.readString(file);
        Matcher matcher = COMMAND.matcher(source);
        while (matcher.find()) names.add(matcher.group(1));
        assertFalse(source.contains(".aliases("), () -> "Generic command alias in " + file);
      }
    }

    assertEquals(1, names.stream().filter("starx"::equals).count());
    assertTrue(names.stream().allMatch(name -> name.equals("starx") || name.startsWith("sx")),
        () -> "Non-StarX command names: " + names);
    assertTrue(names.stream().allMatch(name -> name.length() <= 10),
        () -> "Command names must stay short: " + names);
    assertEquals(List.of("sxvote"), names.stream().filter(name -> name.startsWith("sxvote")).toList(),
        () -> "Voting must use one command with simple subcommands: " + names);
    assertEquals(List.of("sxadmin"), names.stream().filter(name -> name.startsWith("sxreport")
        || name.startsWith("sxhistory") || name.startsWith("sxnote") || name.startsWith("sxnotes")
        || name.startsWith("sxannounce") || name.startsWith("sxbind") || name.equals("sxadmin")).toList(),
        () -> "Admin actions must use one command with simple subcommands: " + names);
    assertEquals(List.of("sxnet"), names.stream().filter(name -> name.startsWith("sxnetwork")
        || name.equals("sxfind") || name.equals("sxsend") || name.equals("sxalert")
        || name.equals("sxping") || name.equals("sxdrain") || name.equals("sxnet")).toList(),
        () -> "Network actions must use one command with simple subcommands: " + names);
    assertEquals(names.size(), new HashSet<>(names).size(), () -> "Duplicate command names: " + names);
  }

  @Test
  void commandModulesUnregisterOnDisable() throws Exception {
    Path root = Path.of("src/main/java/io/github/addxiaoyi/starx/velocity/module");
    List<String> missing = new ArrayList<>();
    try (var files = Files.walk(root)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
        String source = Files.readString(file);
        if (source.contains("metaBuilder(\"") && source.matches("(?s).*public void onDisable\\(\\) \\{\\s*\\}.*")) {
          missing.add(file.toString());
        }
      }
    }
    assertTrue(missing.isEmpty(), () -> "Command modules must unregister on disable: " + missing);
  }

  @Test
  void currentDocumentationUsesRegisteredCommands() throws Exception {
    List<Path> docs = List.of(
        Path.of("../../README.md"),
        Path.of("../../docs/UWORLD_CONFIGURATION.md"));
    for (Path doc : docs) {
      if (!Files.exists(doc)) continue;
      String text = Files.readString(doc);
      assertFalse(text.contains("/starxaccount"), () -> "Removed command in " + doc);
      for (String legacy : List.of("`/tutorial", "`/uworld", "`/hub", "`/lobby")) {
        assertFalse(text.contains(legacy), () -> "Legacy command in " + doc + ": " + legacy);
      }
    }
  }

  @Test
  void queueSchedulersKeepCancellationHandles() throws Exception {
    for (String module : List.of(
        "src/main/java/io/github/addxiaoyi/starx/velocity/module/proxytools/QueueModule.java",
        "src/main/java/io/github/addxiaoyi/starx/velocity/module/proxytools/SmartQueueModule.java")) {
      String source = Files.readString(Path.of(module));
      assertTrue(source.contains("ScheduledTask"), () -> "Missing scheduler handle in " + module);
      assertTrue(source.contains("cancel()"), () -> "Queue scheduler is not cancelled in " + module);
    }
  }
}
