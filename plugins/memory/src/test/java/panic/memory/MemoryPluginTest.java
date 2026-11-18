package panic.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import context.ConfigHandle;
import context.Context;
import context.Root;
import core.Signal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import panic.chat.Chat;
import panic.chat.ChatConfig;
import panic.chat.ChatPlugin;
import panic.chat.Conversation;
import panic.chat.Conversations;
import panic.command.Commands;
import panic.command.Session;
import panic.llm.ChatRequest;
import panic.llm.Completion;
import panic.llm.Llm;
import panic.llm.Message;
import panic.llm.Provider;
import panic.llm.Role;
import panic.llm.ToolCall;
import panic.llm.Usage;
import panic.tools.PermissionsConfig;
import panic.tools.PermissionsPlugin;
import panic.tools.ToolContext;
import panic.tools.ToolResult;
import panic.tools.Tools;
import panic.tools.Workspace;
import panic.util.CancelToken;
import panic.util.Json;

class MemoryPluginTest {

  @TempDir
  Path tmp;

  private final Root root = Context.root("t");
  private final Map<String, Path> projects = new ConcurrentHashMap<>();
  private final List<Tools.Approval> approvals = new CopyOnWriteArrayList<>();
  private Path dir;
  private Path project;
  private ConfigHandle<MemoryConfig> memory;

  @BeforeEach
  void setUp() throws Exception {
    dir = tmp.resolve("memory");
    project = Files.createDirectories(tmp.resolve("shop"));
    root.plugin("workspace", ctx -> {
      ctx.hook(Workspace.ROOT, q -> projects.get(q.conversationId()));
      return null;
    });
    root.plugin("approver", ctx -> {
      ctx.hook(Tools.APPROVE, a -> {
        approvals.add(a);
        return Tools.Decision.ALLOW;
      });
      return null;
    });
    // the default policy: reads run, writes are asked
    root.plugin("permissions", new PermissionsPlugin(), PermissionsConfig.DEFAULT);
    projects.put("c", project);
    memory = root.plugin("memory", new MemoryPlugin(), config(dir, 0, 0));
  }

  @AfterEach
  void dispose() {
    root.dispose();
  }

  private MemoryConfig config(Path d, int maxIndex, int maxFile) {
    return new MemoryConfig(null, d.toString(), tmp.resolve("default").toString(), maxIndex, maxFile);
  }

  private ToolResult call(ToolContext tc, String name, String args) {
    return Tools.call(root, new ToolCall("id", name, args), tc);
  }

  private ToolResult call(String conversation, String name, String args) {
    return call(new ToolContext(conversation, new CancelToken()), name, args);
  }

  private static String edit(String command, String path, String... kv) {
    ObjectNode o = Json.object().put("command", command).put("path", path);
    for (int i = 0; i < kv.length; i += 2) o.put(kv[i], kv[i + 1]);
    return o.toString();
  }

  private ToolResult create(String conversation, String path, String text) {
    return call(conversation, "memory_edit", edit("create", path, "file_text", text));
  }

  private String section(Chat.ContextQuery q) {
    return root.collect(Chat.CONTEXT, q).stream().filter(s -> s.name().equals(MemoryPlugin.SECTION))
        .map(Chat.Section::text).findFirst().orElse(null);
  }

  /** Runs {@code memory <args>} as a front-end does. */
  private String command(String session, String args) {
    StringBuilder out = new StringBuilder();
    assertTrue(Commands.run(root, "memory", args, new Session(session, out::append, new CancelToken())));
    return out.toString();
  }

  private Path projectDir() {
    return new MemoryStore(dir.toAbsolutePath().normalize(), 1).projectDir(project);
  }

