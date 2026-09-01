package io.github.addxiaoyi.starx.velocity.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

public final class ConfigLayout {

  private static final LoaderOptions SECURE_LOADER_OPTIONS = new LoaderOptions() {{
      // 限制别名数量防止 YAML BOMB 木马攻击和深度放大
      setMaxAliasesForCollections(100);
    }};

  static final String DEFAULT_INDEX_RESOURCE = "/default-config.yml";
  static final String CONFIG_FILES_KEY = "config-files";
  static final String DEFAULT_DIRECTORY = "config";
  static final List<String> DEFAULT_FILES = List.of(
      "core.yml",
      "auth.yml",
      "network.yml",
      "modules.yml",
      "uworld.yml",
      "update.yml");

  private static final Pattern YAML_KEY =
      Pattern.compile("^(\\s*)([^#][^:]*):(?:\\s*(.*))?$");
  private static final Map<String, String> DEFAULT_OWNERS = Map.ofEntries(
      Map.entry("auto-config", "core.yml"),
      Map.entry("compatibility", "core.yml"),
      Map.entry("api-key", "core.yml"),
      Map.entry("http", "core.yml"),
      Map.entry("webhook", "core.yml"),
      Map.entry("database", "core.yml"),
      Map.entry("auth", "auth.yml"),
      Map.entry("uniauth", "auth.yml"),
      Map.entry("totp", "auth.yml"),
      Map.entry("website-sync", "network.yml"),
      Map.entry("network-automation", "network.yml"),
      Map.entry("napcat", "network.yml"),
      Map.entry("modules", "modules.yml"),
      Map.entry("player-list", "modules.yml"),
      Map.entry("uworld", "uworld.yml"),
      Map.entry("update", "update.yml"));

  private ConfigLayout() {
  }

  static void ensure(Path entrypoint) throws IOException {
    Objects.requireNonNull(entrypoint, "entrypoint");
    if (Files.notExists(entrypoint)) {
      copyResource(DEFAULT_INDEX_RESOURCE, entrypoint);
    }

    Map<String, Object> root = readFile(entrypoint);
    if (!isSplit(root)) {
      return;
    }
    Spec spec = parseSpec(root, entrypoint);
    for (String name : spec.files()) {
      Path fragment = spec.directory().resolve(name).normalize();
      if (Files.notExists(fragment)) {
        copyResource("/config/" + name, fragment);
      }
    }
  }

  static Loaded load(Path entrypoint, Consumer<String> warningSink) throws IOException {
    Objects.requireNonNull(entrypoint, "entrypoint");
    Objects.requireNonNull(warningSink, "warningSink");
    Map<String, Object> raw = readFile(entrypoint);
    if (!isSplit(raw)) {
      return new Loaded(raw, false, null);
    }

    Spec spec = parseSpec(raw, entrypoint);
    LinkedHashMap<String, Object> current = new LinkedHashMap<>();
    raw.forEach((key, value) -> {
      if (!CONFIG_FILES_KEY.equals(key)) {
        current.put(key, deepCopy(value));
      }
    });
    for (String name : spec.files()) {
      Path fragment = spec.directory().resolve(name).normalize();
      if (!Files.isRegularFile(fragment)) {
        throw new IOException("Missing StarX configuration fragment: " + fragment);
      }
      mergeInto(current, readFile(fragment));
    }
    warningSink.accept("StarX configuration fragments loaded from " + spec.directory());
    return new Loaded(current, true, spec);
  }

