package panic.chat;

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
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import panic.llm.ToolCallDelta;
import panic.llm.ToolSpec;
import panic.llm.Usage;
import panic.tools.Risk;
import panic.tools.Tool;
import panic.tools.ToolContext;
import panic.tools.ToolResult;
import panic.tools.Tools;

class AgentLoopTest {

  /** Answers each request with the next scripted step; the last step repeats. Records every request. */
  static final class ScriptedLlm implements Llm {
    final List<ChatRequest> requests = new CopyOnWriteArrayList<>();
    final Deque<Function<ChatRequest, Completion>> script = new ArrayDeque<>();

    ScriptedLlm then(Function<ChatRequest, Completion> step) {
      script.add(step);
      return this;
    }

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

    /** Reports every scripted call as one piece, the way a provider that sends whole calls does. */
    @Override
    public Completion stream(ChatRequest r, Signal s, Consumer<String> onDelta, Consumer<ToolCallDelta> onToolDelta) {
      Completion c = stream(r, s, onDelta);
      for (int i = 0; i < c.toolCalls().size(); i++) {
        ToolCall call = c.toolCalls().get(i);
        onToolDelta.accept(new ToolCallDelta(i, call.id(), call.name(), call.arguments()));
      }
      return c;
    }

    @Override
    public Completion stream(ChatRequest r, Signal s, Consumer<String> onDelta) {
      requests.add(r);
      Function<ChatRequest, Completion> step = script.size() > 1 ? script.poll() : script.peek();
      Completion c = step.apply(r);
      if (!c.text().isEmpty()) onDelta.accept(c.text());
      return c;
    }
  }

  static Completion calls(ToolCall... calls) {
    return new Completion("", "m", "tool_calls", new Usage(10, 5), List.of(calls));
  }

  static Completion answer(String text) {
    return new Completion(text, "m", "stop", new Usage(10, 5));
  }

  static ToolCall call(String id, String name, String args) {
    return new ToolCall(id, name, args);
  }

  /** {@code echo {text}} answers its text in upper case. */
  static final Tool ECHO = new Tool() {
    @Override
    public ToolSpec spec() {
      return new ToolSpec("echo", "upper-cases text", Json.parseOrNull(
          "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}"));
    }

    @Override
    public Risk risk() {
      return Risk.READ;
    }

    @Override
    public ToolResult call(JsonNode args, ToolContext ctx) {
      return ToolResult.ok(args.get("text").asText().toUpperCase());
    }
  };

  private final Root root = Context.root("t");
  private final ScriptedLlm llm = new ScriptedLlm();
  private final List<Chat.Step> steps = new CopyOnWriteArrayList<>();

  @BeforeEach
  void setUp() {
    root.plugin("llm", ctx -> {
      ctx.provide(Llm.KEY, llm);
      return null;
    });
    root.plugin("conversations", Conversations.inMemory());
  }

  @AfterEach
  void dispose() {
    root.dispose();
  }

  private void tools(Tool... tools) {
    root.plugin("tools-under-test", ctx -> {
      for (Tool t : tools) Tools.register(ctx, t);
      return null;
    });
  }

  private ConfigHandle<ChatConfig> chat(ChatConfig cfg) {
    return root.plugin("chat", new ChatPlugin(), cfg);
  }

  private static ChatConfig cfg(int maxSteps, int maxTokens, int timeoutSeconds, int maxOutput, int maxRepeats) {
    return new ChatConfig(null, 0, true, maxSteps, maxTokens, timeoutSeconds, maxOutput, maxRepeats, null, null);
  }

  private Chat.Reply ask(String text) {
    return Chat.ask(root, new Chat.Ask("c", text, new CancelToken(), d -> { }, steps::add)).orElseThrow();
  }

  /** The saved history of conversation {@code id}. */
  private List<Message> conversation(String id) {
    return panic.command.Hooks.bail(root, Conversations.HISTORY, new Conversations.HistoryQuery(id)).orElseThrow()
        .messages();
  }

