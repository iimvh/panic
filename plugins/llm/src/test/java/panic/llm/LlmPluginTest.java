package panic.llm;

import panic.util.CancelToken;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import context.ConfigHandle;
import context.ConfigPlugin;
import context.Context;
import context.Root;
import core.Signal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import panic.command.Command;
import panic.command.Commands;
import panic.command.Session;

class LlmPluginTest {

  /** A provider that answers "<id>:<model>". */
  record Echo(String id) implements Provider, ChatModel {
    @Override
    public ChatModel chat() {
      return this;
    }

    @Override
    public List<ModelInfo> models(Signal signal) {
      return List.of();
    }

    @Override
    public KeyCheck checkKey(Signal signal) {
      return new KeyCheck(true, "ok");
    }

    @Override
    public Completion complete(ChatRequest r, Signal s) {
      // the effort the provider got comes back as the reasoning, so tests can see it
      return new Completion(id + ":" + r.model() + ":" + r.temperature(), r.model(), "stop", Usage.NONE, List.of(),
          "effort=" + r.reasoningEffort());
    }

    @Override
    public Completion stream(ChatRequest r, Signal s, Consumer<String> onDelta) {
      Completion c = complete(r, s);
      onDelta.accept(c.text());
      return c;
    }

    /** Thinks "hmm" first: shows whether a wrapper forwards the reasoning. */
    @Override
    public Completion stream(ChatRequest r, Signal s, Consumer<String> onDelta, Consumer<ToolCallDelta> onToolDelta,
        Consumer<String> onReasoning) {
      onReasoning.accept("hmm");
      return stream(r, s, onDelta);
    }
  }

  /** Stands in for OpenRouter/Mistral: provides Echo(id) while "connected" is true. */
  static ConfigPlugin<Boolean> fakeProvider(String id) {
    return (ctx, connected) -> {
      if (connected) ctx.provide(Providers.key(id), new Echo(id));
      return null;
    };
  }

  private final Root root = Context.root("t");

  @AfterEach
  void dispose() {
    root.dispose();
  }

  private String ask() {
    Llm llm = root.bail(Llm.CURRENT, Llm.Query.CURRENT).orElse(null);
    return llm == null ? null : llm.complete(ChatRequest.of(List.of(Message.user("q"))), new CancelToken()).text();
  }

  @Test
  void followsConfigAndProviderAvailability() {
    ConfigHandle<Boolean> a = root.plugin("a", fakeProvider("a"), true);
    root.plugin("b", fakeProvider("b"), false);
    ConfigHandle<LlmConfig> llm = root.plugin("llm", new LlmPlugin(), new LlmConfig("a", "m1", 0.3, null));

    assertTrue(root.isProvided(Llm.KEY));
    assertEquals("a:m1:0.3", ask());

    llm.update(llm.config().withModel("m2"));
    assertEquals("a:m2:0.3", ask());

    llm.update(llm.config().withProvider("b"));
    assertFalse(root.isProvided(Llm.KEY), "b has no key yet: no llm");
    assertEquals(null, ask());

    llm.update(llm.config().withProvider("a"));
    a.update(false);
    assertFalse(root.isProvided(Llm.KEY), "provider retracted: llm retracted");
    a.update(true);
    assertTrue(root.isProvided(Llm.KEY), "provider back: llm back, nothing to do");
    assertEquals("a:m2:0.3", ask());
  }

  @Test
  void providerCommandsExistOnlyWhileConnected() {
    ConfigHandle<Boolean> a = root.plugin("a", fakeProvider("a"), false);
    root.plugin("llm", new LlmPlugin(), new LlmConfig("a", "m1", null, null));
    StringBuilder out = new StringBuilder();
    Session session = new Session("s", out::append, new CancelToken());

    assertEquals(List.of("status"), Commands.list(root).stream().map(Command::name).toList());
    Commands.run(root, "status", "", session);
    assertTrue(out.toString().contains("(not connected)"), out.toString());

    a.update(true);
    assertEquals(List.of("check", "models", "status"), Commands.list(root).stream().map(Command::name).toList());
    assertTrue(Commands.run(root, "check", "", session));
    assertTrue(out.toString().contains("ok: ok"), out.toString());

    a.update(false);
    assertFalse(Commands.run(root, "check", "", session), "provider gone: its commands are gone");
  }

