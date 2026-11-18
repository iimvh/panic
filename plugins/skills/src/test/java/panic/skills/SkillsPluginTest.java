package panic.skills;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import panic.chat.Chat;
import panic.command.Commands;
import panic.command.Prompts;
import panic.command.Session;
import panic.llm.ToolCall;
import panic.tools.PermissionsConfig;
import panic.tools.PermissionsPlugin;
import panic.tools.ToolContext;
import panic.tools.ToolResult;
import panic.tools.Tools;
import panic.tools.Workspace;
import panic.util.CancelToken;

class SkillsPluginTest {

  @TempDir
  Path tmp;

  private final Root root = Context.root("t");
  private final List<String> warnings = new CopyOnWriteArrayList<>();
  /** conversation id → project folder, as plugin workspace answers. */
  private final Map<String, Path> projects = new ConcurrentHashMap<>();
  private Path project;
  private Path global;

  @BeforeEach
  void setUp() throws Exception {
    project = Files.createDirectories(tmp.resolve("shop"));
    global = tmp.resolve("home/.panic/skills");
    root.plugin("workspace", ctx -> {
      ctx.hook(Workspace.ROOT, q -> projects.get(q.conversationId()));
      return null;
    });
    root.plugin("permissions", new PermissionsPlugin(), PermissionsConfig.DEFAULT);
    projects.put("c", project);
  }

  @AfterEach
  void dispose() {
    root.dispose();
  }

  private ConfigHandle<SkillsConfig> start(int maxList) {
    return root.plugin("skills", new SkillsPlugin(warnings::add),
        new SkillsConfig(null, global.toString(), null, tmp.resolve("none").toString(), maxList, 0));
  }

  private static Path skill(Path folder, String name, String frontMatter, String body) throws Exception {
    Path dir = Files.createDirectories(folder.resolve(name));
    Files.writeString(dir.resolve("SKILL.md"), "---\n" + frontMatter + "\n---\n" + body + "\n");
    return dir;
  }

  private String section(Chat.ContextQuery q) {
    return root.collect(Chat.CONTEXT, q).stream().filter(s -> s.name().equals(SkillsPlugin.SECTION))
        .map(Chat.Section::text).findFirst().orElse(null);
  }

  private ToolResult call(String conversation, String args) {
    return Tools.call(root, new ToolCall("id", "skill", args), new ToolContext(conversation, new CancelToken()));
  }

  private String command(String args) {
    StringBuilder out = new StringBuilder();
    assertTrue(Commands.run(root, "skills", args, new Session("c", out::append, new CancelToken())));
    return out.toString();
  }

  @Test
  void readsTheFrontMatter() {
    Map<String, String> meta = new LinkedHashMap<>();
    String body = SkillStore.frontMatter("""
        ---
        name: pdf
        description: >
          Fills PDF forms
          and merges files.
        argument-hint: "[file]"
        allowed-tools:
          - Bash
        # a comment
        notes: |
          one
          two
        ---
        # PDF
        Do it.
        """, meta);
    assertEquals("pdf", meta.get("name"));
    assertEquals("Fills PDF forms and merges files.", meta.get("description"));
    assertEquals("[file]", meta.get("argument-hint"));
    assertEquals("", meta.get("allowed-tools"));
    assertEquals("one\ntwo", meta.get("notes"));
    assertEquals("# PDF\nDo it.\n", body);
    assertEquals("no front matter", SkillStore.frontMatter("no front matter", new LinkedHashMap<>()));
  }

  @Test
  void listsTheProjectsSkillsThenTheUsersAndTheProjectWins() throws Exception {
    skill(global, "commit", "name: commit\ndescription: writes commit messages (global)", "global body");
    skill(global, "notes", "description: takes notes", "notes body");
    skill(project.resolve(".claude/skills"), "commit", "name: commit\ndescription: project commits", "project body");
    skill(project.resolve(".panic/skills"), "deploy", "name: deploy\ndescription: deploys the shop", "deploy body");
    start(0);

    String text = section(new Chat.ContextQuery("c"));
    assertTrue(text.startsWith(SkillsPlugin.GUIDE), text);
    assertTrue(text.contains("- commit: project commits"), text);
    assertFalse(text.contains("(global)"), "the project's skill hides the user's of the same name");
    assertTrue(text.indexOf("- deploy") < text.indexOf("- commit"), ".panic/skills comes before .claude/skills");
    assertTrue(text.contains("- notes: takes notes"), "no name: the folder's");
    assertEquals(SkillsPlugin.ORDER, root.collect(Chat.CONTEXT, new Chat.ContextQuery("c")).get(0).order());
    assertTrue(warnings.stream().anyMatch(w -> w.contains("hidden by")), warnings.toString());

    assertNull(section(new Chat.ContextQuery("c~explore-1", "c", "explore")), "a sub-agent gets no list");
    String other = section(new Chat.ContextQuery("no-project"));
    assertFalse(other.contains("deploy"), "another chat sees only the user's skills: " + other);
  }

  @Test
  void skipsSkillsWithABadNameOrNoDescription() throws Exception {
    skill(global, "Bad_Name", "description: x", "b");
    skill(global, "other", "name: mismatch\ndescription: x", "b");
    skill(global, "empty", "name: empty", "b");
    start(0);
    assertNull(section(new Chat.ContextQuery("c")));
    assertEquals(3, warnings.size(), warnings.toString());
    section(new Chat.ContextQuery("c"));
    assertEquals(3, warnings.size(), "each problem is reported once");
  }