  static Completion completeMissingDefaults(
      Path entrypoint,
      Map<String, Object> defaults,
      Consumer<String> warningSink
  ) throws IOException {
    Objects.requireNonNull(entrypoint, "entrypoint");
    Objects.requireNonNull(defaults, "defaults");
    Objects.requireNonNull(warningSink, "warningSink");
    Map<String, Object> index = readFile(entrypoint);
    if (!isSplit(index)) {
      return Completion.none();
    }

    Spec spec = parseSpec(index, entrypoint);
    List<String> addedPaths = new ArrayList<>();
    List<Path> backups = new ArrayList<>();
    Object defaultSchema = defaults.get("schema-version");
    if (defaultSchema != null && !Objects.equals(index.get("schema-version"), defaultSchema)) {
      Path backup = uniqueSibling(entrypoint, entrypoint.getFileName() + ".pre-migration");
      Files.copy(entrypoint, backup, StandardCopyOption.COPY_ATTRIBUTES);
      index.put("schema-version", deepCopy(defaultSchema));
      writeAtomically(entrypoint, dump(index));
      backups.add(backup);
      addedPaths.add("schema-version");
    }
    for (String name : spec.files()) {
      Path fragment = spec.directory().resolve(name).normalize();
      Map<String, Object> current = readFile(fragment);
      Map<String, Object> fragmentDefaults = defaultsFor(name, spec, defaults);
      Map<String, Object> completed = mergeMissing(fragmentDefaults, current, "", addedPaths);
      if (!completed.equals(current)) {
        Path backup = uniqueSibling(fragment, fragment.getFileName() + ".pre-migration");
        Files.copy(fragment, backup, StandardCopyOption.COPY_ATTRIBUTES);
        writeAtomically(fragment, dump(completed));
        backups.add(backup);
      }
    }
    if (addedPaths.isEmpty()) {
      return Completion.none();
    }
    warningSink.accept(
        "StarX split configuration completed missing defaults: " + String.join(", ", addedPaths)
            + "; backups=" + backups.stream().map(path -> path.getFileName().toString())
                .collect(java.util.stream.Collectors.joining(",")));
    return new Completion(List.copyOf(addedPaths), List.copyOf(backups));
  }

  static Map<String, Object> readDefaultRoot() throws IOException {
    Map<String, Object> index = readResource(DEFAULT_INDEX_RESOURCE);
    if (!isSplit(index)) {
      return index;
    }

    LinkedHashMap<String, Object> defaults = new LinkedHashMap<>();
    if (index.containsKey("schema-version")) {
      defaults.put("schema-version", deepCopy(index.get("schema-version")));
    }
    for (String name : parseResourceFiles(index)) {
      try (InputStream input = ConfigLayout.class.getResourceAsStream("/config/" + name)) {
        if (input == null) {
          throw new IOException("Missing classpath StarX configuration fragment: " + name);
        }
        mergeInto(defaults, readRoot(input, "/config/" + name));
      }
    }
    return defaults;
  }

  public static Map<String, Object> readEffectiveRoot(Path entrypoint) throws IOException {
    ensure(entrypoint);
    Loaded loaded = load(entrypoint, ignored -> { });
    if (!loaded.split()) {
      return loaded.root();
    }
    return merge(readDefaultRoot(), loaded.root());
  }

  static void migrateLegacy(
      Path entrypoint,
      Map<String, Object> root,
      Consumer<String> warningSink
  ) throws IOException {
    Objects.requireNonNull(entrypoint, "entrypoint");
    Objects.requireNonNull(root, "root");
    Objects.requireNonNull(warningSink, "warningSink");
    if (isSplit(readFile(entrypoint))) {
      return;
    }

    Spec spec = defaultSpec(entrypoint);
    Map<String, Map<String, Object>> fragments = new LinkedHashMap<>();
    for (String name : spec.files()) {
      fragments.put(name, new LinkedHashMap<>());
    }
    for (Map.Entry<String, Object> entry : root.entrySet()) {
      if ("schema-version".equals(entry.getKey()) || CONFIG_FILES_KEY.equals(entry.getKey())) {
        continue;
      }
      String owner = DEFAULT_OWNERS.getOrDefault(entry.getKey(), "core.yml");
      if (!fragments.containsKey(owner)) {
        owner = spec.files().getFirst();
      }
      fragments.get(owner).put(entry.getKey(), deepCopy(entry.getValue()));
    }

    Path backup = uniqueSibling(entrypoint, "config.yml.split-backup");
    Files.copy(entrypoint, backup, StandardCopyOption.COPY_ATTRIBUTES);
    for (Map.Entry<String, Map<String, Object>> fragment : fragments.entrySet()) {
      writeAtomically(
          spec.directory().resolve(fragment.getKey()),
          dump(fragment.getValue()));
    }
    writeAtomically(entrypoint, dump(indexRoot(spec, root.get("schema-version"))));
    warningSink.accept(
        "StarX monolithic configuration migrated to " + spec.directory()
            + "; backup=" + backup.getFileName());
  }

  static TextEditor openEditor(Path entrypoint) throws IOException {
    Map<String, Object> root = readFile(entrypoint);
    return new TextEditor(entrypoint, isSplit(root) ? parseSpec(root, entrypoint) : null);
  }

