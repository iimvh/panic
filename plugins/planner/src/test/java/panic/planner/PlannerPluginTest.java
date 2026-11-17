package panic.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import context.ConfigHandle;
import context.Context;
import context.Root;
import context.Task;
import core.CancelledException;
import core.Signal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import panic.command.Hooks;
import panic.command.Session;
import panic.llm.ChatRequest;
import panic.llm.Completion;
import panic.llm.Llm;
import panic.llm.Message;
import panic.llm.Provider;
import panic.llm.Role;
import panic.llm.ToolCall;
import panic.llm.ToolSpec;
import panic.llm.Usage;
import panic.todo.Todo;
import panic.todo.TodoConfig;
import panic.todo.TodoPlugin;
import panic.tools.Risk;
import panic.tools.Tool;
import panic.tools.ToolContext;
import panic.tools.ToolResult;
import panic.tools.Tools;
import panic.util.CancelToken;
import panic.util.Json;

/** Plan mode in a real tree: planner, todo, a read and a write tool, a fake user and (for turns) chat. */
class PlannerPluginTest {

  @TempDir
  Path dir;

  private final Root root = Context.root("t");
  private final List<Planner.Proposal> proposals = new CopyOnWriteArrayList<>();
  private final List<Planner.ModeChanged> changes = new CopyOnWriteArrayList<>();
  private volatile Function<Planner.Proposal, Planner.Answer> user = p -> Planner.Answer.APPROVED;
  private ConfigHandle<PlannerConfig> planner;

  private static Tool tool(String name, Risk risk) {
    return new Tool() {
      @Override
      public ToolSpec spec() {
        return new ToolSpec(name, "", Json.parseOrNull("{\"type\":\"object\"}"));
      }

      @Override
      public Risk risk() {
        return risk;
      }

      @Override
      public ToolResult call(JsonNode args, ToolContext ctx) {
        return ToolResult.ok(name + " ran");
      }
    };
  }

  @BeforeEach
  void setUp() {
    root.plugin("tools-under-test", ctx -> {
      Tools.register(ctx, tool("look", Risk.READ));
      Tools.register(ctx, tool("change", Risk.WRITE));
      // allow writes so a refusal can only come from plan mode
      ctx.hook(Tools.POLICY, c -> new Tools.Verdict(panic.tools.Permission.ALLOW, 0));
      return null;
    });
    root.plugin("todo", new TodoPlugin(w -> { }), new TodoConfig(dir.toString(), 10));
    root.plugin("user", ctx -> {
      ctx.hook(Planner.APPROVE, p -> {
        if (!p.conversationId().equals("c")) return null;
        proposals.add(p);
        return user.apply(p);
      });
      ctx.on(Planner.CHANGED, changes::add);
      return null;
    });
    planner = root.plugin("planner", new PlannerPlugin(), cfg(false, null, 0));
  }

  /** A config whose plans go under the test's folder when a conversation has no project. */
  private PlannerConfig cfg(boolean byDefault, List<String> allow, int timeout) {
    return new PlannerConfig(byDefault, allow, timeout, null, dir.resolve("app").toString());
  }

  @AfterEach
  void dispose() {
    root.dispose();
  }

  private String plan(String conversation, String state) {
    StringBuilder out = new StringBuilder();
    Commands.run(root, "plan", state, new Session(conversation, out::append, new CancelToken()));
    return out.toString().strip();
  }

  private boolean planning(String conversation) {
    return Hooks.bail(root, Planner.MODE, new Planner.ModeQuery(conversation)).orElseThrow().on();
  }

  private List<String> offered(String conversation) {
    return Tools.specs(root, conversation).stream().map(ToolSpec::name).toList();
  }

  private ToolResult call(String name, String args, ToolContext tc) {
    return Tools.call(root, new ToolCall("id", name, args), tc);
  }

  private ToolResult call(String name, String args) {
    return call(name, args, new ToolContext("c", new CancelToken()));
  }

  private static final String PROPOSAL = """
      {"plan":"# Plan\\nchange the thing","items":["look around","change it"],"criteria":["it is changed"]}""";