  private static List<Message> toolMessages(ChatRequest r) {
    return r.messages().stream().filter(m -> m.role() == Role.TOOL).toList();
  }

  @Test
  void piecesOfToolCallsComeBeforeTheirSteps() {
    tools(ECHO);
    chat(ChatConfig.DEFAULT);
    llm.then(r -> calls(call("a", "echo", "{\"text\":\"hi\"}"))).then(r -> answer("done"));
    List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
    Chat.ask(root, new Chat.Ask("c", "go", new CancelToken(), d -> seen.add("text " + d),
        s -> seen.add((s.done() ? "done " : "run ") + s.call().id()), null,
        d -> seen.add("piece " + d.index() + " " + d.name() + " " + d.arguments()))).orElseThrow();
    assertEquals(List.of("piece 0 echo {\"text\":\"hi\"}", "run a", "done a", "text done"), seen);
  }

  @Test
  void runsToolsAndFeedsResultsBack() {
    tools(ECHO);
    chat(ChatConfig.DEFAULT);
    llm.then(r -> calls(call("a", "echo", "{\"text\":\"hi\"}"), call("b", "echo", "{\"text\":\"yo\"}")))
        .then(r -> answer("done"));

    Chat.Reply reply = ask("shout");
    assertEquals("done", reply.text());
    assertEquals(Chat.DONE, reply.stop());
    assertEquals(2, reply.steps());
    assertEquals(2, reply.toolCalls());
    assertEquals(new Usage(20, 10), reply.usage(), "usage of every model call");

    ChatRequest first = llm.requests.get(0);
    assertEquals(List.of("echo"), first.tools().stream().map(ToolSpec::name).toList());
    assertEquals(List.of(Message.tool("a", "HI"), Message.tool("b", "YO")), toolMessages(llm.requests.get(1)));

    assertEquals(4, steps.size(), "start and finish of each call");
    assertFalse(steps.get(0).done());
    assertEquals(ToolResult.ok("HI"), steps.get(1).result());
  }

  @Test
  void savesTheWholeTurnAndSendsItNextTime() {
    tools(ECHO);
    chat(ChatConfig.DEFAULT);
    llm.then(r -> calls(call("a", "echo", "{\"text\":\"hi\"}"))).then(r -> answer("HI it is")).then(r -> answer("ok"));
    ask("one");
    ask("two");
    List<Message> sent = llm.requests.get(2).messages();
    assertEquals(List.of(Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.TOOL, Role.ASSISTANT, Role.USER),
        sent.stream().map(Message::role).toList());
    assertTrue(sent.get(2).hasToolCalls());
  }

  @Test
  void historyNeverStartsWithAToolResult() {
    tools(ECHO);
    chat(new ChatConfig(null, 2));
    llm.then(r -> calls(call("a", "echo", "{\"text\":\"hi\"}"))).then(r -> answer("x")).then(r -> answer("y"));
    ask("one"); // stores user, assistant(call), tool, assistant
    ask("two"); // the last 2 would start with the tool result: nothing of turn one is sent
    assertEquals(List.of(Message.user("two")), llm.requests.get(2).messages());
  }

  @Test
  void toolsCanBeTurnedOff() {
    tools(ECHO);
    chat(new ChatConfig(null, 0, false, 0, 0, 0, 0, 0, null, null));
    llm.then(r -> answer("plain"));
    ask("hi");
    assertTrue(llm.requests.get(0).tools().isEmpty());
  }