  @Test
  void createViewReplaceAndDelete() throws Exception {
    String fact = "---\nname: lang\ndescription: answers in Vietnamese\ntype: user\n---\nReply in Vietnamese.\n";
    assertEquals(ToolResult.ok("created /memories/user/lang.md"), create("c", "/memories/user/lang.md", fact));
    assertEquals(1, approvals.size(), "a write is asked by default");
    assertEquals("c", approvals.get(0).conversationId());

    assertEquals(ToolResult.ok("     5\t---\n     6\tReply in Vietnamese."),
        call("c", "memory_view", "{\"path\":\"/memories/user/lang.md\",\"view_range\":[5,-1]}"));
    String list = call("c", "memory_view", "{\"path\":\"/memories\"}").content();
    assertTrue(list.contains("- /memories/user/lang.md (" + fact.length() + " chars): answers in Vietnamese"), list);
    assertTrue(list.contains("/memories/project/\n(no memories)"), list);
    assertEquals(1, approvals.size(), "reading is never asked");

    assertEquals(ToolResult.ok("edited /memories/user/lang.md"), call("c", "memory_edit",
        edit("str_replace", "/memories/user/lang.md", "old_str", "Vietnamese.", "new_str", "Vietnamese, briefly.")));
    assertTrue(Files.readString(dir.resolve("user/lang.md")).endsWith("Reply in Vietnamese, briefly.\n"));
    assertEquals(ToolResult.error("old_str is not in /memories/user/lang.md; view the file and copy the text exactly"),
        call("c", "memory_edit", edit("str_replace", "/memories/user/lang.md", "old_str", "nope", "new_str", "x")));
    assertEquals(ToolResult.error("old_str is in /memories/user/lang.md 2 times; include more of the text around it "
        + "so it is unique"), call("c", "memory_edit", edit("str_replace", "/memories/user/lang.md", "old_str", "---",
            "new_str", "x")));

    assertEquals(ToolResult.ok("replaced /memories/user/lang.md"), create("c", "/memories/user/lang.md", "short"));
    assertEquals(ToolResult.ok("deleted /memories/user/lang.md; remove its line from MEMORY.md too"),
        call("c", "memory_edit", edit("delete", "/memories/user/lang.md")));
    assertEquals(ToolResult.error("no such memory: /memories/user/lang.md"),
        call("c", "memory_view", "{\"path\":\"/memories/user/lang.md\"}"));
  }

  @Test
  void pathsThatLeaveTheirScopeAreRefused() throws Exception {
    assertEquals(ToolResult.error("bad path: /memories/user/../../escape.md"),
        create("c", "/memories/user/../../escape.md", "x"));
    assertEquals(ToolResult.error("paths start with /memories/user or /memories/project, not /etc/passwd"),
        call("c", "memory_view", "{\"path\":\"/etc/passwd\"}"));
    assertEquals(ToolResult.error("unknown scope 'other': use /memories/user or /memories/project"),
        create("c", "/memories/other/a.md", "x"));
    assertEquals(ToolResult.error("memories are Markdown files: name it *.md, not /memories/user/run.sh"),
        create("c", "/memories/user/run.sh", "x"));
    assertEquals(ToolResult.error("give a file path, such as /memories/user/MEMORY.md"),
        create("c", "/memories/user", "x"));
    assertTrue(Files.notExists(tmp.resolve("escape.md")));

    Path outside = Files.writeString(tmp.resolve("secret.md"), "secret");
    Files.createDirectories(dir.resolve("user"));
    Files.createSymbolicLink(dir.resolve("user/link.md"), outside);
    assertEquals(ToolResult.error("not a memory file: /memories/user/link.md"),
        call("c", "memory_view", "{\"path\":\"/memories/user/link.md\"}"));
    assertEquals(ToolResult.error("not a memory file: /memories/user/link.md"),
        create("c", "/memories/user/link.md", "overwrite"));
    assertEquals("secret", Files.readString(outside));
  }

  @Test
  void aFileOverTheLimitIsRefused() {
    memory.update(config(dir, 0, 10));
    assertEquals(ToolResult.error("/memories/user/a.md would hold 11 chars, more than 10: keep one fact per file and "
        + "split it"), create("c", "/memories/user/a.md", "x".repeat(11)));
    assertEquals(ToolResult.ok("created /memories/user/a.md"), create("c", "/memories/user/a.md", "x".repeat(10)));
  }