  @Test
  void requestOptionsWinOverDefaults() {
    root.plugin("a", fakeProvider("a"), true);
    root.plugin("llm", new LlmPlugin(), new LlmConfig("a", "m1", 0.3, null));
    Llm llm = root.bail(Llm.CURRENT, Llm.Query.CURRENT).orElseThrow();
    ChatRequest custom = new ChatRequest(List.of(Message.user("q")), "other", 1.0, null);
    assertEquals("a:other:1.0", llm.complete(custom, new CancelToken()).text());
  }

  @Test
  void theConfiguredEffortIsTheDefaultAndARequestMayTurnItOff() {
    root.plugin("a", fakeProvider("a"), true);
    ConfigHandle<LlmConfig> cfg = root.plugin("llm", new LlmPlugin(), new LlmConfig("a", "m1", null, null, " high "));
    Llm llm = root.bail(Llm.CURRENT, Llm.Query.CURRENT).orElseThrow();
    ChatRequest q = ChatRequest.of(List.of(Message.user("q")));
    assertEquals("effort=high", llm.complete(q, new CancelToken()).reasoning());
    assertEquals("effort=low", llm.complete(q.withReasoningEffort("low"), new CancelToken()).reasoning());
    ChatRequest none = q.withReasoningEffort(ChatRequest.NO_REASONING_EFFORT);
    assertEquals("effort=", llm.complete(none, new CancelToken()).reasoning(), "kept as none, not the default");
    assertEquals(null, none.withDefaults("m", null, null, "high").sentReasoningEffort());

    cfg.update(cfg.config().withReasoningEffort(" "));
    llm = root.bail(Llm.CURRENT, Llm.Query.CURRENT).orElseThrow();
    assertEquals("effort=null", llm.complete(q, new CancelToken()).reasoning(), "blank in the config = none");
  }

  @Test
  void streamForwardsTheReasoning() {
    root.plugin("a", fakeProvider("a"), true);
    root.plugin("llm", new LlmPlugin(), new LlmConfig("a", "m1", null, null));
    Llm llm = root.bail(Llm.CURRENT, Llm.Query.CURRENT).orElseThrow();
    List<String> thoughts = new ArrayList<>();
    llm.stream(ChatRequest.of(List.of(Message.user("q"))), new CancelToken(), d -> { }, d -> { }, thoughts::add);
    assertEquals(List.of("hmm"), thoughts, "the current llm passes the reasoning on, not only text and tool calls");
  }

  /** Lists one model with a context length (and a reply limit and parameters when given), as OpenRouter does. */
  record Listed(String id, long contextLength, long maxOutput, Set<String> parameters) implements Provider {
    Listed(String id, long contextLength) {
      this(id, contextLength, 0, Set.of());
    }

    @Override
    public ChatModel chat() {
      return new Echo(id);
    }

    @Override
    public List<ModelInfo> models(Signal signal) {
      return List.of(new ModelInfo("other", "Other", 8_000, null),
          new ModelInfo("m1", "M1", contextLength, null, maxOutput, parameters));
    }

    @Override
    public KeyCheck checkKey(Signal signal) {
      return new KeyCheck(true, "ok");
    }
  }

  private int window() throws InterruptedException {
    for (int i = 0; i < 400; i++) {
      Llm llm = root.bail(Llm.CURRENT, Llm.Query.CURRENT).orElse(null);
      if (llm != null && llm.inputWindow() > 0) return llm.inputWindow();
      Thread.sleep(5);
    }
    return 0;
  }

  @Test
  void theInputWindowIsTheListedContextLengthLessTheReply() throws InterruptedException {
    root.plugin("p", ctx -> {
      ctx.provide(Providers.key("p"), new Listed("p", 131_072));
      return null;
    });
    root.plugin("llm", new LlmPlugin(), new LlmConfig("p", "m1", null, 16_384));
    assertEquals(131_072 - 16_384, window());
  }