  @Test
  void badCallsAreReportedToTheModel() {
    tools(ECHO);
    chat(ChatConfig.DEFAULT);
    llm.then(r -> calls(call("a", "nope", "{}"), call("b", "echo", "{}"), call("c", "echo", "{oops")))
        .then(r -> answer("sorry"));
    Chat.Reply reply = ask("x");
    assertEquals("sorry", reply.text());
    List<Message> results = toolMessages(llm.requests.get(1));
    assertEquals("error: unknown tool: nope", results.get(0).content());
    assertEquals("error: echo: invalid arguments: text: required", results.get(1).content());
    assertTrue(results.get(2).content().startsWith("error: echo: arguments are not valid JSON"));
  }

  @Test
  void stepLimitStopsAModelThatNeverAnswers() {
    tools(ECHO);
    chat(cfg(3, 0, 0, 0, 100));
    int[] n = {0};
    llm.then(r -> calls(call("c" + n[0], "echo", "{\"text\":\"" + (n[0]++) + "\"}")));
    Chat.Reply reply = ask("go");
    assertEquals(Chat.MAX_STEPS, reply.stop());
    assertEquals(3, llm.requests.size());
    assertEquals(2, reply.toolCalls());

    // the stopped turn is still valid history: every call has a result
    llm.script.clear();
    llm.then(r -> answer("fine"));
    ask("again");
    List<Message> sent = llm.requests.get(3).messages();
    long calls = sent.stream().mapToLong(m -> m.toolCalls().size()).sum();
    assertEquals(calls, sent.stream().filter(m -> m.role() == Role.TOOL).count());
  }

  @Test
  void tokenBudgetStopsTheTurn() {
    tools(ECHO);
    chat(cfg(0, 30, 0, 0, 100));
    llm.then(r -> calls(call("a", "echo", "{\"text\":\"" + r.messages().size() + "\"}")));
    Chat.Reply reply = ask("go");
    assertEquals(Chat.BUDGET, reply.stop());
    assertEquals(2, llm.requests.size(), "15 tokens per call: stops once 30 are used");
  }

  @Test
  void repeatedCallsAreRefusedButOnlyTheStepLimitStopsTheTurn() {
    tools(ECHO);
    chat(cfg(6, 0, 0, 0, 2));
    llm.then(r -> calls(call("x" + r.messages().size(), "echo", "{\"text\":\"same\"}")));
    Chat.Reply reply = ask("go");
    assertEquals(Chat.MAX_STEPS, reply.stop(), "a repeat is feedback, not a stop");
    assertEquals(2, reply.toolCalls(), "runs twice, then every repeat is refused");
    assertEquals(6, llm.requests.size());
    assertTrue(toolMessages(llm.requests.get(3)).get(2).content().contains("same call was made 3 times"));
    assertTrue(toolMessages(llm.requests.get(4)).get(3).content().contains("same call was made 4 times"));
  }

  @Test
  void aModelThatRecoversFromARepeatGoesOn() {
    tools(ECHO);
    chat(cfg(20, 0, 0, 0, 1));
    String same = "{\"text\":\"same\"}";
    llm.then(r -> calls(call("a", "echo", same))).then(r -> calls(call("b", "echo", same)))
        .then(r -> calls(call("c", "echo", same))).then(r -> calls(call("d", "echo", "{\"text\":\"other\"}")))
        .then(r -> answer("fixed"));
    Chat.Reply reply = ask("go");
    assertEquals(Chat.DONE, reply.stop());
    assertEquals("fixed", reply.text());
  }

  @Test
  void aStoppedTurnClosesWithANoteForTheUserThatIsNotSaved() {
    tools(ECHO);
    chat(cfg(3, 0, 0, 0, 100));
    int[] n = {0};
    llm.then(r -> calls(call("c" + n[0], "echo", "{\"text\":\"" + (n[0]++) + "\"}")));
    StringBuilder streamed = new StringBuilder();
    Chat.Reply reply = Chat.ask(root, new Chat.Ask("s", "go", new CancelToken(), streamed::append)).orElseThrow();
    String note = "Stopped after 3 steps (max_steps) before finishing. Tools run this turn: echo ×2. Say \"continue\" "
        + "to go on from here.";
    assertEquals(note, reply.text());
    assertEquals(note, streamed.toString(), "the user sees it while the turn ends");
    assertTrue(conversation("s").stream().noneMatch(m -> m.content().contains("Stopped after")),
        "never saved as something the model said");
  }