  @Test
  void projectsHaveTheirOwnMemoryAndShareTheUsers() throws Exception {
    Path repo = Files.createDirectories(tmp.resolve("mono"));
    Files.createDirectories(repo.resolve(".git"));
    projects.put("api", Files.createDirectories(repo.resolve("api")));
    projects.put("web", Files.createDirectories(repo.resolve("web")));
    create("c", "/memories/project/MEMORY.md", "- [Shop](shop.md) — the shop");
    create("c", "/memories/user/MEMORY.md", "- [Lang](lang.md) — Vietnamese");
    create("api", "/memories/project/MEMORY.md", "- [Mono](mono.md) — the monorepo");

    String shop = section(new Chat.ContextQuery("c"));
    assertTrue(shop.contains("### /memories/project/MEMORY.md\n- [Shop](shop.md) — the shop"), shop);
    assertTrue(shop.contains("### /memories/user/MEMORY.md\n- [Lang](lang.md) — Vietnamese"), shop);
    String web = section(new Chat.ContextQuery("web"));
    assertTrue(web.contains("- [Mono](mono.md)"), "folders of one git repository share its memory: " + web);
    assertTrue(web.contains("- [Lang](lang.md)"), web);
    assertFalse(web.contains("Shop"), web);
    assertTrue(Files.isRegularFile(projectDir().resolve("MEMORY.md")));
    assertTrue(projectDir().getFileName().toString().startsWith("shop-"), projectDir().toString());
    assertTrue(Files.notExists(project.resolve("MEMORY.md")), "nothing is written into the project");
  }

  @Test
  void theSectionGuidesTheMainAgentAndShowsASubAgentOnlyTheIndexes() {
    String main = section(new Chat.ContextQuery("c"));
    assertTrue(main.startsWith(MemoryPlugin.GUIDE), main);
    assertTrue(main.endsWith("### /memories/project/MEMORY.md\n(empty)"), main);
    assertNull(section(new Chat.ContextQuery("c~explore-1", "c", "explore")), "a sub-agent with nothing to read");

    create("c", "/memories/project/MEMORY.md", "- [Shop](shop.md) — the shop");
    String sub = section(new Chat.ContextQuery("c~explore-1", "c", "explore"));
    assertFalse(sub.contains("Save a memory"), sub);
    assertTrue(sub.contains("- [Shop](shop.md)"), "it reads the project of its origin: " + sub);
    assertEquals(MemoryPlugin.ORDER, root.collect(Chat.CONTEXT, new Chat.ContextQuery("c")).get(0).order());
  }

  @Test
  void aLongIndexIsCutAtALine() {
    memory.update(config(dir, 30, 0));
    create("c", "/memories/user/MEMORY.md", "- line one is here\n- line two is here\n- line three");
    String text = section(new Chat.ContextQuery("c"));
    assertTrue(text.contains("### /memories/user/MEMORY.md\n- line one is here\n[index cut: "), text);
  }

  @Test
  void aSubAgentReadsButDoesNotWrite() {
    create("c", "/memories/user/a.md", "a fact");
    ToolContext sub = new ToolContext("c~explore-1", new CancelToken(), null, "c", "explore", null, null, null);
    assertEquals(ToolResult.ok("     1\ta fact"), call(sub, "memory_view", "{\"path\":\"/memories/user/a.md\"}"));
    assertEquals(ToolResult.error("only the main agent saves memories; report it in your answer"),
        call(sub, "memory_edit", edit("create", "/memories/user/b.md", "file_text", "x")));
    assertTrue(Files.notExists(dir.resolve("user/b.md")));
  }