  @Test
  void planModeKeepsWritesOutAndOffersTheExitOnlyWhileOn() {
    assertEquals(List.of("change", "look", "todo_read", "todo_write"), offered("c"));
    assertTrue(plan("c", "status").startsWith("plan off in this chat"));

    assertTrue(plan("c", "on").startsWith("plan on"));
    assertTrue(planning("c"));
    // writes stay offered, so a call tells the model why it cannot act
    assertEquals(List.of("change", Planner.EXIT_TOOL, "look", "todo_read", "todo_write"), offered("c"));
    assertEquals(List.of("change", "look", "todo_read", "todo_write"), offered("other"), "per conversation");
    assertEquals(ToolResult.error("change: refused: " + PlannerPlugin.BLOCKED), call("change", "{}"));
    assertEquals(ToolResult.error("todo_write: refused: " + PlannerPlugin.BLOCKED),
        call("todo_write", "{\"items\":[{\"text\":\"x\",\"status\":\"done\"}]}"), "no steps marked done while planning");
    assertEquals(ToolResult.ok("look ran"), call("look", "{}"));
    // a sub-agent of the conversation is gated as well
    ToolContext sub = new ToolContext("c~explore-1", new CancelToken(), null, "c", "explore", null, null, p -> { });
    assertTrue(call("change", "{}", sub).isError());

    List<String> sections = root.collect(Chat.CONTEXT, new Chat.ContextQuery("c")).stream()
        .map(Chat.Section::name).toList();
    assertTrue(sections.contains(PlannerPlugin.SECTION));
    assertFalse(root.collect(Chat.CONTEXT, new Chat.ContextQuery("other")).stream()
        .anyMatch(s -> s.name().equals(PlannerPlugin.SECTION)));

    assertTrue(plan("c", "off").startsWith("plan off"));
    assertEquals(List.of(new Planner.ModeChanged("c", true), new Planner.ModeChanged("c", false)), changes);
    assertEquals(ToolResult.ok("change ran"), call("change", "{}"));
    assertEquals(ToolResult.error(Planner.EXIT_TOOL + ": refused: only in plan mode"), call(Planner.EXIT_TOOL, PROPOSAL));
  }

  @Test
  void anApprovedPlanEndsPlanModeAndBecomesTheTaskList() {
    plan("c", "on");
    ToolResult r = call(Planner.EXIT_TOOL, PROPOSAL);
    assertFalse(r.isError(), r.content());
    assertTrue(r.content().startsWith("The user approved the plan"));

    Planner.Proposal p = proposals.get(0);
    assertEquals("# Plan\nchange the thing", p.plan());
    assertEquals(List.of("look around", "change it"), p.items());
    assertEquals(List.of("it is changed"), p.criteria());
    assertFalse(planning("c"));
    Todo.Plan saved = Hooks.bail(root, Todo.PLAN, new Todo.Query("c")).orElseThrow();
    assertEquals(List.of("[ ] look around", "[ ] change it"),
        saved.items().stream().map(panic.todo.TodoItem::line).toList());
    assertEquals(List.of("it is changed"), saved.criteria());
    assertEquals(ToolResult.ok("change ran"), call("change", "{}"));
  }

  @Test
  void aRejectedPlanSendsTheFeedbackBackAndStaysInPlanMode() {
    plan("c", "on");
    user = p -> Planner.Answer.rejected("smaller steps");
    ToolResult r = call(Planner.EXIT_TOOL, PROPOSAL);
    assertTrue(r.isError());
    assertTrue(r.content().contains("did not approve the plan: smaller steps"), r.content());
    assertTrue(planning("c"));
    assertTrue(Hooks.bail(root, Todo.PLAN, new Todo.Query("c")).isEmpty(), "nothing saved");
  }

  @Test
  void withoutAnAnswerThePlanIsNotApproved() {
    plan("x", "on");
    ToolResult r = call(Planner.EXIT_TOOL, PROPOSAL, new ToolContext("x", new CancelToken()));
    assertTrue(r.content().contains("nobody answered"), r.content());
    assertTrue(planning("x"));
  }

  @Test
  void aPlanSentBackWithoutAReasonStopsTheAgent() {
    plan("c", "on");
    user = p -> Planner.Answer.rejected("");
    ToolResult r = call(Planner.EXIT_TOOL, PROPOSAL);
    assertTrue(r.content().contains("gave no reason. Stop here"), r.content());
    assertTrue(planning("c"));
  }