  @Test
  void argumentOrderDoesNotHideARepeat() {
    tools(ECHO);
    chat(cfg(20, 0, 0, 0, 1));
    llm.then(r -> calls(call("a", "echo", "{\"text\":\"x\",\"n\":1}")))
        .then(r -> calls(call("b", "echo", "{\"n\":1,\"text\":\"x\"}")))
        .then(r -> answer("ok"));
    ask("go");
    assertTrue(toolMessages(llm.requests.get(2)).get(1).content().contains("same call"));
  }

  @Test
  void longOutputIsCut() {
    tools(ECHO);
    chat(cfg(0, 0, 0, 5, 0));
    llm.then(r -> calls(call("a", "echo", "{\"text\":\"abcdefgh\"}"))).then(r -> answer("ok"));
    ask("go");
    assertEquals("ABCDE\n… [truncated 3 chars]", toolMessages(llm.requests.get(1)).get(0).content());
  }

  /** {@code wait}: blocks until cancelled; counts down {@code started} first. */
  static Tool waiting(CountDownLatch started) {
    return new Tool() {
      @Override
      public ToolSpec spec() {
        return new ToolSpec("wait", "", null);
      }

      @Override
      public Risk risk() {
        return Risk.READ;
      }

      @Override
      public ToolResult call(JsonNode args, ToolContext ctx) {
        CountDownLatch release = new CountDownLatch(1);
        ctx.signal().onCancel(release::countDown);
        started.countDown();
        try {
          release.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        ctx.signal().throwIfCancelled();
        return ToolResult.ok("never");
      }
    };
  }

  @Test
  void slowToolsTimeOut() {
    tools(waiting(new CountDownLatch(1)));
    chat(cfg(0, 0, 1, 0, 0));
    llm.then(r -> calls(call("a", "wait", "{}"))).then(r -> answer("gave up"));
    Chat.Reply reply = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> ask("go"));
    assertEquals("gave up", reply.text());
    assertEquals("error: wait timed out after 1s", toolMessages(llm.requests.get(1)).get(0).content());
  }

  /** {@code write}: a tool of risk write, so the policy asks before it runs. */
  static final Tool WRITE = new Tool() {
    @Override
    public ToolSpec spec() {
      return new ToolSpec("write", "", null);
    }

    @Override
    public Risk risk() {
      return Risk.WRITE;
    }

    @Override
    public ToolResult call(JsonNode args, ToolContext ctx) {
      return ToolResult.ok("written");
    }
  };

  @Test
  void deniedCallsAreObservationsAndTheTurnGoesOn() {
    tools(WRITE);
    chat(ChatConfig.DEFAULT);
    llm.then(r -> calls(call("a", "write", "{}"))).then(r -> answer("could not write"));
    Chat.Reply reply = ask("go");
    assertEquals(Chat.DONE, reply.stop());
    assertEquals("could not write", reply.text());
    assertEquals("error: write: denied by the user", toolMessages(llm.requests.get(1)).get(0).content());
    assertEquals(ToolResult.error("write: denied by the user"), steps.get(1).result());
  }

