package panic.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import context.ConfigHandle;
import context.Context;
import context.Root;
import context.Task;
import core.CancelledException;
import core.Signal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import panic.command.Commands;
import panic.command.Prompts;
import panic.command.Session;
import panic.util.CancelToken;
import panic.llm.ChatRequest;
import panic.llm.Completion;
import panic.llm.Llm;
import panic.llm.Message;
import panic.llm.Provider;
import panic.llm.Usage;

class ChatPluginTest {

  /** Replies "n=<number of messages it received>" in two deltas; records every request. */
  static final class FakeLlm implements Llm {
    final List<ChatRequest> requests = new CopyOnWriteArrayList<>();
    final String name;
    volatile CountDownLatch block;

    FakeLlm(String name) {
      this.name = name;
    }

    @Override
    public Provider provider() {
      throw new UnsupportedOperationException();
    }

    @Override
    public String model() {
      return name;
    }

    @Override
    public Completion complete(ChatRequest r, Signal s) {
      return stream(r, s, d -> { });
    }

    @Override
    public Completion stream(ChatRequest r, Signal s, Consumer<String> onDelta) {
      requests.add(r);
      onDelta.accept("n=");
      CountDownLatch b = block;
      if (b != null) {
        CountDownLatch release = new CountDownLatch(1);
        s.onCancel(release::countDown);
        b.countDown();
        try {
          release.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        s.throwIfCancelled();
      }
      onDelta.accept(String.valueOf(r.messages().size()));
      return new Completion("n=" + r.messages().size(), name, "stop", new Usage(1, 1));
    }
  }

  private final Root root = Context.root("t");

  @AfterEach
  void dispose() {
    root.dispose();
  }

  /** Provides the fake as Llm.KEY; configs true/false connect/disconnect it. */
  private ConfigHandle<Boolean> llm(FakeLlm fake) {
    return root.plugin("llm", (ctx, on) -> {
      if (on) ctx.provide(Llm.KEY, fake);
      return null;
    }, true);
  }

  private Optional<Chat.Reply> ask(String text, Consumer<String> onDelta) {
    return Chat.ask(root, new Chat.Ask("c", text, new CancelToken(), onDelta));
  }

  @Test
  void keepsHistoryAndSendsSystemPrompt() {
    FakeLlm fake = new FakeLlm("m");
    llm(fake);
    root.plugin("conversations", Conversations.inMemory());
    root.plugin("chat", new ChatPlugin(), new ChatConfig("sys", 0));
    List<Chat.Turn> turns = new ArrayList<>();
    root.plugin("audit", ctx -> {
      ctx.on(Chat.TURN, turns::add);
      return null;
    });

    StringBuilder streamed = new StringBuilder();
    assertEquals("n=2", ask("one", streamed::append).orElseThrow().text());
    assertEquals("n=2", streamed.toString());
    assertEquals("n=4", ask("two", d -> { }).orElseThrow().text());

    List<Message> second = fake.requests.get(1).messages();
    assertEquals(List.of(Message.system("sys"), Message.user("one"), Message.assistant("n=2"), Message.user("two")),
        second);
    assertEquals(2, turns.size());
    assertEquals("two", turns.get(1).question());
  }

  /** Answers {@code /hello} with a skill-like prompt, recording what it was asked. */
  private List<Prompts.Query> prompts(String tool) {
    List<Prompts.Query> asked = new CopyOnWriteArrayList<>();
    root.plugin("prompts", ctx -> {
      ctx.hook(Prompts.EXPAND, q -> {
        if (!q.name().equals("hello")) return null;
        asked.add(q);
        return new Prompts.Prompt("hello", "says hello", tool, "{\"name\":\"hello\"}", "Say hello to " + q.args());
      });
      return null;
    });
    return asked;
  }

  @Test
  void expandsASlashPromptAsAToolCallAfterTheUsersLine() {
    FakeLlm fake = new FakeLlm("m");
    llm(fake);
    root.plugin("conversations", Conversations.inMemory());
    root.plugin("chat", new ChatPlugin(), new ChatConfig(null, 0));
    List<Prompts.Query> asked = prompts("skill");
    List<Chat.Step> steps = new CopyOnWriteArrayList<>();

    Chat.ask(root, new Chat.Ask("c", "/hello  bob", new CancelToken(), d -> { }, steps::add)).orElseThrow();

    assertEquals(List.of(new Prompts.Query("c", "hello", "bob")), asked);
    List<Message> sent = fake.requests.get(0).messages();
    assertEquals(3, sent.size());
    assertEquals(Message.user("/hello  bob"), sent.get(0));
    assertEquals("skill", sent.get(1).toolCalls().get(0).name());
    assertEquals("{\"name\":\"hello\"}", sent.get(1).toolCalls().get(0).arguments());
    assertEquals(Message.tool(sent.get(1).toolCalls().get(0).id(), "Say hello to bob"), sent.get(2));
    assertEquals(2, steps.size(), "running, then done");
    assertEquals("Say hello to bob", steps.get(1).result().content());
    // the next turn sees the same history: the expansion was saved
    ask("again", d -> { });
    assertEquals(5, fake.requests.get(1).messages().size());
  }

