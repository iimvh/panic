package panic.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import context.ConfigHandle;
import context.Context;
import context.Root;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import panic.chat.Chat;
import panic.command.Commands;
import panic.command.Session;
import panic.tools.Workspace;
import panic.util.CancelToken;

class InstructionsPluginTest {

  @TempDir
  Path tmp;

  private final Root root = Context.root("t");
  private final List<String> warnings = new CopyOnWriteArrayList<>();
  /** conversation id → project folder, as plugin workspace answers. */
  private final Map<String, Path> projects = new ConcurrentHashMap<>();
  private Path repo;
  private Path project;
  private Path global;

  @BeforeEach
  void setUp() throws Exception {
    repo = Files.createDirectories(tmp.resolve("repo"));
    Files.createDirectories(repo.resolve(".git"));
    project = Files.createDirectories(repo.resolve("apps/web"));
    global = tmp.resolve("global/AGENTS.md");
    Files.createDirectories(global.getParent());
    root.plugin("workspace", ctx -> {
      ctx.hook(Workspace.ROOT, q -> projects.get(q.conversationId()));
      return null;
    });
  }

  @AfterEach
  void dispose() {
    root.dispose();
  }

  private ConfigHandle<InstructionsConfig> start(int maxChars) {
    return root.plugin("instructions", new InstructionsPlugin(warnings::add),
        new InstructionsConfig(null, global.toString(), null, tmp.resolve("none").toString(), maxChars));
  }

  private String section(Chat.ContextQuery q) {
    return root.collect(Chat.CONTEXT, q).stream().filter(s -> s.name().equals(InstructionsPlugin.SECTION))
        .map(Chat.Section::text).findFirst().orElse(null);
  }

  private String section(String conversation) {
    return section(new Chat.ContextQuery(conversation));
  }

  @Test
  void globalThenParentsThenTheProjectStoppingAtTheGitRoot() throws Exception {
    Files.writeString(tmp.resolve("AGENTS.md"), "above the repo");
    Files.writeString(global, "global rules");
    Files.writeString(repo.resolve("AGENTS.md"), "repo rules");
    Files.writeString(repo.resolve("apps/CLAUDE.md"), "apps rules");
    Files.writeString(project.resolve("AGENTS.md"), "web rules");
    Files.writeString(project.resolve("CLAUDE.md"), "claude web rules");
    projects.put("c", project);
    start(0);

    String text = section("c");
    assertFalse(text.contains("above the repo"), "the walk stops at the folder holding .git");
    assertFalse(text.contains("claude web rules"), "AGENTS.md wins over CLAUDE.md in one folder");
    int g = text.indexOf("global rules");
    int r = text.indexOf("repo rules");
    int a = text.indexOf("apps rules");
    int w = text.indexOf("web rules");
    assertTrue(g >= 0 && g < r && r < a && a < w, text);
    assertTrue(text.contains("### " + project.resolve("AGENTS.md")), text);
    assertEquals(InstructionsPlugin.ORDER, root.collect(Chat.CONTEXT, new Chat.ContextQuery("c")).get(0).order());
  }

  @Test
  void eachChatGetsItsProjectsFilesAndASubAgentItsOrigins() throws Exception {
    Path other = Files.createDirectories(tmp.resolve("other"));
    Files.createDirectories(other.resolve(".git"));
    Files.writeString(other.resolve("AGENTS.md"), "other rules");
    Files.writeString(project.resolve("AGENTS.md"), "web rules");
    projects.put("c", project);
    projects.put("d", other);
    start(0);

    assertTrue(section("c").contains("web rules"));
    assertTrue(section("d").contains("other rules"));
    assertFalse(section("d").contains("web rules"));
    String sub = section(new Chat.ContextQuery("c~explore-1", "c", "explore"));
    assertTrue(sub.contains("web rules"), "a sub-agent works in the project of its origin");
    assertNull(section("no-project"), "no file in the default folder: no section");
  }

  @Test
  void anEditedFileIsSeenOnTheNextAsk() throws Exception {
    Path file = project.resolve("AGENTS.md");
    Files.writeString(file, "first");
    projects.put("c", project);
    start(0);
    assertTrue(section("c").contains("first"));
    Files.writeString(file, "second version");
    Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 2000));
    assertTrue(section("c").contains("second version"));
    Files.delete(file);
    assertNull(section("c"));
  }

  @Test
  void overTheBudgetTheFarthestParentIsCutFirstAndTheProjectLast() throws Exception {
    Files.writeString(global, "G".repeat(40));
    Files.writeString(repo.resolve("AGENTS.md"), "R".repeat(40));
    Files.writeString(project.resolve("AGENTS.md"), "W".repeat(40));
    projects.put("c", project);
    start(70);

    String text = section("c");
    assertFalse(text.contains("R"), "the parent goes first: " + text);
    assertTrue(text.contains("[left out: 40 chars; read the file if you need it]"), text);
    assertTrue(text.contains("G".repeat(30) + "\n[cut: 10 more chars"), "then the global file: " + text);
    assertTrue(text.contains("W".repeat(40)), "the project's own file is kept whole");

    StringBuilder out = new StringBuilder();
    projects.put("s", project);
    assertTrue(Commands.run(root, "instructions", "", new Session("s", out::append, new CancelToken())));
    assertTrue(out.toString().contains(repo.resolve("AGENTS.md") + "  0 chars, 40 left out (instructions.maxChars 70)"),
        out.toString());
  }

  @Test
  void disabledAddsNothingAndDisposingRemovesTheSection() throws Exception {
    Files.writeString(project.resolve("AGENTS.md"), "web rules");
    projects.put("c", project);
    ConfigHandle<InstructionsConfig> h = start(0);
    assertTrue(section("c").contains("web rules"));
    h.update(new InstructionsConfig(false, global.toString(), null, null, 0));
    assertNull(section("c"));
    h.update(new InstructionsConfig(true, global.toString(), List.of("CLAUDE.md"), null, 0));
    assertNull(section("c"), "only the configured names are looked for");
    h.dispose();
    assertTrue(root.collect(Chat.CONTEXT, new Chat.ContextQuery("c")).isEmpty());
  }

  @Test
  void theCommandSaysWhereItLookedWhenThereIsNothing() {
    start(0);
    StringBuilder out = new StringBuilder();
    assertTrue(Commands.run(root, "instructions", "", new Session("s", out::append, new CancelToken())));
    assertTrue(out.toString().startsWith("no instruction files: looked for AGENTS.md, CLAUDE.md from "), out.toString());
  }
}