  @Test
  void waitingForApprovalIsNotPartOfTheToolTimeout() {
    tools(WRITE);
    root.plugin("slow-user", ctx -> {
      ctx.hook(Tools.APPROVE, a -> {
        try {
          Thread.sleep(1500);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return Tools.Decision.ALLOW;
      });
      return null;
    });
    chat(cfg(0, 0, 1, 0, 0));
    llm.then(r -> calls(call("a", "write", "{}"))).then(r -> answer("ok"));
    assertTimeoutPreemptively(Duration.ofSeconds(10), () -> ask("go"));
    assertEquals(Message.tool("a", "written"), toolMessages(llm.requests.get(1)).get(0));
  }

  @Test
  void disposingChatCancelsARunningToolAndSavesNothing() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    tools(waiting(started));
    ConfigHandle<ChatConfig> chat = chat(ChatConfig.DEFAULT);
    llm.then(r -> calls(call("a", "wait", "{}"))).then(r -> answer("x"));

    Task<Optional<Chat.Reply>> pending = root.spawn("asker",
        signal -> Chat.ask(root, new Chat.Ask("c", "go", new CancelToken(), d -> { })));
    started.await();
    assertTimeoutPreemptively(Duration.ofSeconds(10), () -> chat.update(new ChatConfig("restart", 0)));
    assertThrows(CancelledException.class, pending::await);

    llm.script.clear();
    llm.then(r -> answer("fresh"));
    ask("again");
    assertEquals(List.of(Message.system("restart"), Message.user("again")),
        llm.requests.get(llm.requests.size() - 1).messages(), "the cancelled turn was not saved");
  }

  @Test
  void stepsAreEmittedAsEvents() {
    tools(ECHO);
    chat(ChatConfig.DEFAULT);
    List<Chat.Step> events = new ArrayList<>();
    root.plugin("audit", ctx -> {
      ctx.on(Chat.STEP, events::add);
      return null;
    });
    llm.then(r -> calls(call("a", "echo", "{\"text\":\"hi\"}"))).then(r -> answer("ok"));
    ask("go");
    assertEquals(2, events.size());
    assertEquals("c", events.get(1).conversationId());
  }

  @Test
  void askCommandPrintsToolCalls() {
    tools(ECHO);
    chat(ChatConfig.DEFAULT);
    llm.then(r -> new Completion("Let me check.", "m", "tool_calls", new Usage(1, 1),
            List.of(call("a", "echo", "{\"text\":\"hi\"}"), call("b", "nope", "{}"))))
        .then(r -> answer("HI."));
    StringBuilder out = new StringBuilder();
    Commands.run(root, "ask", "go", new Session("s", out::append, new CancelToken()));
    assertEquals("""
        Let me check.
        [tool echo {"text":"hi"}]
        [tool nope {}]
        [tool nope: error: unknown tool: nope]
        HI.
        [m · 17 tokens · 2 tools]
        """, out.toString());
  }

  private static ChatConfig parallel(int parallelTools, int timeoutSeconds) {
    return new ChatConfig(null, 0, true, 0, 0, timeoutSeconds, 0, 0, null, null, null, parallelTools);
  }

  /**
   * {@code meet {name}}: counts down {@code arrived}, then waits (at most 5s) for everyone to arrive, so it
   * only answers "met" when the calls run at the same time. {@code first} answers last.
   */
  static Tool meeting(CountDownLatch arrived) {
    return new Tool() {
      @Override
      public ToolSpec spec() {
        return new ToolSpec("meet", "", Json.parseOrNull(
            "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}"));
      }

      @Override
      public Risk risk() {
        return Risk.READ;
      }

      @Override
      public ToolResult call(JsonNode args, ToolContext ctx) {
        arrived.countDown();
        try {
          boolean met = arrived.await(5, java.util.concurrent.TimeUnit.SECONDS);
          if (args.path("name").asText().equals("first")) Thread.sleep(100);
          return ToolResult.ok(args.path("name").asText() + (met ? " met" : " alone"));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return ToolResult.error("interrupted");
        }
      }
    };
  }