  @Test
  void expandsAPromptWithoutAToolAsAUserMessage() {
    FakeLlm fake = new FakeLlm("m");
    llm(fake);
    root.plugin("conversations", Conversations.inMemory());
    root.plugin("chat", new ChatPlugin(), new ChatConfig(null, 0));
    prompts(null);
    ask("/hello bob", d -> { });
    assertEquals(List.of(Message.user("/hello bob"), Message.user("Say hello to bob")),
        fake.requests.get(0).messages());
  }

  @Test
  void sendsASlashLineAsIsWhenNobodyExpandsIt() {
    FakeLlm fake = new FakeLlm("m");
    llm(fake);
    root.plugin("conversations", Conversations.inMemory());
    root.plugin("chat", new ChatPlugin(), new ChatConfig(null, 0));
    prompts("skill");
    ask("/other x", d -> { });
    ask("/etc/hosts is broken", d -> { });
    assertEquals(List.of(Message.user("/other x")), fake.requests.get(0).messages());
    assertEquals(Message.user("/etc/hosts is broken"), fake.requests.get(1).messages().get(2));
  }

  @Test
  void neverExpandsASubAgentsTask() {
    FakeLlm fake = new FakeLlm("m");
    llm(fake);
    root.plugin("conversations", Conversations.inMemory());
    root.plugin("chat", new ChatPlugin(), new ChatConfig(null, 0));
    List<Prompts.Query> asked = prompts("skill");
    Chat.ask(root, new Chat.Ask("c~x", "/hello bob", new CancelToken(), d -> { }, null, null, null, null, null,
        new Chat.Delegate("c", "x", null, null, 0, 0, null))).orElseThrow();
    assertTrue(asked.isEmpty());
    List<Message> sent = fake.requests.get(0).messages();
    assertEquals(Message.user("/hello bob"), sent.get(sent.size() - 1));
  }

  @Test
  void trimsHistoryWithoutStartingOnAReply() {
    FakeLlm fake = new FakeLlm("m");
    llm(fake);
    root.plugin("conversations", Conversations.inMemory());
    root.plugin("chat", new ChatPlugin(), new ChatConfig(null, 3));
    ask("1", d -> { });
    ask("2", d -> { });
    ask("3", d -> { });
    // history is [u1 a1 u2 a2]; the last 3 would start with a1, so only [u2 a2] is sent, then u3
    assertEquals(List.of(Message.user("2"), Message.assistant("n=3"), Message.user("3")),
        fake.requests.get(2).messages());
  }

  @Test
  void noLlmMeansNoAnswerAndHistorySurvivesASwitch() {
    FakeLlm first = new FakeLlm("first");
    ConfigHandle<Boolean> connected = llm(first);
    root.plugin("conversations", Conversations.inMemory());
    root.plugin("chat", new ChatPlugin(), ChatConfig.DEFAULT);
    ask("hello", d -> { });

    connected.update(false);
    assertTrue(ask("anyone?", d -> { }).isEmpty());

    connected.update(true);
    assertEquals("n=4", ask("again", d -> { }).orElseThrow().text(), "system + hello + reply + again");
  }

  @Test
  void askAndResetCommandsUseTheSessionsConversation() {
    FakeLlm fake = new FakeLlm("m");
    ConfigHandle<Boolean> connected = llm(fake);
    root.plugin("conversations", Conversations.inMemory());
    root.plugin("chat", new ChatPlugin(), new ChatConfig(null, 0));
    StringBuilder out = new StringBuilder();
    Session alice = new Session("alice", out::append, new CancelToken());
    Session bob = new Session("bob", d -> { }, new CancelToken());

    assertTrue(Commands.run(root, "ask", "hi there", alice));
    assertEquals("n=1\n[m · 2 tokens]\n", out.toString());
    Commands.run(root, "ask", "again", alice);
    assertEquals(3, fake.requests.get(1).messages().size(), "alice's history");
    Commands.run(root, "ask", "hello", bob);
    assertEquals(1, fake.requests.get(2).messages().size(), "bob has his own conversation");

    Commands.run(root, "reset", "", alice);
    Commands.run(root, "ask", "fresh", alice);
    assertEquals(1, fake.requests.get(3).messages().size());

    connected.update(false);
    assertTrue(Commands.find(root, "ask").isEmpty(), "no llm: no ask command");
    assertTrue(Commands.run(root, "reset", "", alice), "reset does not need an llm");
  }