  private String read(Path file) {
    try {
      return Files.readString(file);
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  @Test
  void plansAreWrittenInTheProjectOfTheConversation() throws Exception {
    Path project = Files.createDirectories(dir.resolve("shop"));
    root.plugin("workspace", ctx -> {
      ctx.hook(panic.tools.Workspace.ROOT, q -> q.conversationId().equals("c") ? project : null);
      return null;
    });
    plan("c", "on");
    String proposal = "{\"plan\":\"# Trang chủ đẹp\\nReact\",\"items\":[\"a\",\"b\"],\"criteria\":[\"ok\"]}";
    user = p -> Planner.Answer.rejected("smaller");
    assertTrue(call(Planner.EXIT_TOOL, proposal).content().contains("did not approve the plan: smaller"));
    // "c" is too short to be an id of its own: the name ends with a hash of it
    Path file = project.resolve(".panic/plans/trang-chu-dep-c.md");
    Path written;
    try (var files = Files.list(project.resolve(".panic/plans"))) {
      written = files.findFirst().orElseThrow();
    }
    assertTrue(written.getFileName().toString().startsWith("trang-chu-dep-"), written.toString());
    String text = read(written);
    assertTrue(text.startsWith("<!-- panic plan: conversation=c status=rejected updated="), text);
    assertTrue(text.contains("# Trang chủ đẹp\nReact\n\n## Steps\n\n1. a\n2. b\n\n## Acceptance criteria\n\n- ok\n"), text);
    assertTrue(text.contains("## Feedback\n\n> smaller"), text);
    assertFalse(Files.exists(file), "named after the title and a short id");

    user = p -> Planner.Answer.APPROVED;
    ToolResult approved = call(Planner.EXIT_TOOL, proposal.replace("Trang chủ đẹp", "Renamed"));
    assertTrue(approved.content().contains("saved in " + written), approved.content());
    try (var files = Files.list(project.resolve(".panic/plans"))) {
      assertEquals(1, files.count(), "a conversation keeps its file");
    }
    assertTrue(read(written).contains("status=approved"));
    assertTrue(read(written).contains("# Renamed"));

    // while it is carried out, the approved plan is in the system prompt
    Chat.Section section = root.collect(Chat.CONTEXT, new Chat.ContextQuery("c")).stream()
        .filter(x -> x.name().equals(PlannerPlugin.APPROVED_SECTION)).findFirst().orElseThrow();
    assertTrue(section.text().contains("# Renamed") && section.text().contains(written.toString()));
    assertTrue(plan("c", "show").startsWith(written + " (approved)"));
    assertTrue(plan("c", "status").endsWith("plan file: " + written));
    // a new round of planning hides it
    plan("c", "on");
    assertFalse(root.collect(Chat.CONTEXT, new Chat.ContextQuery("c")).stream()
        .anyMatch(x -> x.name().equals(PlannerPlugin.APPROVED_SECTION)));
  }

  @Test
  void withoutAProjectPlansGoUnderTheConfiguredRoot() {
    plan("c327b77b6e0b", "on");
    // nobody answers for this conversation, but the proposal is on disk
    ToolResult r = call(Planner.EXIT_TOOL, PROPOSAL, new ToolContext("c327b77b6e0b", new CancelToken()));
    assertTrue(r.content().contains("saved in " + dir.resolve("app/.panic/plans/plan-c327b77b.md")), r.content());
    assertTrue(plan("c327b77b6e0b", "show").contains("(proposed)"));
    assertEquals("no plan saved for this chat (it would go in " + dir.resolve("app/.panic/plans") + ")",
        plan("other", "show"));
  }

  @Test
  void fileNames() {
    assertEquals("landing-page-thuong-mai-dien-tu-react-c327b77b.md",
        PlanFiles.fileName("intro\n## Landing Page Thương Mại Điện Tử - React\n", "c327b77b6e0b760007a8e56e"));
    assertEquals("c327b77b.md", PlanFiles.fileName("!!!", "c327b77b6e0b"));
    assertEquals(40, PlanFiles.slug("a".repeat(50)).length());
  }

  @Test
  void onlyTheMainAgentProposesThePlan() {
    plan("c", "on");
    ToolContext sub = new ToolContext("c~explore-1", new CancelToken(), null, "c", "explore", null, null, p -> { });
    assertEquals(ToolResult.error("only the main agent can propose the plan"), call(Planner.EXIT_TOOL, PROPOSAL, sub));
    assertTrue(proposals.isEmpty());
  }

  /** The user never answers, but honours the proposal's signal. */
  private void silentUser(CountDownLatch asked) {
    user = p -> {
      asked.countDown();
      while (!p.signal().isCancelled()) {
        try {
          Thread.sleep(5);
        } catch (InterruptedException e) {
          return null;
        }
      }
      return null;
    };
  }

  @Test
  void anUnansweredPlanTimesOut() {
    planner.update(cfg(false, null, 1));
    plan("c", "on");
    silentUser(new CountDownLatch(1));
    ToolResult r = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> call(Planner.EXIT_TOOL, PROPOSAL));
    assertTrue(r.content().startsWith("nobody answered"), r.content());
    assertTrue(planning("c"));
  }

  @Test
  void disposingThePlannerCancelsAWaitingProposal() throws Exception {
    plan("c", "on");
    CountDownLatch asked = new CountDownLatch(1);
    silentUser(asked);
    Task<ToolResult> running = root.spawn("ask", s -> call(Planner.EXIT_TOOL, PROPOSAL));
    assertTrue(asked.await(5, TimeUnit.SECONDS));
    assertTimeoutPreemptively(Duration.ofSeconds(5), () -> planner.update(cfg(false, List.of("x"), 0)));
    assertThrows(CancelledException.class, running::await);
    assertFalse(planning("c"), "a restart forgets the switches");
  }

  @Test
  void configDefaultAndAllowList() {
    planner.update(cfg(true, List.of("todo_write"), 0));
    assertTrue(planning("any"));
    ToolContext any = new ToolContext("any", new CancelToken());
    assertFalse(call("todo_write", "{\"items\":[]}", any).isError(), "allowed by name");
    assertTrue(call("change", "{}", any).isError());
    plan("any", "off");
    assertFalse(planning("any"));
    assertEquals(new Planner.Mode(false, true),
        Hooks.bail(root, Planner.MODE, new Planner.ModeQuery("any")).orElseThrow());
    assertThrows(RuntimeException.class, () -> plan("any", "maybe"));
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

  private static List<String> toolNames(ChatRequest r) {
    return r.tools() == null ? List.of() : r.tools().stream().map(ToolSpec::name).toList();
  }

  @Test
  void theAgentPlansThenCarriesOutTheApprovedPlanInTheSameTurn() {
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
    plan("c", "on");
    llm.answer = r -> {
      long results = r.messages().stream().filter(m -> m.role() == Role.TOOL).count();
      // the model first tries to act, is told to plan, proposes, then acts
      if (results == 0) return new Completion("", "m", "tool_calls", Usage.NONE, List.of(new ToolCall("0", "change", "{}")));
      if (results == 1) {
        return new Completion("", "m", "tool_calls", Usage.NONE, List.of(new ToolCall("1", Planner.EXIT_TOOL, PROPOSAL)));
      }
      if (results == 2) return new Completion("", "m", "tool_calls", Usage.NONE, List.of(new ToolCall("2", "change", "{}")));
      return new Completion("done", "m", "stop", Usage.NONE);
    };

    Chat.Reply reply = Chat.ask(root, new Chat.Ask("c", "change the thing", new CancelToken(), d -> { })).orElseThrow();
    assertEquals("done", reply.text());
    assertEquals(4, llm.requests.size());
    assertTrue(llm.requests.get(0).messages().get(0).content().contains(PlannerPlugin.INSTRUCTIONS));
    assertTrue(toolNames(llm.requests.get(0)).contains(Planner.EXIT_TOOL));
    assertFalse(toolNames(llm.requests.get(2)).contains(Planner.EXIT_TOOL), "gone once the plan is approved");
    List<Message> tools = llm.requests.get(3).messages().stream().filter(m -> m.role() == Role.TOOL).toList();
    assertEquals("error: change: refused: " + PlannerPlugin.BLOCKED, tools.get(0).content());
    assertEquals("change ran", tools.get(2).content());
    assertEquals(1, proposals.size());
  }
}