  static boolean isSplit(Map<String, Object> root) {
    return root.get(CONFIG_FILES_KEY) instanceof Map<?, ?>;
  }

  static Map<String, Object> merge(
      Map<String, Object> defaults,
      Map<String, Object> overrides
  ) {
    LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
    defaults.forEach((key, value) -> merged.put(key, deepCopy(value)));
    mergeInto(merged, overrides);
    return merged;
  }

  private static Spec parseSpec(Map<String, Object> root, Path entrypoint) {
    Object rawNode = root.get(CONFIG_FILES_KEY);
    if (!(rawNode instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("config-files must be a mapping");
    }
    Map<String, Object> node = stringMap(map, "config-files");
    String directoryName = stringValue(node, "directory", DEFAULT_DIRECTORY);
    if (directoryName.isBlank()) {
      throw new IllegalArgumentException("config-files.directory must not be blank");
    }
    Path parent = entrypoint.toAbsolutePath().normalize().getParent();
    if (parent == null) {
      throw new IllegalArgumentException("config-files requires an entrypoint parent");
    }
    Path directory = parent.resolve(directoryName).normalize();
    if (!directory.startsWith(parent)) {
      throw new IllegalArgumentException("config-files.directory escapes the plugin directory");
    }
    List<String> files = stringList(node.get("files"), "config-files.files");
    if (files.isEmpty()) {
      throw new IllegalArgumentException("config-files.files must not be empty");
    }
    validateFiles(files, "config-files.files");
    return new Spec(directory, List.copyOf(files));
  }

  private static List<String> parseResourceFiles(Map<String, Object> root) {
    Object rawNode = root.get(CONFIG_FILES_KEY);
    if (!(rawNode instanceof Map<?, ?> map)) {
      return DEFAULT_FILES;
    }
    Map<String, Object> node = stringMap(map, "config-files");
    List<String> files = stringList(node.get("files"), "config-files.files");
    validateFiles(files, "config-files.files");
    return files;
  }

  private static Spec defaultSpec(Path entrypoint) {
    Path parent = entrypoint.toAbsolutePath().normalize().getParent();
    if (parent == null) {
      throw new IllegalArgumentException("Configuration path has no parent: " + entrypoint);
    }
    return new Spec(parent.resolve(DEFAULT_DIRECTORY), DEFAULT_FILES);
  }

  private static Map<String, Object> indexRoot(Spec spec, Object schemaVersion) {
    LinkedHashMap<String, Object> index = new LinkedHashMap<>();
    index.put("schema-version", schemaVersion == null ? 5 : deepCopy(schemaVersion));
    LinkedHashMap<String, Object> files = new LinkedHashMap<>();
    Path parent = spec.directory().getParent();
    files.put("directory", parent == null
        ? DEFAULT_DIRECTORY
        : parent.relativize(spec.directory()).toString().replace('\\', '/'));
    files.put("files", spec.files());
    index.put(CONFIG_FILES_KEY, files);
    return index;
  }

  private static Map<String, Object> defaultsFor(
      String fragmentName,
      Spec spec,
      Map<String, Object> defaults
  ) {
    LinkedHashMap<String, Object> fragmentDefaults = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : defaults.entrySet()) {
      if ("schema-version".equals(entry.getKey())) {
        continue;
      }
      String owner = DEFAULT_OWNERS.getOrDefault(entry.getKey(), spec.files().getFirst());
      if (!spec.files().contains(owner)) {
        owner = spec.files().getFirst();
      }
      if (fragmentName.equals(owner)) {
        fragmentDefaults.put(entry.getKey(), deepCopy(entry.getValue()));
      }
    }
    return fragmentDefaults;
  }

  private static Map<String, Object> mergeMissing(
      Map<String, Object> defaults,
      Map<String, Object> current,
      String prefix,
      List<String> addedPaths
  ) {
    LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
    current.forEach((key, value) -> merged.put(key, deepCopy(value)));
    for (Map.Entry<String, Object> entry : defaults.entrySet()) {
      String key = entry.getKey();
      String path = prefix.isEmpty() ? key : prefix + "." + key;
      Object currentValue = current.get(key);
      if (!current.containsKey(key)) {
        merged.put(key, deepCopy(entry.getValue()));
        addedPaths.add(path);
      } else if (entry.getValue() instanceof Map<?, ?> defaultMap
          && currentValue instanceof Map<?, ?> currentMap) {
        merged.put(key, mergeMissing(
            stringMap(defaultMap, path), stringMap(currentMap, path), path, addedPaths));
      }
    }
    return merged;
  }