  @Test
  void disposingChatCancelsTheReplyInFlightAndWaitsForIt() throws Exception {
    FakeLlm fake = new FakeLlm("m");
    fake.block = new CountDownLatch(1);
    llm(fake);
    root.plugin("conversations", Conversations.inMemory());
    ConfigHandle<ChatConfig> chat = root.plugin("chat", new ChatPlugin(), ChatConfig.DEFAULT);

    Task<Optional<Chat.Reply>> pending = root.spawn("asker", signal -> ask("slow", d -> { }));
    fake.block.await();
    assertTimeoutPreemptively(Duration.ofSeconds(10), () -> chat.update(new ChatConfig("new prompt", 10)));
    assertThrows(CancelledException.class, pending::await);

    fake.block = null;
    assertEquals("n=3", ask("fast", d -> { }).orElseThrow().text(), "the cancelled turn kept its question");
  }

  /** Thinks "17*23\n= 391" in two pieces, then answers like {@link FakeLlm}. */
  static final class ThinkingLlm implements Llm {
    final FakeLlm inner = new FakeLlm("think");

    @Override
    public Provider provider() {
      throw new UnsupportedOperationException();
    }

    @Override
    public String model() {
      return "think";
    }

    @Override
    public Completion complete(ChatRequest r, Signal s) {
      return inner.complete(r, s);
    }

    @Override
    public Completion stream(ChatRequest r, Signal s, Consumer<String> onDelta) {
      return inner.stream(r, s, onDelta);
    }

    @Override
    public Completion stream(ChatRequest r, Signal s, Consumer<String> onDelta,
        Consumer<panic.llm.ToolCallDelta> onToolDelta, Consumer<String> onReasoning) {
      onReasoning.accept("17*23\n");
      onReasoning.accept("= 391");
      Completion c = inner.stream(r, s, onDelta);
      return new Completion(c.text(), c.model(), c.finishReason(), new Usage(1, 5, 4), List.of(), "17*23\n= 391");
    }
  }

  @Test
  void reasoningIsShownAndKeptButNeverSentBack() {
    ThinkingLlm think = new ThinkingLlm();
    root.plugin("llm", (ctx, on) -> {
      ctx.provide(Llm.KEY, think);
      return null;
    }, true);
    root.plugin("conversations", Conversations.inMemory());
    root.plugin("chat", new ChatPlugin(), new ChatConfig("sys", 0));
    List<panic.trace.Span> spans = new CopyOnWriteArrayList<>();
    root.plugin("tracing", ctx -> {
      ctx.on(panic.trace.Trace.SPAN, spans::add);
      return null;
    });

    List<String> thoughts = new ArrayList<>();
    Chat.Reply reply = Chat.ask(root, new Chat.Ask("c", "17*23?", new CancelToken(), d -> { })
        .withReasoning(thoughts::add)).orElseThrow();
    assertEquals(List.of("17*23\n", "= 391"), thoughts);
    assertEquals(4, reply.usage().reasoningTokens());
    panic.trace.Span llm = spans.stream().filter(s -> s.name().equals("llm")).findFirst().orElseThrow();
    assertEquals(11, llm.longAttr("reasoningChars", -1));
    assertEquals(4, llm.longAttr("reasoningTokens", -1));
    assertTrue(llm.longAttr("firstReasoningMicros", -1) >= 0);

    StringBuilder out = new StringBuilder();
    Commands.run(root, "ask", "again", new Session("c", out::append, new CancelToken()));
    Message first = Message.assistant("n=2", List.of(), "17*23\n= 391");
    assertEquals(List.of(Message.system("sys"), Message.user("17*23?"), first, Message.user("again")),
        think.inner.requests.get(1).messages(), "the history keeps the reasoning for the user to read");
    assertEquals(first, panic.command.Hooks.bail(root, Conversations.HISTORY, new Conversations.HistoryQuery("c"))
        .orElseThrow().messages().get(1));
    assertTrue(out.toString().startsWith("│ 17*23\n│ = 391\n[thought 0s]\nn=4\n"), out.toString());
    assertTrue(out.toString().contains("(4 thinking)"), out.toString());
  }
}
