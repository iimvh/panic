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
    String sdk = read(ROOT.resolve("sdk/build.gradle.kts"));
    if (sdk.contains("project(")) problems.add("sdk must depend on z only (rule 8)");
    for (Path module : modules()) {
      String build = read(module.resolve("build.gradle.kts"));
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
      sources = s.filter(p -> p.toString().endsWith(".java") && p.toString().contains("/src/main/")).toList();
    }
    for (Path file : sources) {
      String rel = ROOT.relativize(file).toString();
      String code = read(file);
      boolean rawThreads = code.contains("new Thread(") || code.contains("Thread.ofPlatform()")
          || code.contains("Thread.ofVirtual()") || code.contains("Executors.");
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
}