  @Test
  void theListIsCutAndLeavesOutUserOnlySkills() throws Exception {
    skill(global, "a-skill", "description: " + "a".repeat(40), "a");
    skill(global, "b-skill", "description: " + "b".repeat(40), "b");
    skill(global, "manual", "description: release steps\ndisable-model-invocation: true", "m");
    start(60);
    String text = section(new Chat.ContextQuery("c"));
    assertTrue(text.contains("- a-skill"), text);
    assertFalse(text.contains("b-skill"), text);
    assertFalse(text.contains("manual"), text);
    assertTrue(text.contains("[1 more skill not listed"), text);
  }

  @Test
  void theToolLoadsASkillWithItsFilesAndArguments() throws Exception {
    Path dir = skill(project.resolve(".agents/skills"), "pdf", "name: pdf\ndescription: works with PDFs",
        "Extract pages $ARGUMENTS.\nSee reference.md.");
    Files.writeString(dir.resolve("reference.md"), "the reference");
    Files.createDirectories(dir.resolve("scripts"));
    Files.writeString(dir.resolve("scripts/fill.py"), "print(1)");
    start(0);

    ToolResult r = call("c", "{\"name\":\"pdf\",\"args\":\"1-3\"}");
    assertFalse(r.isError(), r.content());
    assertTrue(r.content().contains("Base directory: " + dir.toAbsolutePath().normalize()), r.content());
    assertTrue(r.content().contains("- reference.md (13 bytes)"), r.content());
    assertTrue(r.content().contains("- scripts/fill.py"), r.content());
    assertTrue(r.content().contains("Extract pages 1-3."), r.content());

    assertEquals("the reference", call("c", "{\"name\":\"pdf\",\"file\":\"reference.md\"}").content());
    assertTrue(call("c", "{\"name\":\"pdf\",\"file\":\"../../x\"}").content().contains("not inside"));
    assertTrue(call("c", "{\"name\":\"pdf\",\"file\":\"nope.md\"}").content().contains("no file"));
    assertTrue(call("c", "{\"name\":\"nope\"}").content().contains("no skill nope; skills: pdf"));
  }

  @Test
  void theToolRefusesALinkOutOfTheSkillAndAUserOnlySkill() throws Exception {
    Path dir = skill(global, "pdf", "description: works with PDFs", "body");
    Files.writeString(tmp.resolve("secret.txt"), "secret");
    Files.createSymbolicLink(dir.resolve("link.txt"), tmp.resolve("secret.txt"));
    skill(global, "release", "description: ships\ndisable-model-invocation: true", "body");
    start(0);
    ToolResult link = call("c", "{\"name\":\"pdf\",\"file\":\"link.txt\"}");
    assertTrue(link.isError() && link.content().contains("links outside"), link.content());
    assertFalse(call("c", "{\"name\":\"pdf\"}").content().contains("link.txt"), "links are not listed");
    ToolResult manual = call("c", "{\"name\":\"release\"}");
    assertTrue(manual.isError() && manual.content().contains("run by the user only"), manual.content());
  }

  @Test
  void expandsSlashLinesForTheUser() throws Exception {
    skill(global, "hello", "description: greets", "Greet $ARGUMENTS warmly.");
    skill(global, "plain", "description: plain", "Do the plain thing.");
    skill(global, "auto", "description: background\nuser-invocable: false", "a");
    start(0);

    Prompts.Prompt p = Prompts.expand(root, "c", "hello", "bob").orElseThrow();
    assertEquals("skill", p.tool());
    assertEquals("{\"name\":\"hello\",\"args\":\"bob\"}", p.toolArguments());
    assertTrue(p.text().contains("Greet bob warmly."), p.text());
    String plain = Prompts.expand(root, "c", "plain", "fast please").orElseThrow().text();
    assertTrue(plain.endsWith("Do the plain thing.\n\nARGUMENTS: fast please"), plain);
    assertEquals("{\"name\":\"plain\"}", Prompts.expand(root, "c", "plain", "").orElseThrow().toolArguments());
    assertEquals(Optional.empty(), Prompts.expand(root, "c", "auto", ""));
    assertEquals(Optional.empty(), Prompts.expand(root, "c", "nope", ""));
  }

  @Test
  void anEditedSkillIsSeenOnTheNextUseAndDisablingRemovesEverything() throws Exception {
    Path dir = skill(global, "hello", "description: first", "one");
    ConfigHandle<SkillsConfig> handle = start(0);
    assertTrue(section(new Chat.ContextQuery("c")).contains("- hello: first"));
    Path file = dir.resolve("SKILL.md");
    Files.writeString(file, "---\ndescription: second\n---\ntwo\n");
    Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 2000));
    assertTrue(section(new Chat.ContextQuery("c")).contains("- hello: second"));

    handle.update(new SkillsConfig(false, global.toString(), null, null, 0, 0));
    assertNull(section(new Chat.ContextQuery("c")));
    assertTrue(Tools.find(root, "skill").isEmpty());
    assertEquals(Optional.empty(), Prompts.expand(root, "c", "hello", ""));
  }

  @Test
  void theCommandListsAndShowsSkills() throws Exception {
    skill(global, "hello", "description: greets\nargument-hint: <name>\nallowed-tools: Bash\nlicense: MIT", "b");
    skill(global, "memory", "description: shadowed by a command", "b");
    root.plugin("cmd", ctx -> Commands.command(ctx, "memory", "a real command", call -> { }));
    start(0);
    String list = command("");
    assertTrue(list.contains("hello  [global]  greets"), list);
    assertTrue(list.contains("/hello <name>; ignored: allowed-tools"), list);
    assertTrue(list.contains("/memory runs the command of that name"), list);
    String one = command("hello");
    assertTrue(one.contains("SKILL.md") && one.contains("greets"), one);
    assertTrue(command("nope").contains("no skill nope"));
  }
}