  @Test
  void callsRunInParallelAndAnswerInTheirOrder() {
    tools(meeting(new CountDownLatch(3)), ECHO);
    chat(parallel(3, 0));
    llm.then(r -> calls(call("a", "meet", "{\"name\":\"first\"}"), call("b", "meet", "{\"name\":\"second\"}"),
            call("c", "echo", "{\"text\":\"x\"}"), call("d", "meet", "{\"name\":\"third\"}")))
        .then(r -> answer("done"));
    Chat.Reply reply = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> ask("go"));
    assertEquals(4, reply.toolCalls());
    assertEquals(List.of(Message.tool("a", "first met"), Message.tool("b", "second met"), Message.tool("c", "X"),
        Message.tool("d", "third met")), toolMessages(llm.requests.get(1)));
    assertEquals(8, steps.size(), "a start and an end for each call");
    assertEquals(List.of("a", "b", "c", "d"),
        steps.stream().filter(s -> !s.done()).map(s -> s.call().id()).sorted().toList());
  }

  @Test
  void oneAtATimeByDefault() {
    tools(meeting(new CountDownLatch(2)));
    chat(ChatConfig.DEFAULT);
    llm.then(r -> calls(call("a", "meet", "{\"name\":\"x\"}"), call("b", "meet", "{\"name\":\"y\"}")))
        .then(r -> answer("done"));
    ask("go");
    // the first call waits for a second one that only starts after it: it meets nobody
    assertEquals(Message.tool("a", "x alone"), toolMessages(llm.requests.get(1)).get(0));
  }

  @Test
  void loopDetectionStillCountsInCallOrder() {
    tools(ECHO);
    chat(new ChatConfig(null, 0, true, 0, 0, 0, 0, 1, null, null, null, 4));
    String same = "{\"text\":\"hi\"}";
    llm.then(r -> calls(call("a", "echo", same), call("b", "echo", same), call("c", "echo", same),
        call("d", "echo", same))).then(r -> answer("never"));
    Chat.Reply reply = ask("go");
    assertEquals(Chat.DONE, reply.stop(), "the refused repeats go back to the model, the turn goes on");
    assertEquals("never", reply.text());
    assertEquals(1, reply.toolCalls());
  }

  @Test
  void aTimeoutInOneParallelCallLeavesTheOthers() {
    tools(waiting(new CountDownLatch(1)), ECHO);
    chat(parallel(2, 1));
    llm.then(r -> calls(call("a", "wait", "{}"), call("b", "echo", "{\"text\":\"ok\"}"))).then(r -> answer("done"));
    assertTimeoutPreemptively(Duration.ofSeconds(10), () -> ask("go"));
    assertEquals(List.of(Message.tool("a", "error: wait timed out after 1s"), Message.tool("b", "OK")),
        toolMessages(llm.requests.get(1)));
  }

  @Test
  void cancellingTheAskCancelsEveryParallelCall() throws Exception {
    CountDownLatch started = new CountDownLatch(2);
    tools(waiting(started));
    chat(parallel(2, 0));
    llm.then(r -> calls(call("a", "wait", "{}"), call("b", "wait", "{\"x\":1}"))).then(r -> answer("x"));
    CancelToken token = new CancelToken();
    Task<Optional<Chat.Reply>> pending = root.spawn("asker",
        signal -> Chat.ask(root, new Chat.Ask("c", "go", token, d -> { })));
    started.await();
    token.cancel();
    assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThrows(CancelledException.class, pending::await));
  }

  @Test
  void disposingChatCancelsAndAwaitsParallelCalls() throws Exception {
    CountDownLatch started = new CountDownLatch(2);
    tools(waiting(started));
    ConfigHandle<ChatConfig> chat = chat(parallel(2, 0));
    llm.then(r -> calls(call("a", "wait", "{}"), call("b", "wait", "{\"x\":1}"))).then(r -> answer("x"));
    Task<Optional<Chat.Reply>> pending = root.spawn("asker",
        signal -> Chat.ask(root, new Chat.Ask("c", "go", new CancelToken(), d -> { })));
    started.await();
    assertTimeoutPreemptively(Duration.ofSeconds(10), () -> chat.update(parallel(3, 0)));
    assertThrows(CancelledException.class, pending::await);
  }
}