  @Test
  void commandsListShowForgetAndWhere() throws Exception {
    create("c", "/memories/user/lang.md", "---\ndescription: Vietnamese\n---\nbody");
    create("c", "/memories/user/MEMORY.md", "- [Lang](lang.md) — Vietnamese\n- [Other](other.md) — other");

    String list = command("c", "");
    assertTrue(list.contains("- /memories/user/lang.md (") && list.contains("): Vietnamese"), list);
    assertTrue(command("c", "show user/lang.md").contains("     4\tbody"));
    assertEquals("forgot /memories/user/lang.md and its line in MEMORY.md\n", command("c", "forget /memories/user/lang.md"));
    assertEquals("- [Other](other.md) — other", Files.readString(dir.resolve("user/MEMORY.md")));
    assertEquals("no such memory: /memories/user/lang.md\n", command("c", "forget user/lang.md"));
    String where = command("c", "where");
    assertTrue(where.contains("/memories/project " + projectDir()), where);
  }

  @Test
  void aNewDirectoryAppliesAfterAConfigChangeAndDisabledOffersNothing() {
    create("c", "/memories/user/a.md", "old");
    Path other = tmp.resolve("other");
    memory.update(config(other, 0, 0));
    assertEquals(ToolResult.ok("created /memories/user/b.md"), create("c", "/memories/user/b.md", "new"));
    assertTrue(Files.isRegularFile(other.resolve("user/b.md")));
    assertTrue(Files.isRegularFile(dir.resolve("user/a.md")), "the old directory is left as it was");

    memory.update(new MemoryConfig(false, other.toString(), null, 0, 0));
    assertTrue(Tools.specs(root).stream().noneMatch(s -> s.name().startsWith("memory_")));
    assertNull(section(new Chat.ContextQuery("c")));
  }

  // ---------------- a whole turn through chat ----------------

  /** Answers every request with {@link #answer}; records the requests. */
  static final class FakeLlm implements Llm {
    final List<ChatRequest> requests = new CopyOnWriteArrayList<>();
    volatile Function<ChatRequest, Completion> answer;

    @Override
    public Provider provider() {
      throw new UnsupportedOperationException();
    }

    @Override
    public String model() {
      return "m";
    }

    @Override
    public Completion complete(ChatRequest r, Signal s) {
      return stream(r, s, d -> { });
    }

    @Override
    public Completion stream(ChatRequest r, Signal s, Consumer<String> onDelta) {
      requests.add(r);
      return answer.apply(r);
    }
  }

  @Test
  void theAgentSavesAMemoryThatTheNextChatSees() {
    FakeLlm llm = new FakeLlm();
    Map<String, Conversation> all = new ConcurrentHashMap<>();
    root.plugin("llm", ctx -> {
      ctx.provide(Llm.KEY, llm);
      return null;
    });
    root.plugin("conversations", ctx -> {
      ctx.provide(Conversations.KEY, id -> all.computeIfAbsent(id, Conversation::new));
      return null;
    });
    root.plugin("chat", new ChatPlugin(), new ChatConfig("main", 0, true, 0, 0, 0, 0, 0, null, null, null, 0));
    String save = edit("create", "/memories/user/MEMORY.md", "file_text", "- [Lang](lang.md) — Vietnamese");
    llm.answer = r -> r.messages().stream().anyMatch(m -> m.role() == Role.TOOL)
        ? new Completion("saved", "m", "stop", Usage.NONE)
        : new Completion("", "m", "tool_calls", Usage.NONE, List.of(new ToolCall("1", "memory_edit", save)));

    assertEquals("saved", Chat.ask(root, new Chat.Ask("c", "remember: Vietnamese", new CancelToken(), d -> { }))
        .orElseThrow().text());
    Message system = llm.requests.get(0).messages().get(0);
    assertTrue(system.content().contains("## Memory\n" + MemoryPlugin.GUIDE), system.content());
    assertEquals(List.of("memory_edit"), approvals.stream().map(a -> a.call().name()).toList());

    llm.requests.clear();
    llm.answer = r -> new Completion("xin chào", "m", "stop", Usage.NONE);
    Chat.ask(root, new Chat.Ask("fresh", "hi", new CancelToken(), d -> { })).orElseThrow();
    assertTrue(llm.requests.get(0).messages().get(0).content()
        .contains("### /memories/user/MEMORY.md\n- [Lang](lang.md) — Vietnamese"));
  }
}
