package panic.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import panic.plugin.PluginSet;
import panic.plugin.PluginSpec;

/**
 * The parts of AGENTS.md a machine can check. If this fails, fix the change, not the test: every rule here
 * keeps "everything is a plugin" true for the next agent.
 */
class ConventionsTest {

  /** Front-end modules: nothing may depend on them (rule 8). */
  private static final Set<String> FRONT_ENDS = Set.of("cli", "web");

  /**
   * Files allowed to create threads or executors outside ctx.spawn (rule 3), and why. Keep this list short.
   */
  private static final Map<String, String> RAW_THREADS_ALLOWED = Map.of(
      "plugins/cli/src/main/java/panic/cli/Stdin.java", "System.in cannot be interrupted; one daemon reader per stream",
      "plugins/web/src/main/java/panic/web/WebPlugin.java", "HttpServer needs an executor; it is shut down in the server effect");

  /** Files that keep process-wide mutable state (rule 2), and why. Keep this list shorter than it is. */
  private static final Map<String, String> STATIC_STATE_ALLOWED = Map.of(
      "plugins/cli/src/main/java/panic/cli/Stdin.java",
      "one reader thread per stream: a plugin restart must not add a second reader of System.in");

  private static final Pattern STATIC_MUTABLE = Pattern.compile(
      "(?m)^\\s*(?:(?:private|public|protected)\\s+)?static\\s+(?!final\\b)(?!class\\b|interface\\b|record\\b|enum\\b)[^=;(]+[=;]"
          + "|^\\s*(?:(?:private|public|protected)\\s+)?static\\s+final\\s+[\\w.]*(?:Map|List|Set|Queue|Deque|Collection|Atomic\\w*)(?:<[^=;]*>)?\\s+\\w+\\s*=\\s*new\\b");

  private static final Pattern IMPORT = Pattern.compile("(?m)^import\\s+(?:static\\s+)?(panic\\.[\\w.]+?)(?:\\.\\*|\\.[A-Z]\\w*(?:\\.\\w+)*);");

  private static final Pattern PACKAGE = Pattern.compile("(?m)^package\\s+([\\w.]+);");

  private static final Pattern REQUIRES = Pattern.compile("requires\\(\\)\\s*\\{\\s*return\\s+([^;]*);");

  private static final Path ROOT = findRoot();

  private static Path findRoot() {
    Path p = Path.of("").toAbsolutePath();
    while (p != null && !Files.isRegularFile(p.resolve("settings.gradle.kts"))) p = p.getParent();
    if (p == null) throw new IllegalStateException("repository root not found");
    return p;
  }

  private static List<Path> modules() throws IOException {
    try (Stream<Path> s = Files.list(ROOT.resolve("plugins"))) {
      return s.filter(Files::isDirectory).filter(d -> Files.isRegularFile(d.resolve("build.gradle.kts"))).sorted().toList();
    }
  }

  /** The path with {@code /} separators, so the checks give the same answer on Windows. */
  private static String unix(Path p) {
    return p.toString().replace('\\', '/');
  }

  /** A build file without its comment lines, so a commented-out dependency counts for nothing. */
  private static String build(Path file) {
    return read(file).lines().filter(l -> !l.strip().startsWith("//")).collect(java.util.stream.Collectors.joining("\n"));
  }

