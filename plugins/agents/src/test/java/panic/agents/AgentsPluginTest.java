package panic.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import context.ConfigHandle;
import context.Context;
import context.PluginHandle;
import context.Root;
import context.Task;
import core.CancelledException;
import core.Signal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import panic.chat.Chat;
import panic.chat.ChatConfig;
import panic.chat.ChatPlugin;
import panic.chat.Conversation;
import panic.chat.Conversations;
import panic.command.Commands;
import panic.command.Session;
import panic.util.CancelToken;
import panic.llm.ChatRequest;
import panic.llm.Completion;
import panic.util.Json;
import panic.llm.Llm;
import panic.llm.Message;
import panic.llm.Provider;
import panic.llm.Role;
import panic.llm.ToolCall;
import panic.llm.ToolSpec;
import panic.llm.Usage;
import panic.tools.Risk;
import panic.tools.Tool;
import panic.tools.ToolContext;
import panic.tools.ToolResult;
import panic.tools.Tools;

/** Sub-agents through the {@code agent} tool, in a real tree: fake LLM, conversations, chat, tools, agents. */
class AgentsPluginTest {

  /** Answers every request with {@link #answer}, called with the request. Records the requests. */
  static final class RoutedLlm implements Llm {
    final List<ChatRequest> requests = new CopyOnWriteArrayList<>();
    volatile Function<ChatRequest, Completion> answer = r -> text("hi");

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
      Completion c = answer.apply(r);
      if (!c.text().isEmpty()) onDelta.accept(c.text());
      return c;
    }
  }

  static Completion text(String text) {
    return new Completion(text, "m", "stop", new Usage(10, 5));
  }

  static Completion calls(ToolCall... calls) {
    return new Completion("", "m", "tool_calls", new Usage(10, 5), List.of(calls));
  }

  static ToolCall agent(String id, String name, String task) {
    return new ToolCall(id, AgentsPlugin.TOOL, Json.object().put("agent", name).put("task", task).toString());
  }

  /** True for a sub-agent's request: its system prompt starts with the built-in one. */
  static boolean sub(ChatRequest r) {
    return !r.messages().isEmpty() && r.messages().get(0).content().startsWith(AgentsPlugin.BASE_PROMPT);
  }

  static List<Message> results(ChatRequest r) {
    return r.messages().stream().filter(m -> m.role() == Role.TOOL).toList();
  }

  static List<String> toolNames(ChatRequest r) {
    return r.tools() == null ? List.of() : r.tools().stream().map(ToolSpec::name).toList();
  }

  /** A tool that answers {@code "<name> ran"} after running {@code body}. */
  static Tool tool(String name, Risk risk, Consumer<ToolContext> body) {
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
        body.accept(ctx);
        return ToolResult.ok(name + " ran");
      }
    };
  }

  private static final AgentsConfig.Profile EXPLORE =
      new AgentsConfig.Profile("looks around", null, List.of("look", "change"), 0, 0);

  private final Root root = Context.root("t");
  private final RoutedLlm llm = new RoutedLlm();
  private final Map<String, Conversation> all = new ConcurrentHashMap<>();
  private final List<Tools.Approval> approvals = new CopyOnWriteArrayList<>();
  private final List<Chat.Step> steps = new CopyOnWriteArrayList<>();
  private PluginHandle llmPlugin;

  @BeforeEach
  void setUp() {
    llmPlugin = root.plugin("llm", ctx -> {
      ctx.provide(Llm.KEY, llm);
      return null;
    });
    root.plugin("conversations", ctx -> {
      ctx.provide(Conversations.KEY, id -> all.computeIfAbsent(id, Conversation::new));
      return null;
    });
    root.plugin("user", ctx -> {
      ctx.hook(Tools.APPROVE, a -> {
        approvals.add(a);
        return a.conversationId().equals("c") ? Tools.Decision.ALLOW : null;
      });
      return null;
    });
  }

  @AfterEach
  void dispose() {
    root.dispose();
  }

  private ConfigHandle<ChatConfig> chat(int parallelTools) {
    return root.plugin("chat", new ChatPlugin(),
        new ChatConfig("main", 0, true, 0, 0, 0, 0, 0, null, null, null, parallelTools));
  }

  private ConfigHandle<AgentsConfig> agents(AgentsConfig cfg) {
    return root.plugin("agents", new AgentsPlugin(), cfg);
  }

  private static AgentsConfig profiles(int maxDepth, int maxParallel) {
    return new AgentsConfig(Map.of("explore", EXPLORE), true, maxDepth, maxParallel, 0);
  }

  private void tools(Tool... tools) {
    root.plugin("tools-under-test", ctx -> {
      for (Tool t : tools) Tools.register(ctx, t);
      return null;
    });
  }

  private Chat.Reply ask(String text) {
    return Chat.ask(root, new Chat.Ask("c", text, new CancelToken(), d -> { }, steps::add)).orElseThrow();
  }

  /** The main agent calls {@code call} once, then answers with the tool results it got. */
  private void mainCalls(Function<ChatRequest, Completion> subAgent, ToolCall... call) {
    llm.answer = r -> {
      if (sub(r)) return subAgent.apply(r);
      List<Message> res = results(r);
      if (res.isEmpty()) return calls(call);
      return text("main got: " + String.join(" | ", res.stream().map(Message::content).toList()));
    };
  }

  @Test
  void aProfileRunsAndItsAnswerComesBack() {
    tools(tool("look", Risk.READ, c -> { }), tool("other", Risk.READ, c -> { }));
    chat(1);
    agents(profiles(1, 4));
    mainCalls(r -> results(r).isEmpty() ? calls(new ToolCall("x", "look", "{}")) : text("found 42"),
        agent("a", "explore", "count the files"));

    Chat.Reply reply = ask("how many files?");
    assertEquals("main got: found 42", reply.text());
    // main: 2 calls; sub-agent: 2 calls
    assertEquals(new Usage(40, 20), reply.usage(), "the sub-agent's tokens count in the turn");

    ChatRequest firstSub = llm.requests.stream().filter(AgentsPluginTest::sub).findFirst().orElseThrow();
    assertEquals(List.of("look"), toolNames(firstSub), "only its tools that exist");
    assertTrue(firstSub.messages().get(0).content().contains("looks around"));
    assertEquals(Message.user("count the files"), firstSub.messages().get(1));

    String child = all.keySet().stream().filter(id -> id.startsWith("c~explore-")).findFirst().orElseThrow();
    assertEquals(4, all.get(child).snapshot().messages().size(), "the sub-agent's turn is saved");
    assertEquals(4, all.get("c").snapshot().messages().size());

    List<String> seen = steps.stream()
        .map(s -> (s.nested() ? s.agent() + ">" : "") + s.call().name() + (s.done() ? " done" : "")).toList();
    assertEquals(List.of("agent", "explore>look", "explore>look done", "agent done"), seen);
  }

  @Test
  void generalGetsTheModelsInstructionsAndEveryTool() {
    tools(tool("look", Risk.READ, c -> { }), tool("other", Risk.READ, c -> { }));
    chat(1);
    agents(profiles(1, 4));
    mainCalls(r -> text("done"), new ToolCall("a", AgentsPlugin.TOOL,
        "{\"agent\":\"general\",\"task\":\"do it\",\"instructions\":\"be a careful reviewer\"}"));
    ask("go");
    ChatRequest sub = llm.requests.stream().filter(AgentsPluginTest::sub).findFirst().orElseThrow();
    assertTrue(sub.messages().get(0).content().contains("be a careful reviewer"));
    assertEquals(List.of("look", "other"), toolNames(sub), "every tool but agent at the last depth");
  }

  @Test
  void depthIsLimited() {
    tools(tool("look", Risk.READ, c -> { }));
    chat(1);
    agents(new AgentsConfig(Map.of("explore", new AgentsConfig.Profile("x", null, null, 0, 0)), true, 2, 4, 0));
    AtomicInteger depth = new AtomicInteger();
    // every sub-agent tries to start another one, as long as it may
    llm.answer = r -> {
      if (!sub(r)) return results(r).isEmpty() ? calls(agent("a", "explore", "go deeper")) : text("main done");
      if (results(r).isEmpty() && toolNames(r).contains(AgentsPlugin.TOOL)) {
        depth.incrementAndGet();
        return calls(agent("b", "explore", "deeper"));
      }
      return text("bottom");
    };
    ask("go");
    assertEquals(1, depth.get(), "the sub-agent at depth 1 starts one; the one at depth 2 cannot");
    assertEquals(2, all.keySet().stream().filter(id -> id.contains("~explore-")).count());
  }

  @Test
  void aSubAgentsCallsAreApprovedInTheUsersConversation() {
    tools(tool("change", Risk.WRITE, c -> { }));
    chat(1);
    agents(profiles(1, 4));
    mainCalls(r -> results(r).isEmpty() ? calls(new ToolCall("x", "change", "{}")) : text("changed"),
        agent("a", "explore", "change it"));
    assertEquals("main got: changed", ask("go").text());
    assertEquals(1, approvals.size());
    assertEquals("c", approvals.get(0).conversationId());
    assertEquals("explore", approvals.get(0).agent());
  }

  /** {@code block}: waits until cancelled, counting down {@code started} first. */
  static Tool blocking(CountDownLatch started) {
    return tool("look", Risk.READ, c -> {
      CountDownLatch release = new CountDownLatch(1);
      c.signal().onCancel(release::countDown);
      started.countDown();
      try {
        release.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      c.signal().throwIfCancelled();
    });
  }

  private Task<Optional<Chat.Reply>> askInBackground(CancelToken token) {
    return root.spawn("asker", s -> Chat.ask(root, new Chat.Ask("c", "go", token, d -> { })));
  }

  @Test
  void cancellingTheAskCancelsTheSubAgent() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    tools(blocking(started));
    chat(1);
    agents(profiles(1, 4));
    mainCalls(r -> calls(new ToolCall("x", "look", "{}")), agent("a", "explore", "wait"));
    CancelToken token = new CancelToken();
    Task<Optional<Chat.Reply>> pending = askInBackground(token);
    started.await();
    token.cancel();
    assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThrows(CancelledException.class, pending::await));
    assertTrue(all.get("c").snapshot().messages().isEmpty(), "a cancelled turn is not saved");
  }

  @Test
  void disposingAgentsCancelsTheSubAgentAndWaitsForIt() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    tools(blocking(started));
    chat(1);
    ConfigHandle<AgentsConfig> agents = agents(profiles(1, 4));
    mainCalls(r -> calls(new ToolCall("x", "look", "{}")), agent("a", "explore", "wait"));
    Task<Optional<Chat.Reply>> pending = askInBackground(new CancelToken());
    started.await();
    assertTimeoutPreemptively(Duration.ofSeconds(10), agents::dispose);
    assertThrows(CancelledException.class, pending::await);
  }

  @Test
  void disposingChatDuringASubAgentDoesNotDeadlock() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    tools(blocking(started));
    ConfigHandle<ChatConfig> chat = chat(1);
    agents(profiles(1, 4));
    mainCalls(r -> calls(new ToolCall("x", "look", "{}")), agent("a", "explore", "wait"));
    Task<Optional<Chat.Reply>> pending = askInBackground(new CancelToken());
    started.await();
    assertTimeoutPreemptively(Duration.ofSeconds(10), chat::dispose);
    assertThrows(CancelledException.class, pending::await);
  }

  @Test
  void withoutAModelACallIsAnError() {
    agents(profiles(1, 4));
    llmPlugin.dispose(); // nothing answers Chat.ASK (no chat at all here)
    ToolResult r = Tools.call(root, agent("a", "explore", "x"), new ToolContext("c", new CancelToken()));
    assertEquals(ToolResult.error("agent: no model is connected"), r);
    ToolResult unknown = Tools.call(root, agent("b", "nobody", "x"), new ToolContext("c", new CancelToken()));
    assertTrue(unknown.isError() && unknown.content().contains("invalid arguments"), unknown.content());
  }

  @Test
  void atMostMaxParallelRunAtOnce() {
    AtomicInteger now = new AtomicInteger();
    AtomicInteger most = new AtomicInteger();
    tools(tool("look", Risk.READ, c -> {
      most.accumulateAndGet(now.incrementAndGet(), Math::max);
      try {
        Thread.sleep(150);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      now.decrementAndGet();
    }));
    chat(3);
    agents(profiles(1, 2));
    mainCalls(r -> results(r).isEmpty() ? calls(new ToolCall("x", "look", "{}")) : text("ok"),
        agent("a", "explore", "one"), agent("b", "explore", "two"), agent("c", "explore", "three"));
    Chat.Reply reply = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> ask("go"));
    assertEquals("main got: ok | ok | ok", reply.text());
    assertEquals(2, most.get(), "two sub-agents at once, the third waits");
  }

  @Test
  void aStoppedSubAgentIsAnErrorWithItsLastAnswer() {
    tools(tool("look", Risk.READ, c -> { }));
    chat(1);
    agents(new AgentsConfig(Map.of("explore", new AgentsConfig.Profile("x", null, null, 2, 0)), false, 1, 4, 0));
    mainCalls(r -> new Completion("still looking", "m", "tool_calls", new Usage(1, 1),
        List.of(new ToolCall("x" + r.messages().size(), "look", "{\"n\":" + r.messages().size() + "}"))),
        agent("a", "explore", "never ends"));
    String result = ask("go").text();
    assertTrue(result.startsWith("main got: error: explore stopped before it finished (max_steps, 2 steps)"),
        result);
  }

  @Test
  void configChangesRestartOnlyAgents() {
    chat(1);
    ConfigHandle<AgentsConfig> agents = agents(profiles(1, 4));
    assertTrue(Tools.find(root, AgentsPlugin.TOOL).orElseThrow().description().contains("explore"));
    agents.update(new AgentsConfig(Map.of(), false, 1, 4, 0));
    assertTrue(Tools.find(root, AgentsPlugin.TOOL).isEmpty(), "no sub-agents, no tool");
    llm.answer = r -> text("still here");
    assertEquals("still here", ask("go").text(), "chat kept running");
  }

  @Test
  void theCommandListsTheProfiles() {
    agents(new AgentsConfig(Map.of("explore", new AgentsConfig.Profile("looks around", null, List.of("look"), 5, 0)),
        true, 1, 2, 300));
    StringBuilder out = new StringBuilder();
    Commands.run(root, "agents", "", new Session("s", out::append, new CancelToken()));
    assertEquals("""
        general  every tool  instructions written by the bot
        explore  tools: look  maxSteps 5  looks around
        (depth 1, 2 at once, timeout 300s)
        """, out.toString());
  }

  @Test
  void badConfigIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> new AgentsConfig(Map.of("Bad Name", EXPLORE), true, 0, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> new AgentsConfig(Map.of("general", EXPLORE), true, 0, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> new AgentsConfig.Profile(" ", null, null, 0, 0));
    assertEquals(EXPLORE, new AgentsConfig(Map.of("general", EXPLORE), false, 0, 0, 0).profiles().get("general"));
    assertTrue(AgentsConfig.DEFAULT.profiles().isEmpty());
    assertEquals(600, AgentsConfig.DEFAULT.timeoutSeconds());
  }
}
