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
import java.util.List;
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
      return new Completion(id + ":" + r.model() + ":" + r.temperature(), r.model(), "stop", Usage.NONE);
    }

    @Override
    public Completion stream(ChatRequest r, Signal s, Consumer<String> onDelta) {
      Completion c = complete(r, s);
      onDelta.accept(c.text());
      return c;
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
}