  private static String read(Path p) {
    try {
      return Files.readString(p);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void everyPluginModuleDeclaresItsPluginsAndDocumentsThem() throws Exception {
    List<String> problems = new ArrayList<>();
    Set<String> names = new HashSet<>();
    for (Path module : modules()) {
      String id = ROOT.relativize(module).toString();
      Path services = module.resolve("src/main/resources/META-INF/services/panic.plugin.PluginSet");
      Path readme = module.resolve("README.md");
      if (!Files.isRegularFile(readme)) problems.add(id + ": missing README.md (rule 9)");
      if (!Files.isRegularFile(services)) {
        problems.add(id + ": missing META-INF/services/panic.plugin.PluginSet");
        continue;
      }
      for (String line : read(services).lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).toList()) {
        Class<?> type;
        try {
          type = Class.forName(line);
        } catch (ClassNotFoundException e) {
          problems.add(id + ": PluginSet class not found: " + line);
          continue;
        }
        if (!PluginSet.class.isAssignableFrom(type)) {
          problems.add(id + ": " + line + " does not implement PluginSet");
          continue;
        }
        for (PluginSpec<?> spec : ((PluginSet) type.getDeclaredConstructor().newInstance()).plugins()) {
          if (!names.add(spec.name())) problems.add(id + ": plugin name '" + spec.name() + "' is used twice");
          if (Files.isRegularFile(readme) && !read(readme).contains("`" + spec.name() + "`")) {
            problems.add(id + ": README.md does not document plugin `" + spec.name() + "` (rule 9)");
          }
        }
      }
    }
    if (!problems.isEmpty()) fail(String.join("\n", problems));
    assertEquals(names, new HashSet<>(Panic.discover().stream().map(PluginSpec::name).toList()),
        "every declared plugin is discovered at runtime");
  }

  @Test
  void dependenciesPointInwardOnly() throws Exception {
    List<String> problems = new ArrayList<>();
    String sdk = build(ROOT.resolve("sdk/build.gradle.kts"));
    if (sdk.contains("project(")) problems.add("sdk must not depend on a project module (rule 8)");
    for (Path module : modules()) {
      String build = build(module.resolve("build.gradle.kts"));
      String id = module.getFileName().toString();
      if (build.contains("project(\":app\")")) problems.add(id + " depends on :app (rule 8)");
      for (String fe : FRONT_ENDS) {
        if (!fe.equals(id) && build.contains("project(\":" + fe + "\")")) {
          problems.add(id + " depends on front-end :" + fe + " (rule 8)");
        }
      }
    }
    if (!problems.isEmpty()) fail(String.join("\n", problems));
  }

  @Test
  void pluginsOwnTheirThreadsAndReadNoEnvironment() throws Exception {
    List<String> problems = new ArrayList<>();
    List<Path> sources;
    try (Stream<Path> s = Files.walk(ROOT.resolve("plugins"))) {
      sources = s.filter(p -> p.toString().endsWith(".java") && unix(p).contains("/src/main/")).toList();
    }
    for (Path file : sources) {
      String rel = unix(ROOT.relativize(file));
      String code = read(file);
      boolean rawThreads = code.contains("new Thread(") || code.contains("Thread.ofPlatform()")
          || code.contains("Thread.ofVirtual()") || code.contains("Executors.") || code.contains("Thread.startVirtualThread")
          || code.contains("new Timer(") || code.contains("CompletableFuture.runAsync")
          || code.contains("CompletableFuture.supplyAsync");
      if (rawThreads && !RAW_THREADS_ALLOWED.containsKey(rel)) {
        problems.add(rel + ": creates threads outside ctx.spawn (rule 3)");
      }
      if (code.contains("System.getenv") || code.contains("System.getProperty(\"panic")) {
        problems.add(rel + ": reads settings from the environment instead of its config (rule 4)");
      }
    }
    if (!problems.isEmpty()) fail(String.join("\n", problems));
    assertTrue(sources.size() > 10, "plugin sources were found");
  }

  /** Main sources of every module, plugins and sdk. */
  private static List<Path> mainSources(Path module) throws IOException {
    Path main = module.resolve("src/main/java");
    if (!Files.isDirectory(main)) return List.of();
    try (Stream<Path> s = Files.walk(main)) {
      return s.filter(p -> p.toString().endsWith(".java")).toList();
    }
  }

  @Test
  void frontEndsRequireNothingButTheApp() throws Exception {
    List<String> problems = new ArrayList<>();
    for (String fe : FRONT_ENDS) {
      for (Path file : mainSources(ROOT.resolve("plugins").resolve(fe))) {
        var m = REQUIRES.matcher(read(file));
        while (m.find()) {
          String keys = m.group(1).replaceAll("\\s+", "");
          if (!keys.equals("List.of()") && !keys.equals("List.of(App.KEY)")) {
            problems.add(unix(ROOT.relativize(file)) + ": requires " + keys
                + "; a front-end only runs commands and asks hooks (rule 6)");
          }
        }
      }
    }
    if (!problems.isEmpty()) fail(String.join("\n", problems));
  }

  @Test
  void everyImportedModuleIsADeclaredDependency() throws Exception {
    // package -> module, from the sources themselves
    Map<String, String> owner = new java.util.HashMap<>();
    List<Path> all = new ArrayList<>(modules());
    all.add(ROOT.resolve("sdk"));
    for (Path module : all) {
      String id = module.getFileName().toString();
      for (Path file : mainSources(module)) {
        var m = PACKAGE.matcher(read(file));
        if (m.find()) owner.put(m.group(1), id);
      }
    }
    List<String> problems = new ArrayList<>();
    for (Path module : modules()) {
      String id = module.getFileName().toString();
      String build = build(module.resolve("build.gradle.kts"));
      Set<String> reported = new HashSet<>();
      for (Path file : mainSources(module)) {
        var m = IMPORT.matcher(read(file));
        while (m.find()) {
          String dep = owner.get(m.group(1));
          if (dep == null || dep.equals(id) || dep.equals("sdk")) continue;
          if (!build.contains("project(\":" + dep + "\")") && reported.add(dep)) {
            problems.add(id + " imports " + m.group(1) + " (module :" + dep + ") but does not declare it in build.gradle.kts");
          }
        }
      }
    }
    if (!problems.isEmpty()) fail(String.join("\n", problems));
  }

  @Test
  void pluginsKeepNoProcessWideMutableState() throws Exception {
    List<String> problems = new ArrayList<>();
    for (Path module : modules()) {
      for (Path file : mainSources(module)) {
        String rel = unix(ROOT.relativize(file));
        if (STATIC_STATE_ALLOWED.containsKey(rel)) continue;
        if (STATIC_MUTABLE.matcher(read(file)).find()) {
          problems.add(rel + ": static mutable state (rule 2); keep it in the plugin's Context instead");
        }
      }
    }
    if (!problems.isEmpty()) fail(String.join("\n", problems));
  }

  /** Repo paths named in the agent docs (in backticks or link targets) must exist, so the docs cannot point at ghosts. */
  @Test
  void agentDocsOnlyNameExistingPaths() {
    Pattern token = Pattern.compile("`([^`]+)`|\\]\\(([^)]+)\\)");
    Pattern repoPath = Pattern.compile("(?:plugins|sdk|app|ui|z|docs|\\.github)/[\\w./-]*");
    List<String> problems = new ArrayList<>();
    for (String doc : List.of("AGENTS.md", "CLAUDE.md")) {
      Path file = ROOT.resolve(doc);
      if (!Files.isRegularFile(file)) continue;
      // fenced blocks first: their backticks would pair up wrongly with the inline ones
      var m = token.matcher(read(file).replaceAll("(?s)```.*?```", ""));
      while (m.find()) {
        String t = m.group(1) != null ? m.group(1) : m.group(2);
        if (!repoPath.matcher(t).matches()) continue; // commands, placeholders (<module>) and prose are skipped
        if (!Files.exists(ROOT.resolve(t))) problems.add(doc + " names " + t + ", which does not exist");
      }
    }
    if (!problems.isEmpty()) fail(String.join("\n", problems));
  }
}