  @Test
  void aConfiguredContextWindowWinsAndAModelSwitchDropsIt() throws InterruptedException {
    root.plugin("p", ctx -> {
      ctx.provide(Providers.key("p"), new Listed("p", 0)); // a host that lists ids only
      return null;
    });
    ConfigHandle<LlmConfig> llm = root.plugin("llm", new LlmPlugin(),
        new LlmConfig("p", "m1", null, 1_000, null, 64_000));
    assertEquals(63_000, window());
    llm.update(llm.config().withModel("m1"));
    assertEquals(null, llm.config().contextWindow());
    assertEquals(0, root.bail(Llm.CURRENT, Llm.Query.CURRENT).orElseThrow().inputWindow(), "unknown again");
  }

  private void listed(Listed provider) {
    root.plugin("p", ctx -> {
      ctx.provide(Providers.key("p"), provider);
      return null;
    });
  }

  /** The current Llm once its model info came (the lookup runs in the background). */
  private Llm looked() throws InterruptedException {
    for (int i = 0; i < 400; i++) {
      Llm llm = root.bail(Llm.CURRENT, Llm.Query.CURRENT).orElse(null);
      if (llm != null && llm.info() != null) return llm;
      Thread.sleep(5);
    }
    throw new AssertionError("no model info");
  }

  @Test
  void withoutMaxTokensTheReplyRoomComesFromTheModelsLimit() throws InterruptedException {
    listed(new Listed("p", 131_072, 4_096, Set.of()));
    root.plugin("llm", new LlmPlugin(), new LlmConfig("p", "m1", null, null));
    assertEquals(131_072 - 4_096, looked().inputWindow(), "a small limit is all the reply can take");
  }

  @Test
  void aLargeReplyLimitKeepsAtMostTheReplyRoom() throws InterruptedException {
    listed(new Listed("p", 200_000, 128_000, Set.of()));
    root.plugin("llm", new LlmPlugin(), new LlmConfig("p", "m1", null, null));
    assertEquals(200_000 - Llm.REPLY_ROOM, looked().inputWindow());
  }

  @Test
  void anUnknownReplyLimitKeepsTheDefaultRoom() throws InterruptedException {
    listed(new Listed("p", 131_072));
    root.plugin("llm", new LlmPlugin(), new LlmConfig("p", "m1", null, null));
    assertEquals(131_072 - Llm.DEFAULT_REPLY_ROOM, looked().inputWindow());
  }

  @Test
  void theEffortIsNotSentToAModelListedWithoutReasoning() throws InterruptedException {
    listed(new Listed("p", 8_000, 0, Set.of("tools", "temperature")));
    root.plugin("llm", new LlmPlugin(), new LlmConfig("p", "m1", null, null, "high"));
    Llm llm = looked();
    assertEquals(Boolean.FALSE, llm.info().reasoning());
    ChatRequest q = ChatRequest.of(List.of(Message.user("q")));
    assertEquals("effort=null", llm.complete(q, new CancelToken()).reasoning(), "the default is left out");
    assertEquals("effort=low", llm.complete(q.withReasoningEffort("low"), new CancelToken()).reasoning(),
        "a request's own effort is still sent");
    List<String> out = new ArrayList<>();
    Commands.run(root, Commands.STATUS, "", new Session("t", out::add, new CancelToken()));
    assertTrue(String.join("", out).contains("effort: high (the model takes none: not sent)"), out.toString());
  }

  @Test
  void theEffortIsSentToAReasoningModelAndWhenNobodyKnows() throws InterruptedException {
    listed(new Listed("p", 8_000, 0, Set.of("reasoning", "tools")));
    root.plugin("llm", new LlmPlugin(), new LlmConfig("p", "m1", null, null, "high"));
    ChatRequest q = ChatRequest.of(List.of(Message.user("q")));
    assertEquals("effort=high", looked().complete(q, new CancelToken()).reasoning());
    assertEquals(null, new ModelInfo("x", "x", 0, null).reasoning(), "no parameters listed: unknown");
  }
}