  private static void validateFiles(List<String> files, String label) {
    Set<String> seen = new HashSet<>();
    for (String name : files) {
      if (name.isBlank()
          || !seen.add(name)
          || !name.matches("[A-Za-z0-9_.-]+\\.(?:yml|yaml)")) {
        throw new IllegalArgumentException(label + " contains an invalid or duplicate file: " + name);
      }
      Path path = Path.of(name);
      if (path.isAbsolute() || path.getNameCount() != 1) {
        throw new IllegalArgumentException(label + " must contain relative file names only: " + name);
      }
    }
  }

  private static List<String> stringList(Object value, String label) {
    if (value == null) {
      return DEFAULT_FILES;
    }
    if (!(value instanceof List<?> list)) {
      throw new IllegalArgumentException(label + " must be a list");
    }
    List<String> values = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof String string)) {
        throw new IllegalArgumentException(label + " must contain strings");
      }
      values.add(string.trim());
    }
    return values;
  }

  private static String stringValue(Map<String, Object> node, String key, String fallback) {
    Object value = node.get(key);
    return value == null ? fallback : String.valueOf(value).trim();
  }

  private static Map<String, Object> readFile(Path path) throws IOException {
    if (!Files.isRegularFile(path)) {
      throw new IOException("Missing StarX configuration file: " + path);
    }
    try (InputStream input = Files.newInputStream(path)) {
      return readRoot(input, path.toString());
    }
  }

  private static Map<String, Object> readResource(String resource) throws IOException {
    try (InputStream input = ConfigLayout.class.getResourceAsStream(resource)) {
      if (input == null) {
        throw new IOException("Missing classpath StarX configuration resource: " + resource);
      }
      return readRoot(input, resource);
    }
  }

  private static Map<String, Object> readRoot(InputStream input, String label) throws IOException {
    Object loaded = new Yaml(SECURE_LOADER_OPTIONS).load(input);
    if (loaded == null) {
      return new LinkedHashMap<>();
    }
    if (!(loaded instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("Configuration root must be a mapping: " + label);
    }
    return stringMap(map, label);
  }

  private static Map<String, Object> stringMap(Map<?, ?> source, String label) {
    LinkedHashMap<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : source.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw new IllegalArgumentException(label + " contains a non-string key");
      }
      result.put(key, deepCopy(entry.getValue()));
    }
    return result;
  }

  private static void mergeInto(Map<String, Object> target, Map<String, Object> overrides) {
    for (Map.Entry<String, Object> entry : overrides.entrySet()) {
      Object current = target.get(entry.getKey());
      Object override = entry.getValue();
      if (current instanceof Map<?, ?> currentMap && override instanceof Map<?, ?> overrideMap) {
        Map<String, Object> nested = stringMap(currentMap, entry.getKey());
        mergeInto(nested, stringMap(overrideMap, entry.getKey()));
        target.put(entry.getKey(), nested);
      } else {
        target.put(entry.getKey(), deepCopy(override));
      }
    }
  }

  private static Object deepCopy(Object value) {
    if (value instanceof Map<?, ?> map) {
      return stringMap(map, "configuration value");
    }
    if (value instanceof List<?> list) {
      return list.stream().map(ConfigLayout::deepCopy).toList();
    }
    return value;
  }

  private static void copyResource(String resource, Path target) throws IOException {
    Files.createDirectories(target.toAbsolutePath().normalize().getParent());
    try (InputStream input = ConfigLayout.class.getResourceAsStream(resource)) {
      if (input == null) {
        writeAtomically(target, "# StarX configuration fragment\n");
        return;
      }
      writeAtomically(target, new String(input.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  private static String dump(Map<String, Object> root) {
    DumperOptions options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    options.setPrettyFlow(true);
    options.setIndent(2);
    options.setIndicatorIndent(0);
    options.setSplitLines(false);
    return new Yaml(options).dump(root);
  }

  private static void writeAtomically(Path target, String content) throws IOException {
    Path absolute = target.toAbsolutePath().normalize();
    Path parent = absolute.getParent();
    if (parent == null) {
      throw new IOException("Configuration path has no parent: " + target);
    }
    Files.createDirectories(parent);
    Path temporary = Files.createTempFile(parent, absolute.getFileName().toString(), ".tmp");
    boolean moved = false;
    try {
      Files.writeString(
          temporary,
          content,
          StandardCharsets.UTF_8,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE);
      try {
        Files.move(
            temporary,
            absolute,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException ignored) {
        Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
      }
      moved = true;
    } finally {
      if (!moved) {
        Files.deleteIfExists(temporary);
      }
    }
  }

  private static Path uniqueSibling(Path path, String baseName) {
    Path parent = path.toAbsolutePath().normalize().getParent();
    if (parent == null) {
      throw new IllegalArgumentException("Configuration path has no parent: " + path);
    }
    Path candidate = parent.resolve(baseName);
    int suffix = 1;
    while (Files.exists(candidate)) {
      candidate = parent.resolve(baseName + "." + suffix++);
    }
    return candidate;
  }

  record Loaded(Map<String, Object> root, boolean split, Spec spec) {
  }

  record Completion(List<String> addedPaths, List<Path> backups) {
    private static Completion none() {
      return new Completion(List.of(), List.of());
    }
  }

  record Spec(Path directory, List<String> files) {
  }

  static final class TextEditor {
    private final Path entrypoint;
    private final Spec spec;
    private final Map<Path, YamlTextEditor> editors = new HashMap<>();

    private TextEditor(Path entrypoint, Spec spec) {
      this.entrypoint = entrypoint;
      this.spec = spec;
    }

    boolean setScalar(List<String> expectedPath, String renderedValue) throws IOException {
      if (expectedPath == null || expectedPath.isEmpty()) {
        throw new IllegalArgumentException("Configuration edit path must not be empty");
      }
      Path target = this.spec == null
          ? this.entrypoint
          : owner(expectedPath.getFirst());
      YamlTextEditor editor = this.editors.get(target);
      if (editor == null) {
        editor = new YamlTextEditor(Files.readString(target, StandardCharsets.UTF_8));
        this.editors.put(target, editor);
      }
      return editor.setScalar(expectedPath, renderedValue);
    }

    void writeAtomically() throws IOException {
      for (Map.Entry<Path, YamlTextEditor> entry : this.editors.entrySet()) {
        ConfigLayout.writeAtomically(entry.getKey(), entry.getValue().source());
      }
    }

    private Path owner(String topLevelKey) throws IOException {
      for (String name : this.spec.files()) {
        Path candidate = this.spec.directory().resolve(name).normalize();
        if (readFile(candidate).containsKey(topLevelKey)) {
          return candidate;
        }
      }
      String preferred = DEFAULT_OWNERS.get(topLevelKey);
      if (preferred != null && this.spec.files().contains(preferred)) {
        return this.spec.directory().resolve(preferred).normalize();
      }
      throw new IllegalArgumentException(
          "No StarX configuration fragment owns top-level key: " + topLevelKey);
    }
  }

  private static final class YamlTextEditor {
    private final List<String> lines;
    private final String newline;

    private YamlTextEditor(String source) {
      this.newline = source.contains("\r\n") ? "\r\n" : "\n";
      this.lines = new ArrayList<>(List.of(source.split("\\R", -1)));
    }

    private boolean setScalar(List<String> expectedPath, String renderedValue) {
      List<String> stack = new ArrayList<>();
      for (int index = 0; index < this.lines.size(); index++) {
        String line = this.lines.get(index);
        Matcher matcher = YAML_KEY.matcher(line);
        if (!matcher.matches()) {
          continue;
        }
        int indent = matcher.group(1).length();
        if (indent % 2 != 0) {
          continue;
        }
        int depth = indent / 2;
        while (stack.size() > depth) {
          stack.removeLast();
        }
        String key = matcher.group(2).trim();
        if (stack.size() != depth) {
          continue;
        }
        stack.add(key);
        if (!stack.equals(expectedPath)) {
          continue;
        }
        String replacement = " ".repeat(indent) + key + ": " + renderedValue;
        if (replacement.equals(line)) {
          return false;
        }
        this.lines.set(index, replacement);
        return true;
      }
      return false;
    }

    private String source() {
      return String.join(this.newline, this.lines);
    }
  }
}
